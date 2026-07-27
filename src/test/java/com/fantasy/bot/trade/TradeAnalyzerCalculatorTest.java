package com.fantasy.bot.trade;

import com.fantasy.bot.trade.TradeAnalyzerCalculator.Fit;
import com.fantasy.bot.trade.TradeAnalyzerCalculator.FitNote;
import com.fantasy.bot.trade.TradeAnalyzerCalculator.TradeResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TradeAnalyzerCalculatorTest {

    private static final int PG = 1, SG = 2, SF = 3, PF = 4, C = 5;
    private static final long NOW = 1_800_000_000_000L; // arbitrary fixed instant
    private static final long DAY = 86_400_000L;

    private JsonObject player(int playerId, String name, int position, int proTeamId, double ppg, String injuryStatus) {
        JsonObject statEntry = new JsonObject();
        statEntry.addProperty("statSourceId", 0);
        statEntry.addProperty("statSplitTypeId", 0);
        statEntry.addProperty("appliedAverage", ppg);

        JsonArray stats = new JsonArray();
        stats.add(statEntry);

        JsonObject playerObj = new JsonObject();
        playerObj.addProperty("fullName", name);
        playerObj.addProperty("defaultPositionId", position);
        playerObj.addProperty("proTeamId", proTeamId);
        playerObj.addProperty("injuryStatus", injuryStatus);
        playerObj.add("stats", stats);

        JsonObject ppe = new JsonObject();
        ppe.add("player", playerObj);

        JsonObject entry = new JsonObject();
        entry.addProperty("playerId", playerId);
        entry.add("playerPoolEntry", ppe);
        return entry;
    }

    private JsonObject team(int teamId, JsonObject... entries) {
        JsonArray entryArray = new JsonArray();
        for (JsonObject e : entries) entryArray.add(e);

        JsonObject roster = new JsonObject();
        roster.add("entries", entryArray);

        JsonObject team = new JsonObject();
        team.addProperty("id", teamId);
        team.add("roster", roster);
        return team;
    }

    private JsonObject league(JsonObject... teams) {
        JsonArray teamsArray = new JsonArray();
        for (JsonObject t : teams) teamsArray.add(t);

        JsonObject league = new JsonObject();
        league.add("teams", teamsArray);
        return league;
    }

    /** One real NBA team (proTeamId) with `remaining` games after NOW and `past` games before NOW. */
    private JsonObject scheduleFor(Map<Integer, Integer> remainingGamesByProTeam) {
        JsonArray proTeams = new JsonArray();
        for (Map.Entry<Integer, Integer> e : remainingGamesByProTeam.entrySet()) {
            JsonObject byPeriod = new JsonObject();
            int period = 1;
            for (int i = 0; i < e.getValue(); i++) {
                JsonObject game = new JsonObject();
                game.addProperty("date", NOW + (i + 1) * DAY);
                JsonArray games = new JsonArray();
                games.add(game);
                byPeriod.add(String.valueOf(period++), games);
            }
            JsonObject proTeam = new JsonObject();
            proTeam.addProperty("id", e.getKey());
            proTeam.add("proGamesByScoringPeriod", byPeriod);
            proTeams.add(proTeam);
        }
        JsonObject settings = new JsonObject();
        settings.add("proTeams", proTeams);
        JsonObject schedule = new JsonObject();
        schedule.add("settings", settings);
        return schedule;
    }

    @Test
    void rawValueIsPpgTimesGamesRemaining() {
        JsonObject team1 = team(1, player(101, "Give Guy", SG, 100, 20.0, "ACTIVE"));
        JsonObject team2 = team(2, player(201, "Get Guy", SF, 200, 30.0, "ACTIVE"));
        JsonObject leagueData = league(team1, team2);
        JsonObject scheduleData = scheduleFor(Map.of(100, 10, 200, 5));

        TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                1, List.of(101), 2, List.of(201), NOW);

        assertEquals(200.0, result.team1RawGiven(), 0.001); // 20 ppg * 10 games
        assertEquals(150.0, result.team1RawReceived(), 0.001); // 30 ppg * 5 games
        assertEquals(-50.0, result.rawGap(), 0.001);
    }

    @Test
    void gamesBeforeNowDoNotCountAsRemaining() {
        JsonObject team1 = team(1, player(101, "Give Guy", SG, 100, 20.0, "ACTIVE"));
        JsonObject team2 = team(2, player(201, "Get Guy", SF, 200, 20.0, "ACTIVE"));
        JsonObject leagueData = league(team1, team2);

        // Build a schedule by hand with one past game and two future games for proTeam 100.
        JsonObject pastGame = new JsonObject();
        pastGame.addProperty("date", NOW - DAY);
        JsonObject futureGame1 = new JsonObject();
        futureGame1.addProperty("date", NOW + DAY);
        JsonObject futureGame2 = new JsonObject();
        futureGame2.addProperty("date", NOW + 2 * DAY);

        JsonArray pastArr = new JsonArray();
        pastArr.add(pastGame);
        JsonArray future1Arr = new JsonArray();
        future1Arr.add(futureGame1);
        JsonArray future2Arr = new JsonArray();
        future2Arr.add(futureGame2);

        JsonObject byPeriod = new JsonObject();
        byPeriod.add("1", pastArr);
        byPeriod.add("2", future1Arr);
        byPeriod.add("3", future2Arr);

        JsonObject proTeam = new JsonObject();
        proTeam.addProperty("id", 100);
        proTeam.add("proGamesByScoringPeriod", byPeriod);
        JsonArray proTeams = new JsonArray();
        proTeams.add(proTeam);
        JsonObject settings = new JsonObject();
        settings.add("proTeams", proTeams);
        JsonObject scheduleData = new JsonObject();
        scheduleData.add("settings", settings);

        TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                1, List.of(101), 2, List.of(201), NOW);

        assertEquals(40.0, result.team1RawGiven(), 0.001); // 20 ppg * 2 remaining games (not 3)
    }

    @Test
    void deficientPositionBoostsAdjustedValue() {
        // team1 (perspective team) has zero centers; two other teams have 2 centers each -> league avg C ~= 1.33, team1 is deficient.
        JsonObject team1 = team(1, player(101, "Give Guy", SG, 100, 20.0, "ACTIVE"));
        JsonObject team2 = team(2,
                player(201, "Get Center", C, 200, 20.0, "ACTIVE"),
                player(202, "Other C", C, 200, 10.0, "ACTIVE"));
        JsonObject team3 = team(3,
                player(301, "Filler C1", C, 300, 10.0, "ACTIVE"),
                player(302, "Filler C2", C, 300, 10.0, "ACTIVE"));

        JsonObject leagueData = league(team1, team2, team3);
        JsonObject scheduleData = scheduleFor(Map.of(100, 10, 200, 10, 300, 10));

        TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                1, List.of(101), 2, List.of(201), NOW);

        // Raw: receiving Get Center = 20 ppg * 10 games = 200
        assertEquals(200.0, result.team1RawReceived(), 0.001);
        // Adjusted: team1 is deficient at C -> +10% boost
        assertEquals(220.0, result.team1AdjustedReceived(), 0.001);

        assertTrue(result.fitNotes().stream().anyMatch(n ->
                n.playerName().equals("Get Center") && n.receiving() && n.fit() == Fit.DEFICIENT));
    }

    @Test
    void surplusPositionDiscountsAdjustedValue() {
        // team1 has 4 SGs (surplus); two other teams have 1 SG each -> league avg SG = 2, team1 (4) is surplus.
        JsonObject team1 = team(1,
                player(101, "Give SG", SG, 100, 20.0, "ACTIVE"),
                player(102, "SG2", SG, 100, 5.0, "ACTIVE"),
                player(103, "SG3", SG, 100, 5.0, "ACTIVE"),
                player(104, "SG4", SG, 100, 5.0, "ACTIVE"));
        JsonObject team2 = team(2,
                player(201, "Get Guy", PF, 200, 20.0, "ACTIVE"),
                player(202, "OtherSG", SG, 200, 5.0, "ACTIVE"));
        JsonObject team3 = team(3, player(301, "FillerSG", SG, 300, 5.0, "ACTIVE"));

        JsonObject leagueData = league(team1, team2, team3);
        JsonObject scheduleData = scheduleFor(Map.of(100, 10, 200, 10, 300, 10));

        TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                1, List.of(101), 2, List.of(201), NOW);

        // Raw: giving away Give SG = 20 ppg * 10 games = 200
        assertEquals(200.0, result.team1RawGiven(), 0.001);
        // Adjusted: team1 is surplus at SG -> -10% discount (losing a surplus player hurts less)
        assertEquals(180.0, result.team1AdjustedGiven(), 0.001);

        assertTrue(result.fitNotes().stream().anyMatch(n ->
                n.playerName().equals("Give SG") && !n.receiving() && n.fit() == Fit.SURPLUS));
    }

    @Test
    void neutralPositionProducesNoFitNoteAndNoAdjustment() {
        // Every team has exactly 1 PF -> perfectly average, neutral fit.
        JsonObject team1 = team(1, player(101, "Give PF", PF, 100, 20.0, "ACTIVE"));
        JsonObject team2 = team(2, player(201, "Get Guy", SF, 200, 20.0, "ACTIVE"), player(202, "OtherPF", PF, 200, 5.0, "ACTIVE"));
        JsonObject team3 = team(3, player(301, "FillerPF", PF, 300, 5.0, "ACTIVE"));

        JsonObject leagueData = league(team1, team2, team3);
        JsonObject scheduleData = scheduleFor(Map.of(100, 10, 200, 10, 300, 10));

        TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                1, List.of(101), 2, List.of(201), NOW);

        assertEquals(result.team1RawGiven(), result.team1AdjustedGiven(), 0.001);
        assertTrue(result.fitNotes().stream().noneMatch(n -> n.playerName().equals("Give PF")));
    }

    @Test
    void multiPlayerTradeSumsAllPlayersOnEachSide() {
        JsonObject team1 = team(1,
                player(101, "Give A", SG, 100, 10.0, "ACTIVE"),
                player(102, "Give B", SF, 100, 15.0, "ACTIVE"));
        JsonObject team2 = team(2,
                player(201, "Get A", PF, 200, 12.0, "ACTIVE"),
                player(202, "Get B", C, 200, 18.0, "ACTIVE"));

        JsonObject leagueData = league(team1, team2);
        JsonObject scheduleData = scheduleFor(Map.of(100, 10, 200, 10));

        TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                1, List.of(101, 102), 2, List.of(201, 202), NOW);

        assertEquals(2, result.team1Gives().size());
        assertEquals(2, result.team2Gives().size());
        assertEquals((10.0 + 15.0) * 10, result.team1RawGiven(), 0.001);
        assertEquals((12.0 + 18.0) * 10, result.team1RawReceived(), 0.001);
    }

    @Test
    void injuryStatusIsCarriedThroughToPlayerValue() {
        JsonObject team1 = team(1, player(101, "Hurt Guy", SG, 100, 20.0, "OUT"));
        JsonObject team2 = team(2, player(201, "Healthy Guy", SF, 200, 20.0, "ACTIVE"));
        JsonObject leagueData = league(team1, team2);
        JsonObject scheduleData = scheduleFor(Map.of(100, 10, 200, 10));

        TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                1, List.of(101), 2, List.of(201), NOW);

        assertEquals("OUT", result.team1Gives().get(0).injuryStatus());
        assertEquals("ACTIVE", result.team2Gives().get(0).injuryStatus());
    }
}
