package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.fantasy.bot.util.ErrorReplies;
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

public class StandingsCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(StandingsCommand.class);

    private final ESPNApiClient apiClient;

    public StandingsCommand(ESPNApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public static CommandData getCommandData() {
        return Commands.slash("standings", "Show current league standings");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("standings")) return;

        event.deferReply().queue();

        try {
            JsonObject data = apiClient.getLeagueData();
            JsonArray teams = data.getAsJsonArray("teams");

            List<TeamStanding> standings = new ArrayList<>();

            for (int i = 0; i < teams.size(); i++) {
                JsonObject team = teams.get(i).getAsJsonObject();
                JsonObject record = team.getAsJsonObject("record")
                        .getAsJsonObject("overall");

                String teamName = displayName(team);

                int wins = record.get("wins").getAsInt();
                int losses = record.get("losses").getAsInt();
                int ties = record.has("ties") ? record.get("ties").getAsInt() : 0;
                double percentage = record.get("percentage").getAsDouble();

                standings.add(new TeamStanding(teamName, wins, losses, ties, percentage));
            }

            standings.sort(Comparator.comparingDouble(TeamStanding::percentage).reversed());

            // Get current week for display
            Integer currentWeek = null;
            if (data.has("status") && data.get("status").isJsonObject()) {
                JsonObject status = data.getAsJsonObject("status");
                if (status.has("currentMatchupPeriod") && !status.get("currentMatchupPeriod").isJsonNull()) {
                    currentWeek = status.get("currentMatchupPeriod").getAsInt();
                }
            }
            if (currentWeek == null && data.has("scoringPeriodId") && !data.get("scoringPeriodId").isJsonNull()) {
                currentWeek = data.get("scoringPeriodId").getAsInt();
            }

            // Get season year
            String season = BotConfig.get().getEspnSeasonId();

            int endYear = Integer.parseInt(season);
            int startYear = endYear - 1;
            String seasonDisplay = String.format("%02d/%02d", startYear % 100, endYear % 100);

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Current Standings")
                    .setColor(Color.decode("#1a1a1a"))
                    .setFooter("Season " + seasonDisplay + " • Week " + (currentWeek != null ? currentWeek : "—"));

            int rankW = String.valueOf(standings.size()).length();

            int nameW = 8; // minimum width
            for (TeamStanding s : standings) {
                if (s.teamName() != null) {
                    nameW = Math.max(nameW, s.teamName().length());
                }
            }

            List<String> lines = new ArrayList<>();
            for (int i = 0; i < standings.size(); i++) {
                TeamStanding s = standings.get(i);

                String wl = s.wins() + "-" + s.losses() + (s.ties() > 0 ? "-" + s.ties() : "");
                String rank = String.format("%" + rankW + "d", i + 1);
                String name = String.format("%-" + nameW + "s", s.teamName());

                lines.add(rank + ". " + name + "  " + wl);
            }

            String description = "```txt\n" + String.join("\n", lines) + "\n```";
            embed.setDescription(description);

            event.getHook().sendMessageEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage(ErrorReplies.forFailure("fetch standings data", e)).queue();
            log.error("Failed to fetch standings data", e);
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String s = obj.get(key).getAsString();
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayName(JsonObject team) {
        // name > (location + nickname) > (teamLocation + teamNickname) > location/nickname/teamLocation/teamNickname > abbrev > Team {id}
        String name = getString(team, "name");
        if (name != null) return name;

        String loc = getString(team, "location");
        String nick = getString(team, "nickname");
        String locNick = joinNonBlank(loc, nick);
        if (locNick != null) return locNick;

        String teamLoc = getString(team, "teamLocation");
        String teamNick = getString(team, "teamNickname");
        String teamLocNick = joinNonBlank(teamLoc, teamNick);
        if (teamLocNick != null) return teamLocNick;

        if (loc != null) return loc;
        if (nick != null) return nick;
        if (teamLoc != null) return teamLoc;
        if (teamNick != null) return teamNick;

        String abbrev = getString(team, "abbrev");
        if (abbrev != null) return abbrev;

        int id = team.has("id") && !team.get("id").isJsonNull() ? team.get("id").getAsInt() : -1;
        return id > 0 ? ("Team " + id) : "Team";
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        String s = (a + " " + b).trim();
        return s.isEmpty() ? null : s;
    }

    private record TeamStanding(String teamName, int wins, int losses, int ties, double percentage) {
    }
}
