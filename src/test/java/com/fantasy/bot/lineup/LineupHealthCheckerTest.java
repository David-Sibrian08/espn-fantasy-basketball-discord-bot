package com.fantasy.bot.lineup;

import com.fantasy.bot.lineup.LineupHealthChecker.LineupAlert;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LineupHealthCheckerTest {

    private static final int PG = 0, BENCH = 12, IR = 13;
    private static final long NOW = 1_800_000_000_000L; // arbitrary fixed instant
    private static final long WINDOW = 30 * 60 * 1000L;

    private JsonObject leagueWithOneEntry(int teamId, int slotId, String playerName, String injuryStatus, int proTeamId) {
        JsonObject player = new JsonObject();
        player.addProperty("fullName", playerName);
        player.addProperty("injuryStatus", injuryStatus);
        player.addProperty("proTeamId", proTeamId);

        JsonObject ppe = new JsonObject();
        ppe.add("player", player);

        JsonObject entry = new JsonObject();
        entry.addProperty("playerId", 1);
        entry.addProperty("lineupSlotId", slotId);
        entry.add("playerPoolEntry", ppe);

        JsonArray entries = new JsonArray();
        entries.add(entry);

        JsonObject roster = new JsonObject();
        roster.add("entries", entries);

        JsonObject team = new JsonObject();
        team.addProperty("id", teamId);
        team.addProperty("name", "Team " + teamId);
        team.add("roster", roster);

        JsonArray teams = new JsonArray();
        teams.add(team);

        JsonObject slotCounts = new JsonObject();
        slotCounts.addProperty(String.valueOf(PG), 1);
        slotCounts.addProperty(String.valueOf(BENCH), 6);
        slotCounts.addProperty(String.valueOf(IR), 2);

        JsonObject rosterSettings = new JsonObject();
        rosterSettings.add("lineupSlotCounts", slotCounts);

        JsonObject settings = new JsonObject();
        settings.add("rosterSettings", rosterSettings);

        JsonObject league = new JsonObject();
        league.add("settings", settings);
        league.add("teams", teams);
        return league;
    }

    private JsonObject scheduleWithGame(int proTeamId, int scoringPeriodId, long dateMillis) {
        JsonObject game = new JsonObject();
        game.addProperty("date", dateMillis);
        JsonArray games = new JsonArray();
        games.add(game);

        JsonObject byPeriod = new JsonObject();
        byPeriod.add(String.valueOf(scoringPeriodId), games);

        JsonObject proTeam = new JsonObject();
        proTeam.addProperty("id", proTeamId);
        proTeam.add("proGamesByScoringPeriod", byPeriod);

        JsonArray proTeams = new JsonArray();
        proTeams.add(proTeam);

        JsonObject settings = new JsonObject();
        settings.add("proTeams", proTeams);

        JsonObject schedule = new JsonObject();
        schedule.add("settings", settings);
        return schedule;
    }

    @Test
    void flagsOutPlayerStartingWithinWindow() {
        JsonObject league = leagueWithOneEntry(1, PG, "Hurt Guy", "OUT", 99);
        JsonObject schedule = scheduleWithGame(99, 5, NOW + 20 * 60 * 1000L);

        List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());

        assertEquals(1, alerts.size());
        assertEquals("Hurt Guy", alerts.get(0).playerName());
        assertEquals("OUT", alerts.get(0).injuryStatus());
    }

    @Test
    void doesNotFlagWhenGameIsOutsideWindow() {
        JsonObject league = leagueWithOneEntry(1, PG, "Hurt Guy", "OUT", 99);
        JsonObject schedule = scheduleWithGame(99, 5, NOW + 40 * 60 * 1000L); // 40 min away, window is 30

        List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());

        assertTrue(alerts.isEmpty());
    }

    @Test
    void doesNotFlagBenchPlayers() {
        JsonObject league = leagueWithOneEntry(1, BENCH, "Hurt Guy", "OUT", 99);
        JsonObject schedule = scheduleWithGame(99, 5, NOW + 20 * 60 * 1000L);

        List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());

        assertTrue(alerts.isEmpty());
    }

    @Test
    void doesNotFlagIrPlayers() {
        JsonObject league = leagueWithOneEntry(1, IR, "Hurt Guy", "OUT", 99);
        JsonObject schedule = scheduleWithGame(99, 5, NOW + 20 * 60 * 1000L);

        List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());

        assertTrue(alerts.isEmpty());
    }

    @Test
    void doesNotFlagActivePlayers() {
        JsonObject league = leagueWithOneEntry(1, PG, "Healthy Guy", "ACTIVE", 99);
        JsonObject schedule = scheduleWithGame(99, 5, NOW + 20 * 60 * 1000L);

        List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());

        assertTrue(alerts.isEmpty());
    }

    @Test
    void doesNotFlagAlreadyStartedGames() {
        JsonObject league = leagueWithOneEntry(1, PG, "Hurt Guy", "OUT", 99);
        JsonObject schedule = scheduleWithGame(99, 5, NOW - 1000L); // already started

        List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());

        assertTrue(alerts.isEmpty());
    }

    @Test
    void dedupeSuppressesAlreadyAlertedGame() {
        JsonObject league = leagueWithOneEntry(1, PG, "Hurt Guy", "OUT", 99);
        long gameStart = NOW + 20 * 60 * 1000L;
        JsonObject schedule = scheduleWithGame(99, 5, gameStart);

        List<LineupAlert> first = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());
        assertEquals(1, first.size());

        List<LineupAlert> second = LineupHealthChecker.computeAlerts(
                league, schedule, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of(first.get(0).dedupeKey()));
        assertTrue(second.isEmpty());
    }

    @Test
    void doubtfulIsFlaggedByDefaultButQuestionableIsNot() {
        JsonObject leagueDoubtful = leagueWithOneEntry(1, PG, "Iffy Guy", "DOUBTFUL", 99);
        JsonObject scheduleDoubtful = scheduleWithGame(99, 5, NOW + 20 * 60 * 1000L);
        assertEquals(1, LineupHealthChecker.computeAlerts(
                leagueDoubtful, scheduleDoubtful, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of()).size());

        JsonObject leagueQuestionable = leagueWithOneEntry(1, PG, "Iffy Guy", "QUESTIONABLE", 99);
        JsonObject scheduleQuestionable = scheduleWithGame(99, 5, NOW + 20 * 60 * 1000L);
        assertTrue(LineupHealthChecker.computeAlerts(
                leagueQuestionable, scheduleQuestionable, 5, NOW, WINDOW, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of()).isEmpty());
    }
}
