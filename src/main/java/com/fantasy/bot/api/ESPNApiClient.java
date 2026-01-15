package com.fantasy.bot.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;

import java.io.IOException;

public class ESPNApiClient {
    // Prefer read-only host to avoid redirects
    private static final String HOST_READONLY = "https://lm-api-reads.fantasy.espn.com";
    private static final String HOST_PRIMARY = "https://fantasy.espn.com";

    private final OkHttpClient client;
    private final String leagueId;
    private final String seasonId;
    private final String espnS2;
    private final String swid;

    public ESPNApiClient() {
        Dotenv dotenv = Dotenv.load();
        this.client = new OkHttpClient.Builder()
                .followRedirects(true)  // Follow redirects but we'll validate the response
                .build();
        this.leagueId = dotenv.get("ESPN_LEAGUE_ID");
        this.seasonId = dotenv.get("ESPN_SEASON_ID") != null ?
                dotenv.get("ESPN_SEASON_ID") : "2025";
        this.espnS2 = dotenv.get("ESPN_S2");
        this.swid = dotenv.get("SWID");

        // Validate required environment variables
        if (leagueId == null || leagueId.isEmpty()) {
            throw new IllegalStateException("ESPN_LEAGUE_ID environment variable is required");
        }
    }

    // overload
    private String buildLeagueUrl(String host) {
        return host + "/apis/v3/games/fba/seasons/" + seasonId +
                "/segments/0/leagues/" + leagueId +
                "?view=mMatchup&view=mStandings&view=mTeam&view=mSettings";
    }

    private String buildLeagueUrl(String host, String seasonId) {
        return host + "/apis/v3/games/fba/seasons/" + seasonId +
                "/segments/0/leagues/" + leagueId +
                "?view=mMatchup&view=mStandings&view=mTeam&view=mSettings";
    }

    public JsonObject getLeagueData() throws IOException {
        return getLeagueData(Integer.parseInt(seasonId));
    }

    //overload
    public JsonObject getLeagueData(int seasonId) throws IOException {
        String seasonStr = String.valueOf(seasonId);

        try {
            return fetchLeagueData(HOST_READONLY, seasonStr);
        } catch (IOException e) {
            System.out.println("Read-only host failed for season " + seasonStr + ", trying primary host...");
            return fetchLeagueData(HOST_PRIMARY, seasonStr);
        }
    }

    private JsonObject fetchLeagueData(String host) throws IOException {
        String url = buildLeagueUrl(host);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0");

        // Add cookies for private leagues
        if (espnS2 != null && !espnS2.isEmpty() && swid != null && !swid.isEmpty()) {
            String cookieHeader = "espn_s2=" + espnS2 + "; SWID=" + swid;
            requestBuilder.addHeader("Cookie", cookieHeader);
        } else {
            System.out.println("WARNING: No ESPN_S2 or SWID found. Private leagues will fail!");
        }

        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Response code: " + response.code());

            if (response.code() == 302 || response.code() == 301) {
                throw new IOException("Got redirect (302/301). Your league is likely private. Please set ESPN_S2 and SWID environment variables.");
            }

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }

            String responseBody = response.body().string();

            // Check if response is actually JSON
            if (responseBody.trim().startsWith("<")) {
                throw new IOException("Received HTML instead of JSON. Your league might be private - set ESPN_S2 and SWID cookies.");
            }

            return JsonParser.parseString(responseBody).getAsJsonObject();
        }
    }

    //overload
    private JsonObject fetchLeagueData(String host, String seasonId) throws IOException {
        String url = buildLeagueUrl(host, seasonId);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0");

        if (espnS2 != null && !espnS2.isEmpty() && swid != null && !swid.isEmpty()) {
            String cookieHeader = "espn_s2=" + espnS2 + "; SWID=" + swid;
            requestBuilder.addHeader("Cookie", cookieHeader);
        } else {
            System.out.println("WARNING: No ESPN_S2 or SWID found. Private leagues will fail!");
        }

        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Response code: " + response.code());

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
