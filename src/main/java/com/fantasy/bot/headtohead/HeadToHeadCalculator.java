package com.fantasy.bot.headtohead;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * All-time series record between two specific teams, scanning every
 * historical season's schedule for matchups between them. Pure logic —
 * takes already-fetched season payloads, no network calls.
 */
public class HeadToHeadCalculator {

    public record GameResult(int season, int week, double teamAPoints, double teamBPoints) {
        public double margin() {
            return Math.abs(teamAPoints - teamBPoints);
        }
    }

    public record HeadToHeadRecord(
            int teamAWins, int teamBWins, int ties,
            double teamATotalPoints, double teamBTotalPoints,
            List<GameResult> games
    ) {
        public int gamesPlayed() {
            return teamAWins + teamBWins + ties;
        }

        public Optional<GameResult> biggestBlowout() {
            return games.stream().max(Comparator.comparingDouble(GameResult::margin));
        }

        public Optional<GameResult> mostRecent() {
            return games.stream().max(Comparator.comparingInt(GameResult::season).thenComparingInt(GameResult::week));
        }
    }

    /** @param seasonDataList one already-fetched league payload (ESPNApiClient.getLeagueData(season)) per season to scan */
    public static HeadToHeadRecord compute(List<JsonObject> seasonDataList, int teamAId, int teamBId) {
        int aWins = 0, bWins = 0, ties = 0;
        double aPoints = 0, bPoints = 0;
        List<GameResult> games = new ArrayList<>();

        for (JsonObject seasonData : seasonDataList) {
            if (seasonData == null || !seasonData.has("schedule")) continue;

            int season = seasonData.has("seasonId") && !seasonData.get("seasonId").isJsonNull()
                    ? seasonData.get("seasonId").getAsInt() : 0;

            JsonArray schedule = seasonData.getAsJsonArray("schedule");
            for (int i = 0; i < schedule.size(); i++) {
                JsonObject m = schedule.get(i).getAsJsonObject();
                if (m == null || !m.has("matchupPeriodId") || !m.has("home") || !m.has("away")) continue;

                JsonObject home = m.getAsJsonObject("home");
                JsonObject away = m.getAsJsonObject("away");
                if (!home.has("teamId") || !away.has("teamId")) continue;

                int homeId = home.get("teamId").getAsInt();
                int awayId = away.get("teamId").getAsInt();

                boolean isThisPair = (homeId == teamAId && awayId == teamBId) || (homeId == teamBId && awayId == teamAId);
                if (!isThisPair) continue;

                double homePts = safePoints(home);
                double awayPts = safePoints(away);
                if (homePts <= 0 && awayPts <= 0) continue; // not played yet

                double teamAScore = homeId == teamAId ? homePts : awayPts;
                double teamBScore = homeId == teamBId ? homePts : awayPts;

                int week = m.get("matchupPeriodId").getAsInt();
                games.add(new GameResult(season, week, teamAScore, teamBScore));

                aPoints += teamAScore;
                bPoints += teamBScore;
                if (teamAScore > teamBScore) aWins++;
                else if (teamBScore > teamAScore) bWins++;
                else ties++;
            }
        }

        return new HeadToHeadRecord(aWins, bWins, ties, aPoints, bPoints, games);
    }

    private static double safePoints(JsonObject side) {
        if (side == null || !side.has("totalPoints") || side.get("totalPoints").isJsonNull()) return 0.0;
        try {
            return side.get("totalPoints").getAsDouble();
        } catch (Exception e) {
            return 0.0;
        }
    }
}
