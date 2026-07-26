package com.fantasy.bot.powerrankings;

import com.fantasy.bot.powerrankings.PowerRankingsCalculator.TeamPowerRank;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PowerRankingsCalculatorTest {

    private JsonObject team(int id, String name) {
        JsonObject overall = new JsonObject();
        overall.addProperty("wins", 0);
        overall.addProperty("losses", 0);
        overall.addProperty("ties", 0);
        JsonObject record = new JsonObject();
        record.add("overall", overall);

        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        t.addProperty("name", name);
        t.add("record", record);
        return t;
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

    /**
     * Hand-computed 4-team, 2-week scenario:
     * Week 1: A=100 B=80 C=90 D=70  -> A:3-0 B:1-2 C:2-1 D:0-3
     * Week 2: A=85  B=75 C=96 D=65  -> A:2-1 B:1-2 C:3-0 D:0-3
     * Totals: A=5-1 (185 pts), C=5-1 (186 pts), B=2-4 (155 pts), D=0-6 (135 pts)
     * A and C tie on all-play record; C should rank #1 on higher total points.
     */
    @Test
    void computesAllPlayRecordAndBreaksTiesByTotalPoints() {
        JsonArray teams = new JsonArray();
        teams.add(team(1, "A"));
        teams.add(team(2, "B"));
        teams.add(team(3, "C"));
        teams.add(team(4, "D"));

        JsonArray schedule = new JsonArray();
        schedule.add(matchup(1, 1, 100, 2, 80));
        schedule.add(matchup(1, 3, 90, 4, 70));
        schedule.add(matchup(2, 1, 85, 3, 96));
        schedule.add(matchup(2, 2, 75, 4, 65));

        JsonObject league = new JsonObject();
        league.add("teams", teams);
        league.add("schedule", schedule);

        List<TeamPowerRank> ranks = PowerRankingsCalculator.compute(league, 2);

        assertEquals(4, ranks.size());

        TeamPowerRank first = ranks.get(0);
        assertEquals("C", first.teamName());
        assertEquals(5, first.allPlayWins());
        assertEquals(1, first.allPlayLosses());
        assertEquals(186.0, first.totalPoints(), 0.001);

        TeamPowerRank second = ranks.get(1);
        assertEquals("A", second.teamName());
        assertEquals(5, second.allPlayWins());
        assertEquals(1, second.allPlayLosses());
        assertEquals(185.0, second.totalPoints(), 0.001);

        TeamPowerRank third = ranks.get(2);
        assertEquals("B", third.teamName());
        assertEquals(2, third.allPlayWins());
        assertEquals(4, third.allPlayLosses());

        TeamPowerRank fourth = ranks.get(3);
        assertEquals("D", fourth.teamName());
        assertEquals(0, fourth.allPlayWins());
        assertEquals(6, fourth.allPlayLosses());
    }

    @Test
    void everyTeamHasSameNumberOfDecisions() {
        JsonArray teams = new JsonArray();
        teams.add(team(1, "A"));
        teams.add(team(2, "B"));
        teams.add(team(3, "C"));
        teams.add(team(4, "D"));

        JsonArray schedule = new JsonArray();
        schedule.add(matchup(1, 1, 100, 2, 80));
        schedule.add(matchup(1, 3, 90, 4, 70));

        JsonObject league = new JsonObject();
        league.add("teams", teams);
        league.add("schedule", schedule);

        List<TeamPowerRank> ranks = PowerRankingsCalculator.compute(league, 1);

        // 4 teams, 1 week -> each team should have exactly 3 decisions (vs the other 3 teams)
        for (TeamPowerRank r : ranks) {
            assertEquals(3, r.allPlayWins() + r.allPlayLosses() + r.allPlayTies());
        }
    }

    @Test
    void tiedScoresProduceAllPlayTies() {
        JsonArray teams = new JsonArray();
        teams.add(team(1, "A"));
        teams.add(team(2, "B"));

        JsonArray schedule = new JsonArray();
        schedule.add(matchup(1, 1, 100, 2, 100));

        JsonObject league = new JsonObject();
        league.add("teams", teams);
        league.add("schedule", schedule);

        List<TeamPowerRank> ranks = PowerRankingsCalculator.compute(league, 1);

        for (TeamPowerRank r : ranks) {
            assertEquals(0, r.allPlayWins());
            assertEquals(0, r.allPlayLosses());
            assertEquals(1, r.allPlayTies());
        }
    }

    @Test
    void ignoresUnplayedWeeks() {
        JsonArray teams = new JsonArray();
        teams.add(team(1, "A"));
        teams.add(team(2, "B"));

        JsonArray schedule = new JsonArray();
        schedule.add(matchup(1, 1, 100, 2, 80));
        // week 2 matchup with 0-0 (not played yet)
        schedule.add(matchup(2, 1, 0, 2, 0));

        JsonObject league = new JsonObject();
        league.add("teams", teams);
        league.add("schedule", schedule);

        List<TeamPowerRank> ranks = PowerRankingsCalculator.compute(league, 2);

        for (TeamPowerRank r : ranks) {
            assertEquals(1, r.allPlayWins() + r.allPlayLosses() + r.allPlayTies());
        }
    }
}
