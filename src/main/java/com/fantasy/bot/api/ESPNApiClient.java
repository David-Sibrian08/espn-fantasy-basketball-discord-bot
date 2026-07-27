package com.fantasy.bot.api;

import com.fantasy.bot.config.BotConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ESPNApiClient {
    private static final Logger log = LoggerFactory.getLogger(ESPNApiClient.class);

    // Prefer read-only host to avoid redirects
    private static final String HOST_READONLY = "https://lm-api-reads.fantasy.espn.com";
    private static final String HOST_PRIMARY = "https://fantasy.espn.com";

    // Short TTL cache so bursts of interactions (e.g. matchup pager buttons)
    // don't hammer ESPN with duplicate requests for the same season.
    private static final long LEAGUE_CACHE_TTL_MILLIS = 30_000;

    // Pro team schedules barely change intra-day, so this can be cached much longer.
    private static final long SCHEDULE_CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L;

    // A past day's roster/stats snapshot is immutable once final, so this can be cached long-term too.
    private static final long DAY_ROSTER_CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L;

    // Transient failures (timeouts, connection resets, 5xx) get one retry per
    // host before falling back to the other host — a real config problem
    // (bad league ID, private league without cookies) won't be fixed by
    // retrying, so those fail immediately instead of wasting time.
    private static final int MAX_ATTEMPTS_PER_HOST = 2;
    private static final long RETRY_BACKOFF_MILLIS = 500;

    private final OkHttpClient client;
    private final String leagueId;
    private final String defaultSeasonId;
    private final String espnS2;
    private final String swid;
    private final String hostReadonly;
    private final String hostPrimary;
    private final long leagueCacheTtlMillis;
    private final long scheduleCacheTtlMillis;
    private final long dayRosterCacheTtlMillis;

    private final Map<String, CachedResponse> leagueCache = new ConcurrentHashMap<>();
    private final Map<String, CachedResponse> scheduleCache = new ConcurrentHashMap<>();
    private final Map<String, CachedResponse> dayRosterCache = new ConcurrentHashMap<>();

    private record CachedResponse(JsonObject data, long fetchedAtMillis, long ttlMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() - fetchedAtMillis < ttlMillis;
        }
    }

    /** Thrown for failures worth retrying (network blips, 5xx) as opposed to config problems. */
    private static class TransientEspnException extends IOException {
        TransientEspnException(String message) {
            super(message);
        }

        TransientEspnException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ESPNApiClient() {
        this(defaultClient(), BotConfig.get().getEspnLeagueId(), BotConfig.get().getEspnSeasonId(),
                BotConfig.get().getEspnS2(), BotConfig.get().getSwid(), HOST_READONLY, HOST_PRIMARY,
                LEAGUE_CACHE_TTL_MILLIS, SCHEDULE_CACHE_TTL_MILLIS, DAY_ROSTER_CACHE_TTL_MILLIS);
    }

    /**
     * Package-private: lets tests point both hosts at a fake server and use
     * short cache TTLs, instead of real ESPN endpoints and hours-long TTLs.
     */
    ESPNApiClient(OkHttpClient client, String leagueId, String defaultSeasonId, String espnS2, String swid,
                  String hostReadonly, String hostPrimary,
                  long leagueCacheTtlMillis, long scheduleCacheTtlMillis, long dayRosterCacheTtlMillis) {
        this.client = client;
        this.leagueId = leagueId;
        this.defaultSeasonId = defaultSeasonId;
        this.espnS2 = espnS2;
        this.swid = swid;
        this.hostReadonly = hostReadonly;
        this.hostPrimary = hostPrimary;
        this.leagueCacheTtlMillis = leagueCacheTtlMillis;
        this.scheduleCacheTtlMillis = scheduleCacheTtlMillis;
        this.dayRosterCacheTtlMillis = dayRosterCacheTtlMillis;

        if (espnS2 == null || espnS2.isBlank() || swid == null || swid.isBlank()) {
            log.warn("ESPN_S2 and/or SWID not set. Private leagues will fail!");
        }
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
    }

    private String buildLeagueUrl(String host, String seasonId) {
        return host + "/apis/v3/games/fba/seasons/" + seasonId +
                "/segments/0/leagues/" + leagueId +
                "?view=mMatchup&view=mStandings&view=mTeam&view=mSettings&view=mRoster";
    }

    private String buildProTeamScheduleUrl(String host, String seasonId) {
        return host + "/apis/v3/games/fba/seasons/" + seasonId + "?view=proTeamSchedules_wl";
    }

    public JsonObject getLeagueData() throws IOException {
        return getLeagueData(Integer.parseInt(defaultSeasonId));
    }

    public JsonObject getLeagueData(int seasonId) throws IOException {
        String seasonStr = String.valueOf(seasonId);
        return getCachedOrFetch(leagueCache, seasonStr, leagueCacheTtlMillis, this::buildLeagueUrl);
    }

    /** Real-world NBA game schedule per pro team, keyed by scoringPeriodId (day). */
    public JsonObject getProTeamSchedules() throws IOException {
        return getProTeamSchedules(Integer.parseInt(defaultSeasonId));
    }

    public JsonObject getProTeamSchedules(int seasonId) throws IOException {
        String seasonStr = String.valueOf(seasonId);
        return getCachedOrFetch(scheduleCache, seasonStr, scheduleCacheTtlMillis, this::buildProTeamScheduleUrl);
    }

    /**
     * Every team's roster/lineup snapshot AS IT WAS on a specific day, with
     * each rostered player's actual fantasy points for that day
     * (playerPoolEntry.appliedStatTotal). One bulk call covers all teams.
     */
    public JsonObject getRosterForDay(int scoringPeriodId) throws IOException {
        return getRosterForDay(Integer.parseInt(defaultSeasonId), scoringPeriodId);
    }

    public JsonObject getRosterForDay(int seasonId, int scoringPeriodId) throws IOException {
        String cacheKey = seasonId + ":" + scoringPeriodId;
        return getCachedOrFetch(dayRosterCache, cacheKey, dayRosterCacheTtlMillis,
                (host, ignoredKey) -> buildLeagueUrl(host, String.valueOf(seasonId)) + "&scoringPeriodId=" + scoringPeriodId);
    }

    private interface UrlBuilder {
        String build(String host, String seasonId);
    }

    private JsonObject getCachedOrFetch(Map<String, CachedResponse> cache, String seasonStr, long ttlMillis, UrlBuilder urlBuilder) throws IOException {
        CachedResponse cached = cache.get(seasonStr);
        if (cached != null && cached.isFresh()) {
            return cached.data();
        }

        try {
            JsonObject data = fetchFromEitherHost(urlBuilder, seasonStr);
            cache.put(seasonStr, new CachedResponse(data, System.currentTimeMillis(), ttlMillis));
            return data;
        } catch (IOException e) {
            if (cached != null) {
                Duration age = Duration.ofMillis(System.currentTimeMillis() - cached.fetchedAtMillis());
                log.error("ESPN fetch failed, serving stale cached data from {} ago instead: {}", age, e.getMessage());
                return cached.data();
            }
            throw e;
        }
    }

    private JsonObject fetchFromEitherHost(UrlBuilder urlBuilder, String seasonStr) throws IOException {
        try {
            return fetchJsonWithRetry(urlBuilder.build(hostReadonly, seasonStr));
        } catch (IOException e) {
            log.info("Read-only host failed for season {}, trying primary host...", seasonStr);
            return fetchJsonWithRetry(urlBuilder.build(hostPrimary, seasonStr));
        }
    }

    private JsonObject fetchJsonWithRetry(String url) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_HOST; attempt++) {
            try {
                return fetchJson(url);
            } catch (TransientEspnException e) {
                lastException = e;
                if (attempt < MAX_ATTEMPTS_PER_HOST) {
                    log.info("Transient ESPN API failure (attempt {}/{}): {} — retrying...",
                            attempt, MAX_ATTEMPTS_PER_HOST, e.getMessage());
                    sleep(RETRY_BACKOFF_MILLIS * attempt);
                }
            }
        }

        throw lastException;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonObject fetchJson(String url) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0");

        if (espnS2 != null && !espnS2.isBlank() && swid != null && !swid.isBlank()) {
            requestBuilder.addHeader("Cookie", "espn_s2=" + espnS2 + "; SWID=" + swid);
        }

        Request request = requestBuilder.build();

        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (IOException e) {
            // Network-level failure (timeout, connection reset, DNS blip) — worth retrying.
            throw new TransientEspnException("Network error calling ESPN API: " + e.getMessage(), e);
        }

        try (response) {
            log.debug("ESPN API response code: {}", response.code());

            if (response.code() == 302 || response.code() == 301) {
                throw new IOException("Got redirect (302/301). Your league is likely private. Please set ESPN_S2 and SWID environment variables.");
            }

            if (response.code() >= 500) {
                // Server-side failure — likely transient, worth retrying.
                throw new TransientEspnException("HTTP " + response.code() + ": " + response.message());
            }

            if (!response.isSuccessful()) {
                // 4xx — a config problem (bad league ID, etc.), not something a retry fixes.
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
