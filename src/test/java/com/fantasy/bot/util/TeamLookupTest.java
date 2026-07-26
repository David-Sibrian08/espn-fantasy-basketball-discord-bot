package com.fantasy.bot.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.interactions.commands.Command;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamLookupTest {

    private JsonObject team(int id, String name) {
        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        t.addProperty("name", name);
        return t;
    }

    private JsonObject teamWithoutName(int id, String location, String nickname) {
        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        if (location != null) t.addProperty("location", location);
        if (nickname != null) t.addProperty("nickname", nickname);
        return t;
    }

    private JsonArray teamsArray(JsonObject... teams) {
        JsonArray arr = new JsonArray();
        for (JsonObject t : teams) arr.add(t);
        return arr;
    }

    @Test
    void findTeam_exactMatchCaseInsensitive() {
        JsonArray teams = teamsArray(team(1, "Big Bad Govt"), team(2, "Jokic ?"));
        JsonObject found = TeamLookup.findTeam(teams, "jokic ?");
        assertNotNull(found);
        assertEquals(2, found.get("id").getAsInt());
    }

    @Test
    void findTeam_partialMatchFallback() {
        JsonArray teams = teamsArray(team(1, "Big Bad Govt"), team(2, "Codename: Kids Next Door"));
        JsonObject found = TeamLookup.findTeam(teams, "kids next");
        assertNotNull(found);
        assertEquals(2, found.get("id").getAsInt());
    }

    @Test
    void findTeam_exactMatchTakesPriorityOverPartial() {
        // "Big" is a partial match for "Big Bad Govt" but there's also a team literally named "Big"
        JsonArray teams = teamsArray(team(1, "Big Bad Govt"), team(2, "Big"));
        JsonObject found = TeamLookup.findTeam(teams, "Big");
        assertNotNull(found);
        assertEquals(2, found.get("id").getAsInt());
    }

    @Test
    void findTeam_noMatchReturnsNull() {
        JsonArray teams = teamsArray(team(1, "Big Bad Govt"));
        assertNull(TeamLookup.findTeam(teams, "zzz_nonexistent_zzz"));
    }

    @Test
    void displayName_prefersNameField() {
        assertEquals("Big Bad Govt", TeamLookup.displayName(team(1, "Big Bad Govt")));
    }

    @Test
    void displayName_fallsBackToLocationAndNickname() {
        JsonObject t = teamWithoutName(1, "Big Bad", "Govt");
        assertEquals("Big Bad Govt", TeamLookup.displayName(t));
    }

    @Test
    void displayName_fallsBackToTeamIdWhenNothingElseAvailable() {
        JsonObject t = teamWithoutName(7, null, null);
        assertEquals("Team 7", TeamLookup.displayName(t));
    }

    @Test
    void autocompleteChoices_filtersBySubstringCaseInsensitive() {
        JsonObject data = new JsonObject();
        data.add("teams", teamsArray(team(1, "Big Bad Govt"), team(2, "Jokic ?"), team(3, "Pelican Bay")));

        List<Command.Choice> choices = TeamLookup.autocompleteChoices(data, "ba");

        assertEquals(2, choices.size());
        assertTrue(choices.stream().anyMatch(c -> c.getName().equals("Big Bad Govt")));
        assertTrue(choices.stream().anyMatch(c -> c.getName().equals("Pelican Bay")));
    }

    @Test
    void autocompleteChoices_emptyTypedReturnsAll() {
        JsonObject data = new JsonObject();
        data.add("teams", teamsArray(team(1, "A"), team(2, "B")));

        assertEquals(2, TeamLookup.autocompleteChoices(data, "").size());
    }

    @Test
    void autocompleteChoices_capsAt25() {
        JsonObject[] many = new JsonObject[30];
        for (int i = 0; i < 30; i++) many[i] = team(i, "Team" + i);
        JsonObject data = new JsonObject();
        data.add("teams", teamsArray(many));

        assertEquals(25, TeamLookup.autocompleteChoices(data, "").size());
    }
}
