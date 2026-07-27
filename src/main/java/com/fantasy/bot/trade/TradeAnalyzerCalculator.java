package com.fantasy.bot.trade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure logic (no network/Discord calls) for comparing the rest-of-season
 * point value of both sides of a proposed trade, adjusted for each team's
 * own positional surplus/deficiency (e.g. giving up a position you're
 * stacked at hurts less than its raw production suggests; getting a
 * position you're thin at is worth more than its raw production suggests).
 *
 * Value per player is season-to-date points-per-game times real games
 * remaining on their NBA team's schedule — both already present in data
 * this bot fetches for other commands, no extra API calls needed.
 */
public class TradeAnalyzerCalculator {

    /** How much a position adjustment moves a player's value, in either direction. */
    private static final double POSITION_ADJUSTMENT = 0.10;

    /** A team's rostered count at a position must differ from the league average by at least this much to count as surplus/deficient. */
    private static final double FIT_TOLERANCE = 1.0;

    public static final Map<Integer, String> POSITION_NAMES = Map.of(
            1, "PG", 2, "SG", 3, "SF", 4, "PF", 5, "C");

    public enum Fit { SURPLUS, NEUTRAL, DEFICIENT }

    public record PlayerValue(String name, int playerId, int position, double ppg, int gamesRemaining,
                               double rawPoints, String injuryStatus) {
        public String positionName() {
            return POSITION_NAMES.getOrDefault(position, "?");
        }
    }

    public record FitNote(String playerName, String positionName, boolean receiving, Fit fit) {
    }

    public record TradeResult(
            List<PlayerValue> team1Gives,
            List<PlayerValue> team2Gives,
            double team1RawGiven,
            double team1RawReceived,
            double team1AdjustedGiven,
            double team1AdjustedReceived,
            List<FitNote> fitNotes
    ) {
        public double rawGap() {
            return team1RawReceived - team1RawGiven;
        }

        public double adjustedGap() {
            return team1AdjustedReceived - team1AdjustedGiven;
        }
    }

    private record PositionProfile(Map<Integer, Integer> rosteredCountByPosition, Map<Integer, Double> leagueAverageByPosition) {
        Fit fitFor(int position) {
            int count = rosteredCountByPosition.getOrDefault(position, 0);
            double avg = leagueAverageByPosition.getOrDefault(position, 0.0);
            if (count <= avg - FIT_TOLERANCE) return Fit.DEFICIENT;
            if (count >= avg + FIT_TOLERANCE) return Fit.SURPLUS;
            return Fit.NEUTRAL;
        }
    }

    /**
     * @param leagueData      result of ESPNApiClient.getLeagueData() (must include mRoster view)
     * @param scheduleData    result of ESPNApiClient.getProTeamSchedules()
     * @param team1Id         the team whose perspective the trade is evaluated from
     * @param team1PlayerIds  players team1 gives away
     * @param team2Id         the other team
     * @param team2PlayerIds  players team2 gives away (i.e. what team1 receives)
     * @param nowEpochMillis  injected "now" for testability; games before this don't count as "remaining"
     */
    public static TradeResult analyze(
            JsonObject leagueData,
            JsonObject scheduleData,
            int team1Id,
            List<Integer> team1PlayerIds,
            int team2Id,
            List<Integer> team2PlayerIds,
            long nowEpochMillis
    ) {
        Map<Integer, Integer> gamesRemainingByProTeam = computeGamesRemainingByProTeam(scheduleData, nowEpochMillis);
        Map<Integer, PositionProfile> profilesByTeam = buildPositionProfiles(leagueData);

        List<PlayerValue> team1Gives = extractPlayers(leagueData, team1Id, team1PlayerIds, gamesRemainingByProTeam);
        List<PlayerValue> team2Gives = extractPlayers(leagueData, team2Id, team2PlayerIds, gamesRemainingByProTeam);

        double team1RawGiven = team1Gives.stream().mapToDouble(PlayerValue::rawPoints).sum();
        double team1RawReceived = team2Gives.stream().mapToDouble(PlayerValue::rawPoints).sum();

        PositionProfile team1Profile = profilesByTeam.getOrDefault(team1Id,
                new PositionProfile(Map.of(), Map.of()));

        List<FitNote> fitNotes = new ArrayList<>();

        double team1AdjustedGiven = 0;
        for (PlayerValue p : team1Gives) {
            Fit fit = team1Profile.fitFor(p.position());
            team1AdjustedGiven += p.rawPoints() * multiplierFor(fit);
            if (fit != Fit.NEUTRAL) {
                fitNotes.add(new FitNote(p.name(), p.positionName(), false, fit));
            }
        }

        double team1AdjustedReceived = 0;
        for (PlayerValue p : team2Gives) {
            Fit fit = team1Profile.fitFor(p.position());
            team1AdjustedReceived += p.rawPoints() * multiplierFor(fit);
            if (fit != Fit.NEUTRAL) {
                fitNotes.add(new FitNote(p.name(), p.positionName(), true, fit));
            }
        }

        return new TradeResult(team1Gives, team2Gives, team1RawGiven, team1RawReceived,
                team1AdjustedGiven, team1AdjustedReceived, fitNotes);
    }

    private static double multiplierFor(Fit fit) {
        return switch (fit) {
            case DEFICIENT -> 1 + POSITION_ADJUSTMENT;
            case SURPLUS -> 1 - POSITION_ADJUSTMENT;
            case NEUTRAL -> 1.0;
        };
    }

    private static List<PlayerValue> extractPlayers(JsonObject leagueData, int teamId, List<Integer> playerIds,
                                                      Map<Integer, Integer> gamesRemainingByProTeam) {
        List<PlayerValue> result = new ArrayList<>();
        JsonArray teams = leagueData.getAsJsonArray("teams");
        if (teams == null) return result;

        for (int i = 0; i < teams.size(); i++) {
            JsonObject team = teams.get(i).getAsJsonObject();
            if (!team.has("id") || team.get("id").getAsInt() != teamId) continue;
            if (!team.has("roster")) break;

            JsonArray entries = team.getAsJsonObject("roster").getAsJsonArray("entries");
            if (entries == null) break;

            for (int j = 0; j < entries.size(); j++) {
                JsonObject entry = entries.get(j).getAsJsonObject();
                if (!entry.has("playerId")) continue;
                int playerId = entry.get("playerId").getAsInt();
                if (!playerIds.contains(playerId)) continue;

                JsonObject player = entry.getAsJsonObject("playerPoolEntry").getAsJsonObject("player");
                String name = player.has("fullName") ? player.get("fullName").getAsString() : "Unknown Player";
                int position = player.has("defaultPositionId") ? player.get("defaultPositionId").getAsInt() : 0;
                int proTeamId = player.has("proTeamId") ? player.get("proTeamId").getAsInt() : 0;
                String injuryStatus = player.has("injuryStatus") && !player.get("injuryStatus").isJsonNull()
                        ? player.get("injuryStatus").getAsString() : "NORMAL";

                double ppg = seasonActualPpg(player);
                int gamesRemaining = gamesRemainingByProTeam.getOrDefault(proTeamId, 0);

                result.add(new PlayerValue(name, playerId, position, ppg, gamesRemaining, ppg * gamesRemaining, injuryStatus));
            }
            break;
        }
        return result;
    }

    /** Season-to-date actual points-per-game (statSourceId=0 "actual", statSplitTypeId=0 "full season"). */
    private static double seasonActualPpg(JsonObject player) {
        if (!player.has("stats")) return 0.0;
        JsonArray stats = player.getAsJsonArray("stats");
        for (int i = 0; i < stats.size(); i++) {
            JsonObject stat = stats.get(i).getAsJsonObject();
            int sourceId = stat.has("statSourceId") ? stat.get("statSourceId").getAsInt() : -1;
            int splitId = stat.has("statSplitTypeId") ? stat.get("statSplitTypeId").getAsInt() : -1;
            if (sourceId == 0 && splitId == 0 && stat.has("appliedAverage")) {
                return stat.get("appliedAverage").getAsDouble();
            }
        }
        return 0.0;
    }

    /** How many of a real NBA team's games (by proTeamId) start after nowEpochMillis. */
    private static Map<Integer, Integer> computeGamesRemainingByProTeam(JsonObject scheduleData, long nowEpochMillis) {
        Map<Integer, Integer> result = new HashMap<>();
        if (!scheduleData.has("settings")) return result;

        JsonObject settings = scheduleData.getAsJsonObject("settings");
        if (!settings.has("proTeams")) return result;

        JsonArray proTeams = settings.getAsJsonArray("proTeams");
        for (int i = 0; i < proTeams.size(); i++) {
            JsonObject proTeam = proTeams.get(i).getAsJsonObject();
            if (!proTeam.has("id") || !proTeam.has("proGamesByScoringPeriod")) continue;

            int proTeamId = proTeam.get("id").getAsInt();
            JsonObject byPeriod = proTeam.getAsJsonObject("proGamesByScoringPeriod");

            int count = 0;
            for (String periodKey : byPeriod.keySet()) {
                JsonArray games = byPeriod.getAsJsonArray(periodKey);
                for (int g = 0; g < games.size(); g++) {
                    JsonObject game = games.get(g).getAsJsonObject();
                    if (!game.has("date")) continue;
                    if (game.get("date").getAsLong() > nowEpochMillis) {
                        count++;
                        break; // one remaining game that day is enough to count the day
                    }
                }
            }
            result.put(proTeamId, count);
        }
        return result;
    }

    /** Each team's rostered-player count per position, plus the league average at each position. */
    private static Map<Integer, PositionProfile> buildPositionProfiles(JsonObject leagueData) {
        JsonArray teams = leagueData.getAsJsonArray("teams");
        Map<Integer, Map<Integer, Integer>> countsByTeam = new HashMap<>();
        if (teams == null) return Map.of();

        for (int i = 0; i < teams.size(); i++) {
            JsonObject team = teams.get(i).getAsJsonObject();
            if (!team.has("id") || !team.has("roster")) continue;
            int teamId = team.get("id").getAsInt();

            Map<Integer, Integer> counts = new HashMap<>();
            JsonArray entries = team.getAsJsonObject("roster").getAsJsonArray("entries");
            if (entries != null) {
                for (int j = 0; j < entries.size(); j++) {
                    JsonObject entry = entries.get(j).getAsJsonObject();
                    JsonObject player = entry.getAsJsonObject("playerPoolEntry").getAsJsonObject("player");
                    int position = player.has("defaultPositionId") ? player.get("defaultPositionId").getAsInt() : 0;
                    counts.merge(position, 1, Integer::sum);
                }
            }
            countsByTeam.put(teamId, counts);
        }

        if (countsByTeam.isEmpty()) return Map.of();

        Map<Integer, Double> leagueAverage = new HashMap<>();
        for (int position : POSITION_NAMES.keySet()) {
            double total = 0;
            for (Map<Integer, Integer> counts : countsByTeam.values()) {
                total += counts.getOrDefault(position, 0);
            }
            leagueAverage.put(position, total / countsByTeam.size());
        }

        Map<Integer, PositionProfile> result = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : countsByTeam.entrySet()) {
            result.put(entry.getKey(), new PositionProfile(entry.getValue(), leagueAverage));
        }
        return result;
    }
}
