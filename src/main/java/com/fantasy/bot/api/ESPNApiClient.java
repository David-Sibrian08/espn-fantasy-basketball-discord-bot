package com.fantasy.bot.api;

import com.fantasy.bot.config.BotConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ESPNApiClient {
    private static final Logger log = LoggerFactory.getLogger(ESPNApiClient.class);

    // Prefer read-only host to avoid redirects
    private static final String HOST_READONLY = "https://lm-api-reads.fantasy.espn.com";
    private static final String HOST_PRIMARY = "https://fantasy.espn.com";

    // Short TTL cache so bursts of interactions (e.g. matchup pager buttons)
    // don't hammer ESPN with duplicate requests for the same season.
    private static final long CACHE_TTL_MILLIS = 30_000;

    private final OkHttpClient client;
    private final String leagueId;
    private final String defaultSeasonId;
    private final String espnS2;
    private final String swid;

    private final Map<String, CachedResponse> cache = new ConcurrentHashMap<>();

    private record CachedResponse(JsonObject data, long fetchedAtMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() - fetchedAtMillis < CACHE_TTL_MILLIS;
        }
    }

    public ESPNApiClient() {
        BotConfig config = BotConfig.get();

        this.client = new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
        this.leagueId = config.getEspnLeagueId();
        this.defaultSeasonId = config.getEspnSeasonId();
        this.espnS2 = config.getEspnS2();
        this.swid = config.getSwid();

        if (espnS2 == null || espnS2.isBlank() || swid == null || swid.isBlank()) {
            log.warn("ESPN_S2 and/or SWID not set. Private leagues will fail!");
        }
    }

    private String buildLeagueUrl(String host, String seasonId) {
        return host + "/apis/v3/games/fba/seasons/" + seasonId +
                "/segments/0/leagues/" + leagueId +
                "?view=mMatchup&view=mStandings&view=mTeam&view=mSettings";
    }

    public JsonObject getLeagueData() throws IOException {
        return getLeagueData(Integer.parseInt(defaultSeasonId));
    }

    public JsonObject getLeagueData(int seasonId) throws IOException {
        String seasonStr = String.valueOf(seasonId);

        CachedResponse cached = cache.get(seasonStr);
        if (cached != null && cached.isFresh()) {
            return cached.data();
        }

        JsonObject data;
        try {
            data = fetchLeagueData(HOST_READONLY, seasonStr);
        } catch (IOException e) {
            log.info("Read-only host failed for season {}, trying primary host...", seasonStr);
            data = fetchLeagueData(HOST_PRIMARY, seasonStr);
        }

        cache.put(seasonStr, new CachedResponse(data, System.currentTimeMillis()));
        return data;
    }

    private JsonObject fetchLeagueData(String host, String seasonId) throws IOException {
        String url = buildLeagueUrl(host, seasonId);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0");

        if (espnS2 != null && !espnS2.isBlank() && swid != null && !swid.isBlank()) {
            requestBuilder.addHeader("Cookie", "espn_s2=" + espnS2 + "; SWID=" + swid);
        }

        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            log.debug("ESPN API response code: {}", response.code());

            if (response.code() == 302 || response.code() == 301) {
                throw new IOException("Got redirect (302/301). Your league is likely private. Please set ESPN_S2 and SWID environment variables.");
            }

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }

            String responseBody = response.body().string();

            if (responseBody.trim().startsWith("<")) {
                throw new IOException("Received HTML instead of JSON. Your league might be private - set ESPN_S2 and SWID cookies.");
            }

            return JsonParser.parseString(responseBody).getAsJsonObject();
        }
    }
}
