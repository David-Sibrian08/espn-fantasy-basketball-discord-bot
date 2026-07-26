package com.fantasy.bot.powerrankings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "All-play" power rankings: for every played week, each team's score is
 * compared against every OTHER team's score that week (not just their
 * actual scheduled opponent), and the resulting win/loss/tie is tallied
 * across the whole season. This removes schedule luck from the ranking —
 * a team that scores well every week ranks high regardless of who they
 * actually happened to play.
 */
public class PowerRankingsCalculator {

    public record TeamPowerRank(
            int espnTeamId, String teamName,
            int allPlayWins, int allPlayLosses, int allPlayTies,
            double totalPoints,
            int actualWins, int actualLosses, int actualTies
    ) {
        public double allPlayPercentage() {
            int total = allPlayWins + allPlayLosses + allPlayTies;
            return total == 0 ? 0.0 : (allPlayWins + 0.5 * allPlayTies) / total;
        }
    }

    private record WeekScore(int teamId, double points) {
    }

    public static List<TeamPowerRank> compute(JsonObject leagueData, int throughWeek) {
        Map<Integer, String> teamNames = new HashMap<>();
        Map<Integer, int[]> actualRecords = new HashMap<>(); // teamId -> [wins, losses, ties]
        JsonArray teamsArray = leagueData.getAsJsonArray("teams");
        for (int i = 0; i < teamsArray.size(); i++) {
            JsonObject team = teamsArray.get(i).getAsJsonObject();
            int id = team.get("id").getAsInt();
            teamNames.put(id, displayName(team));

            JsonObject overall = team.getAsJsonObject("record").getAsJsonObject("overall");
            actualRecords.put(id, new int[]{
                    overall.get("wins").getAsInt(),
                    overall.get("losses").getAsInt(),
                    overall.has("ties") ? overall.get("ties").getAsInt() : 0
            });
        }

        Map<Integer, Integer> allPlayWins = new HashMap<>();
        Map<Integer, Integer> allPlayLosses = new HashMap<>();
        Map<Integer, Integer> allPlayTies = new HashMap<>();
        Map<Integer, Double> totalPoints = new HashMap<>();

        JsonArray schedule = leagueData.getAsJsonArray("schedule");
        if (schedule != null) {
            for (int week = 1; week <= throughWeek; week++) {
                List<WeekScore> weekScores = collectWeekScores(schedule, week);
                if (weekScores.isEmpty()) continue;

                for (int i = 0; i < weekScores.size(); i++) {
                    WeekScore mine = weekScores.get(i);
                    int wins = 0, losses = 0, ties = 0;

                    for (int j = 0; j < weekScores.size(); j++) {
                        if (i == j) continue;
                        double otherPoints = weekScores.get(j).points();
                        if (mine.points() > otherPoints) wins++;
                        else if (mine.points() < otherPoints) losses++;
                        else ties++;
                    }

                    allPlayWins.merge(mine.teamId(), wins, Integer::sum);
                    allPlayLosses.merge(mine.teamId(), losses, Integer::sum);
                    allPlayTies.merge(mine.teamId(), ties, Integer::sum);
                    totalPoints.merge(mine.teamId(), mine.points(), Double::sum);
                }
            }
        }

        List<TeamPowerRank> ranks = new ArrayList<>();
        for (int teamId : teamNames.keySet()) {
            int[] actual = actualRecords.getOrDefault(teamId, new int[]{0, 0, 0});
            ranks.add(new TeamPowerRank(
                    teamId, teamNames.get(teamId),
                    allPlayWins.getOrDefault(teamId, 0),
                    allPlayLosses.getOrDefault(teamId, 0),
                    allPlayTies.getOrDefault(teamId, 0),
                    totalPoints.getOrDefault(teamId, 0.0),
                    actual[0], actual[1], actual[2]
            ));
        }

        ranks.sort(Comparator
                .comparingDouble(TeamPowerRank::allPlayPercentage).reversed()
                .thenComparing(Comparator.comparingDouble(TeamPowerRank::totalPoints).reversed()));

        return ranks;
    }

    private static List<WeekScore> collectWeekScores(JsonArray schedule, int week) {
        List<WeekScore> scores = new ArrayList<>();
        for (int i = 0; i < schedule.size(); i++) {
            JsonObject m = schedule.get(i).getAsJsonObject();
            if (m == null || !m.has("matchupPeriodId")) continue;
            if (m.get("matchupPeriodId").getAsInt() != week) continue;
            if (!m.has("home") || !m.has("away")) continue;

            JsonObject home = m.getAsJsonObject("home");
            JsonObject away = m.getAsJsonObject("away");
            if (!looksPlayed(home, away)) continue;

            scores.add(new WeekScore(home.get("teamId").getAsInt(), safePoints(home)));
            scores.add(new WeekScore(away.get("teamId").getAsInt(), safePoints(away)));
        }
        return scores;
    }

    private static boolean looksPlayed(JsonObject home, JsonObject away) {
        return safePoints(home) > 0 || safePoints(away) > 0;
    }

    private static double safePoints(JsonObject side) {
        if (side == null || !side.has("totalPoints") || side.get("totalPoints").isJsonNull()) return 0.0;
        try {
            return side.get("totalPoints").getAsDouble();
        } catch (Exception e) {
            return 0.0;
        }
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

    private static String joinNonBlank(String a, String b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        String s = (a + " " + b).trim();
        return s.isEmpty() ? null : s;
    }

    private static String displayName(JsonObject team) {
        String name = getString(team, "name");
        if (name != null) return name;

        String loc = getString(team, "location");
        String nick = getString(team, "nickname");
        String locNick = joinNonBlank(loc, nick);
        if (locNick != null) return locNick;

        String abbrev = getString(team, "abbrev");
        if (abbrev != null) return abbrev;

        int id = team.has("id") && !team.get("id").isJsonNull() ? team.get("id").getAsInt() : -1;
        return id > 0 ? ("Team " + id) : "Team";
    }
}
