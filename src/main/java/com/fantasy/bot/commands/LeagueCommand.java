package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LeagueCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(LeagueCommand.class);

    private final ESPNApiClient apiClient;

    public LeagueCommand(ESPNApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public static CommandData getCommandData() {
        return Commands.slash("league", "Show your league info");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("league")) return;

        event.deferReply().queue();

        try {
            JsonObject data = apiClient.getLeagueData();
            JsonObject settings = data.getAsJsonObject("settings");

            String leagueName = settings.get("name").getAsString();
            int teams = settings.get("size").getAsInt();
            String season = BotConfig.get().getEspnSeasonId();

            Integer currentWeek = null;

            if (data.has("status") && data.get("status").isJsonObject()) {
                JsonObject status = data.getAsJsonObject("status");
                if (status.has("currentMatchupPeriod") && !status.get("currentMatchupPeriod").isJsonNull()) {
                    currentWeek = status.get("currentMatchupPeriod").getAsInt();
                }
            }

            if (currentWeek == null && data.has("scoringPeriodId") && !data.get("scoringPeriodId").isJsonNull()) {
                currentWeek = data.get("scoringPeriodId").getAsInt();
                log.debug("Fell back to scoringPeriodId: {}", currentWeek);
            }

            String currentWeekDisplay = (currentWeek != null) ? String.valueOf(currentWeek) : "—";
            int endYear = Integer.parseInt(season);
            int startYear = endYear - 1;

            //25/26 format. It just looks better than 2025/26 (spacing wise)
            String seasonDisplay = String.format("%02d/%02d", startYear % 100, endYear % 100);

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("🏀 " + leagueName)
                    .setColor(Color.ORANGE)
                    .addField("Season", seasonDisplay, true)
                    .addField("Teams", String.valueOf(teams), true)
                    .addField("Current Week", currentWeekDisplay, true)
                    .addField("Team IDs", buildTeamIdList(data), false);

            event.getHook().sendMessageEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Failed to fetch league data. Check your configuration.").queue();
            log.error("Failed to fetch league data", e);
        }
    }

    private record TeamIdEntry(int id, String name) {
    }

    // Surfaced here (not in /standings, to keep that one uncluttered) so
    // self-hosters can find the IDs needed for team_owners.json.
    private static String buildTeamIdList(JsonObject data) {
        JsonArray teamsArray = data.getAsJsonArray("teams");
        if (teamsArray == null || teamsArray.isEmpty()) return "—";

        List<TeamIdEntry> entries = new ArrayList<>();
        for (int i = 0; i < teamsArray.size(); i++) {
            JsonObject team = teamsArray.get(i).getAsJsonObject();
            int id = team.has("id") && !team.get("id").isJsonNull() ? team.get("id").getAsInt() : -1;
            entries.add(new TeamIdEntry(id, displayName(team)));
        }
        entries.sort(Comparator.comparingInt(TeamIdEntry::id));

        int idW = 2;
        for (TeamIdEntry entry : entries) {
            idW = Math.max(idW, String.valueOf(entry.id()).length());
        }

        StringBuilder sb = new StringBuilder("```txt\n");
        for (TeamIdEntry entry : entries) {
            sb.append(String.format("%" + idW + "d  %s%n", entry.id(), entry.name()));
        }
        sb.append("```");
        return sb.toString();
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
