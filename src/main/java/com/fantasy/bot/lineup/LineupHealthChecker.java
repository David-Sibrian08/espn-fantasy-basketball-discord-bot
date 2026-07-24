package com.fantasy.bot.lineup;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure logic (no network/Discord calls) for finding starting-lineup players
 * who are injured and about to have their game lock. Kept side-effect free
 * so it can be tested without waiting for a real game to happen.
 *
 * ESPN fixes these lineup slot IDs for basketball across leagues (they're
 * not league-configurable, only the slot *counts* are):
 */
public class LineupHealthChecker {
    private static final int BENCH_SLOT_ID = 12;
    private static final int IR_SLOT_ID = 13;

    public static final Set<String> DEFAULT_FLAGGED_STATUSES = Set.of("OUT", "DOUBTFUL");

    public record LineupAlert(int espnTeamId, String teamName, String playerName, String injuryStatus,
                               long gameStartEpochMillis, String dedupeKey) {
    }

    /**
     * @param leagueData         result of ESPNApiClient.getLeagueData() (must include mRoster + mSettings views)
     * @param scheduleData       result of ESPNApiClient.getProTeamSchedules()
     * @param scoringPeriodId    the day to check (league's current scoringPeriodId for "today")
     * @param nowEpochMillis     injected "now" for testability
     * @param windowMillisBefore only alert if the game starts within this many ms from now (and hasn't started yet)
     * @param flaggedStatuses    injuryStatus values that should trigger an alert
     * @param alreadyAlerted     dedupe keys already alerted; alerts matching these are skipped
     */
    public static List<LineupAlert> computeAlerts(
            JsonObject leagueData,
            JsonObject scheduleData,
            int scoringPeriodId,
            long nowEpochMillis,
            long windowMillisBefore,
            Set<String> flaggedStatuses,
            Set<String> alreadyAlerted
    ) {
        List<LineupAlert> alerts = new ArrayList<>();

        Set<Integer> startingSlotIds = startingSlotIds(leagueData);
        Map<Integer, Long> proTeamGameStart = proTeamGameStartTimes(scheduleData, scoringPeriodId);

        JsonArray teams = leagueData.getAsJsonArray("teams");
        if (teams == null) return alerts;

        for (int i = 0; i < teams.size(); i++) {
            JsonObject team = teams.get(i).getAsJsonObject();
            if (!team.has("id") || !team.has("roster")) continue;
            int espnTeamId = team.get("id").getAsInt();
            String teamName = displayName(team);

            JsonObject roster = team.getAsJsonObject("roster");
            JsonArray entries = roster.has("entries") ? roster.getAsJsonArray("entries") : null;
            if (entries == null) continue;

            for (int j = 0; j < entries.size(); j++) {
                JsonObject entry = entries.get(j).getAsJsonObject();
                if (!entry.has("lineupSlotId")) continue;

                int slotId = entry.get("lineupSlotId").getAsInt();
                if (!startingSlotIds.contains(slotId)) continue;

                JsonObject player = entry.getAsJsonObject("playerPoolEntry").getAsJsonObject("player");
                String status = player.has("injuryStatus") && !player.get("injuryStatus").isJsonNull()
                        ? player.get("injuryStatus").getAsString() : null;
                if (status == null || !flaggedStatuses.contains(status)) continue;

                int proTeamId = player.has("proTeamId") ? player.get("proTeamId").getAsInt() : 0;
                Long gameStart = proTeamGameStart.get(proTeamId);
                if (gameStart == null) continue; // no game today for this player's pro team

                if (nowEpochMillis >= gameStart) continue; // already locked/started, too late
                if (gameStart - nowEpochMillis > windowMillisBefore) continue; // not within the alert window yet

                String playerName = player.has("fullName") ? player.get("fullName").getAsString() : "Unknown Player";
                int playerId = entry.has("playerId") ? entry.get("playerId").getAsInt() : player.get("id").getAsInt();

                String key = playerId + ":" + gameStart;
                if (alreadyAlerted.contains(key)) continue;

                alerts.add(new LineupAlert(espnTeamId, teamName, playerName, status, gameStart, key));
            }
        }

        return alerts;
    }

    private static String displayName(JsonObject team) {
        String name = getString(team, "name");
        if (name != null) return name;

        String loc = getString(team, "location");
        String nick = getString(team, "nickname");
        if (loc != null || nick != null) {
            return ((loc != null ? loc : "") + " " + (nick != null ? nick : "")).trim();
        }

        String abbrev = getString(team, "abbrev");
        if (abbrev != null) return abbrev;

        int id = team.has("id") && !team.get("id").isJsonNull() ? team.get("id").getAsInt() : -1;
        return id > 0 ? ("Team " + id) : "Team";
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String s = obj.get(key).getAsString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** Slot IDs that count as "starting" (not bench=12 or IR=13), derived from the league's actual roster settings. */
    public static Set<Integer> startingSlotIds(JsonObject leagueData) {
        Set<Integer> slots = new HashSet<>();
        if (!leagueData.has("settings")) return slots;

        JsonObject settings = leagueData.getAsJsonObject("settings");
        if (!settings.has("rosterSettings")) return slots;

        JsonObject rosterSettings = settings.getAsJsonObject("rosterSettings");
        if (!rosterSettings.has("lineupSlotCounts")) return slots;

        JsonObject slotCounts = rosterSettings.getAsJsonObject("lineupSlotCounts");
        for (String key : slotCounts.keySet()) {
            int slotId = Integer.parseInt(key);
            if (slotId == BENCH_SLOT_ID || slotId == IR_SLOT_ID) continue;
            if (slotCounts.get(key).getAsInt() > 0) {
                slots.add(slotId);
            }
        }
        return slots;
    }

    private static Map<Integer, Long> proTeamGameStartTimes(JsonObject scheduleData, int scoringPeriodId) {
        Map<Integer, Long> result = new HashMap<>();
        if (!scheduleData.has("settings")) return result;

        JsonObject settings = scheduleData.getAsJsonObject("settings");
        if (!settings.has("proTeams")) return result;

        JsonArray proTeams = settings.getAsJsonArray("proTeams");
        String periodKey = String.valueOf(scoringPeriodId);

        for (int i = 0; i < proTeams.size(); i++) {
            JsonObject proTeam = proTeams.get(i).getAsJsonObject();
            if (!proTeam.has("id") || !proTeam.has("proGamesByScoringPeriod")) continue;

            int proTeamId = proTeam.get("id").getAsInt();
            JsonObject byPeriod = proTeam.getAsJsonObject("proGamesByScoringPeriod");
            if (!byPeriod.has(periodKey)) continue;

            JsonArray games = byPeriod.getAsJsonArray(periodKey);
            if (games.isEmpty()) continue;

            // Earliest game that day (practically always just one for NBA).
            long earliest = Long.MAX_VALUE;
            for (int g = 0; g < games.size(); g++) {
                JsonObject game = games.get(g).getAsJsonObject();
                if (!game.has("date")) continue;
                earliest = Math.min(earliest, game.get("date").getAsLong());
            }
            if (earliest != Long.MAX_VALUE) {
                result.put(proTeamId, earliest);
            }
        }
        return result;
    }
}
