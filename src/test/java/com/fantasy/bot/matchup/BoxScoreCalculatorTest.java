package com.fantasy.bot.matchup;

import com.fantasy.bot.matchup.BoxScoreCalculator.TopScorer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BoxScoreCalculatorTest {

    private static final int PG = 0, BENCH = 12, IR = 13;
    private static final Set<Integer> STARTING = Set.of(PG);

    private JsonObject dayRoster(int teamId, int slotId, String playerName, double appliedStatTotal) {
        JsonObject player = new JsonObject();
        player.addProperty("fullName", playerName);

        JsonObject ppe = new JsonObject();
        ppe.addProperty("appliedStatTotal", appliedStatTotal);
        ppe.add("player", player);

        JsonObject entry = new JsonObject();
        entry.addProperty("lineupSlotId", slotId);
        entry.add("playerPoolEntry", ppe);

        JsonArray entries = new JsonArray();
        entries.add(entry);

        JsonObject roster = new JsonObject();
        roster.add("entries", entries);

        JsonObject team = new JsonObject();
        team.addProperty("id", teamId);
        team.add("roster", roster);

        JsonArray teams = new JsonArray();
        teams.add(team);

        JsonObject league = new JsonObject();
        league.add("teams", teams);
        return league;
    }

    @Test
    void sumsPointsAcrossMultipleDaysForTheSamePlayer() {
        Map<Integer, JsonObject> rosterByDay = new HashMap<>();
        rosterByDay.put(1, dayRoster(5, PG, "Star Player", 40.0));
        rosterByDay.put(2, dayRoster(5, PG, "Star Player", 35.0));

        Optional<TopScorer> top = BoxScoreCalculator.computeTopScorer(5, Set.of(1, 2), rosterByDay, STARTING);

        assertTrue(top.isPresent());
        assertEquals("Star Player", top.get().playerName());
        assertEquals(75.0, top.get().points(), 0.001);
    }

    @Test
    void picksHighestScoringStarterAmongMultiple() {
        // Simulate two different starters across two days by merging two single-entry days
        // into a combined roster for day 1 (both entries present that day).
        JsonObject player1 = new JsonObject();
        player1.addProperty("fullName", "Low Scorer");
        JsonObject ppe1 = new JsonObject();
        ppe1.addProperty("appliedStatTotal", 10.0);
        ppe1.add("player", player1);
        JsonObject entry1 = new JsonObject();
        entry1.addProperty("lineupSlotId", PG);
        entry1.add("playerPoolEntry", ppe1);

        JsonObject player2 = new JsonObject();
        player2.addProperty("fullName", "High Scorer");
        JsonObject ppe2 = new JsonObject();
        ppe2.addProperty("appliedStatTotal", 50.0);
        ppe2.add("player", player2);
        JsonObject entry2 = new JsonObject();
        entry2.addProperty("lineupSlotId", 1); // a different starting slot
        entry2.add("playerPoolEntry", ppe2);

        JsonArray entries = new JsonArray();
        entries.add(entry1);
        entries.add(entry2);
        JsonObject roster = new JsonObject();
        roster.add("entries", entries);
        JsonObject team = new JsonObject();
        team.addProperty("id", 5);
        team.add("roster", roster);
        JsonArray teams = new JsonArray();
        teams.add(team);
        JsonObject league = new JsonObject();
        league.add("teams", teams);

        Map<Integer, JsonObject> rosterByDay = new HashMap<>();
        rosterByDay.put(1, league);

        Optional<TopScorer> top = BoxScoreCalculator.computeTopScorer(5, Set.of(1), rosterByDay, Set.of(PG, 1));

        assertTrue(top.isPresent());
        assertEquals("High Scorer", top.get().playerName());
        assertEquals(50.0, top.get().points(), 0.001);
    }

    @Test
    void excludesBenchAndIrPlayers() {
        Map<Integer, JsonObject> rosterByDay = new HashMap<>();
        rosterByDay.put(1, dayRoster(5, BENCH, "Bench Guy", 999.0));

        Optional<TopScorer> top = BoxScoreCalculator.computeTopScorer(5, Set.of(1), rosterByDay, STARTING);

        assertTrue(top.isEmpty());
    }

    @Test
    void ignoresDaysNotPresentInRosterByDayMap() {
        Map<Integer, JsonObject> rosterByDay = new HashMap<>();
        rosterByDay.put(1, dayRoster(5, PG, "Star Player", 40.0));
        // day 2 requested but not fetched/available

        Optional<TopScorer> top = BoxScoreCalculator.computeTopScorer(5, Set.of(1, 2), rosterByDay, STARTING);

        assertTrue(top.isPresent());
        assertEquals(40.0, top.get().points(), 0.001); // only day 1 counted
    }

    @Test
    void returnsEmptyWhenTeamHasNoStartersThatDay() {
        Map<Integer, JsonObject> rosterByDay = new HashMap<>();
        rosterByDay.put(1, dayRoster(5, PG, "Star Player", 40.0));

        // querying a different team id that isn't in the fixture
        Optional<TopScorer> top = BoxScoreCalculator.computeTopScorer(999, Set.of(1), rosterByDay, STARTING);

        assertTrue(top.isEmpty());
    }
}
