package com.fantasy.bot.headtohead;

import com.fantasy.bot.headtohead.HeadToHeadCalculator.HeadToHeadRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeadToHeadCalculatorTest {

    private JsonObject season(int seasonId, JsonObject... matchups) {
        JsonArray schedule = new JsonArray();
        for (JsonObject m : matchups) schedule.add(m);

        JsonObject season = new JsonObject();
        season.addProperty("seasonId", seasonId);
        season.add("schedule", schedule);
        return season;
    }

    private JsonObject matchup(int week, int homeId, double homePts, int awayId, double awayPts) {
        JsonObject home = new JsonObject();
        home.addProperty("teamId", homeId);
        home.addProperty("totalPoints", homePts);

        JsonObject away = new JsonObject();
        away.addProperty("teamId", awayId);
        away.addProperty("totalPoints", awayPts);

        JsonObject m = new JsonObject();
        m.addProperty("matchupPeriodId", week);
        m.add("home", home);
        m.add("away", away);
        return m;
    }

    @Test
    void ignoresMatchupsBetweenOtherTeams() {
        JsonObject s = season(2024, matchup(1, 1, 100, 2, 90), matchup(1, 3, 50, 4, 40));

        HeadToHeadRecord r = HeadToHeadCalculator.compute(List.of(s), 1, 2);

        assertEquals(1, r.gamesPlayed());
    }

    @Test
    void aggregatesAcrossMultipleSeasons() {
        JsonObject s2024 = season(2024, matchup(5, 1, 100, 2, 90));
        JsonObject s2025 = season(2025, matchup(8, 2, 95, 1, 80)); // note: teamB is home this time

        HeadToHeadRecord r = HeadToHeadCalculator.compute(List.of(s2024, s2025), 1, 2);

        assertEquals(2, r.gamesPlayed());
        // 2024: A(100) > B(90) -> A win. 2025: A(80) < B(95) -> B win.
        assertEquals(1, r.teamAWins());
        assertEquals(1, r.teamBWins());
        assertEquals(180.0, r.teamATotalPoints(), 0.001);
        assertEquals(185.0, r.teamBTotalPoints(), 0.001);
    }

    @Test
    void identifiesBiggestBlowoutAndMostRecent() {
        JsonObject s2023 = season(2023, matchup(3, 1, 150, 2, 100)); // margin 50
        JsonObject s2024 = season(2024, matchup(5, 1, 100, 2, 90));  // margin 10, most recent by season

        HeadToHeadRecord r = HeadToHeadCalculator.compute(List.of(s2023, s2024), 1, 2);

        assertTrue(r.biggestBlowout().isPresent());
        assertEquals(2023, r.biggestBlowout().get().season());
        assertEquals(50.0, r.biggestBlowout().get().margin(), 0.001);

        assertTrue(r.mostRecent().isPresent());
        assertEquals(2024, r.mostRecent().get().season());
    }

    @Test
    void mostRecentBreaksTiesBySeasonThenWeek() {
        JsonObject s2024 = season(2024, matchup(3, 1, 100, 2, 90), matchup(10, 1, 110, 2, 95));

        HeadToHeadRecord r = HeadToHeadCalculator.compute(List.of(s2024), 1, 2);

        assertTrue(r.mostRecent().isPresent());
        assertEquals(10, r.mostRecent().get().week());
    }

    @Test
    void skipsUnplayedGames() {
        JsonObject s = season(2024, matchup(1, 1, 0, 2, 0)); // not played

        HeadToHeadRecord r = HeadToHeadCalculator.compute(List.of(s), 1, 2);

        assertEquals(0, r.gamesPlayed());
    }

    @Test
    void noHistoryReturnsZeroGamesPlayed() {
        HeadToHeadRecord r = HeadToHeadCalculator.compute(List.of(), 1, 2);

        assertEquals(0, r.gamesPlayed());
        assertTrue(r.biggestBlowout().isEmpty());
        assertTrue(r.mostRecent().isEmpty());
    }

    @Test
    void tieIsCountedCorrectly() {
        JsonObject s = season(2024, matchup(1, 1, 100, 2, 100));

        HeadToHeadRecord r = HeadToHeadCalculator.compute(List.of(s), 1, 2);

        assertEquals(1, r.gamesPlayed());
        assertEquals(0, r.teamAWins());
        assertEquals(0, r.teamBWins());
        assertEquals(1, r.ties());
    }
}
