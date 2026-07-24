package com.fantasy.bot.matchup;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sums each starting-lineup player's actual points across every day in a
 * team's matchup week (from per-day roster snapshots) to find the top
 * scorer. NBA fantasy locks lineups daily, not once for the whole week, so a
 * "top scorer for the week" has to be reconstructed day by day rather than
 * read off a single field.
 */
public class BoxScoreCalculator {

    public record TopScorer(String playerName, double points) {
    }

    /**
     * @param espnTeamId       the team to compute for
     * @param scoringPeriodIds the days that count toward this matchup week (a team's pointsByScoringPeriod.keySet())
     * @param rosterByDay      day -> full league data fetched with mRoster for that day (ESPNApiClient.getRosterForDay)
     * @param startingSlotIds  slot IDs that count as "starting" (LineupHealthChecker.startingSlotIds)
     */
    public static Optional<TopScorer> computeTopScorer(
            int espnTeamId,
            Set<Integer> scoringPeriodIds,
            Map<Integer, JsonObject> rosterByDay,
            Set<Integer> startingSlotIds
    ) {
        Map<String, Double> totalsByPlayer = new HashMap<>();

        for (int day : scoringPeriodIds) {
            JsonObject dayData = rosterByDay.get(day);
            if (dayData == null) continue;

            JsonObject team = findTeam(dayData, espnTeamId);
            if (team == null || !team.has("roster")) continue;

            JsonObject roster = team.getAsJsonObject("roster");
            if (!roster.has("entries")) continue;

            JsonArray entries = roster.getAsJsonArray("entries");
            for (int i = 0; i < entries.size(); i++) {
                JsonObject entry = entries.get(i).getAsJsonObject();
                if (!entry.has("lineupSlotId")) continue;

                int slotId = entry.get("lineupSlotId").getAsInt();
                if (!startingSlotIds.contains(slotId)) continue;

                JsonObject ppe = entry.has("playerPoolEntry") ? entry.getAsJsonObject("playerPoolEntry") : null;
                if (ppe == null || !ppe.has("appliedStatTotal") || ppe.get("appliedStatTotal").isJsonNull()) continue;
                if (!ppe.has("player")) continue;

                double points = ppe.get("appliedStatTotal").getAsDouble();
                String playerName = ppe.getAsJsonObject("player").has("fullName")
                        ? ppe.getAsJsonObject("player").get("fullName").getAsString() : "Unknown Player";

                totalsByPlayer.merge(playerName, points, Double::sum);
            }
        }

        return totalsByPlayer.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> new TopScorer(e.getKey(), e.getValue()));
    }

    private static JsonObject findTeam(JsonObject leagueData, int espnTeamId) {
        if (!leagueData.has("teams")) return null;
        JsonArray teams = leagueData.getAsJsonArray("teams");
        for (int i = 0; i < teams.size(); i++) {
            JsonObject team = teams.get(i).getAsJsonObject();
            if (team.has("id") && team.get("id").getAsInt() == espnTeamId) {
                return team;
            }
        }
        return null;
    }
}
