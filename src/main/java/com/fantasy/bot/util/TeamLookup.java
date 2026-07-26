package com.fantasy.bot.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.interactions.commands.Command;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared "find a team by name" + autocomplete logic for any command that
 * takes a team name option (e.g. /team, /headtohead).
 */
public class TeamLookup {

    /** Exact case-insensitive match takes priority; falls back to the first case-insensitive partial match. */
    public static JsonObject findTeam(JsonArray teamsArray, String query) {
        String q = query.toLowerCase().trim();
        JsonObject partialMatch = null;

        for (int i = 0; i < teamsArray.size(); i++) {
            JsonObject team = teamsArray.get(i).getAsJsonObject();
            String name = displayName(team).toLowerCase();
            if (name.equals(q)) return team;
            if (partialMatch == null && name.contains(q)) partialMatch = team;
        }
        return partialMatch;
    }

    public static List<Command.Choice> autocompleteChoices(JsonObject leagueData, String typed) {
        List<Command.Choice> choices = new ArrayList<>();
        String typedLower = typed.toLowerCase();

        JsonArray teams = leagueData.getAsJsonArray("teams");
        for (int i = 0; i < teams.size(); i++) {
            JsonObject team = teams.get(i).getAsJsonObject();
            String name = displayName(team);
            if (typedLower.isEmpty() || name.toLowerCase().contains(typedLower)) {
                choices.add(new Command.Choice(name, name));
            }
            if (choices.size() >= 25) break; // Discord's autocomplete limit
        }
        return choices;
    }

    public static String displayName(JsonObject team) {
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
}
