package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.awt.Color;

public class LeagueCommand extends ListenerAdapter {
    private final ESPNApiClient apiClient = new ESPNApiClient();

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
            Dotenv dotenv = Dotenv.load();
            String season = dotenv.get("ESPN_SEASON_ID") != null ?
                    dotenv.get("ESPN_SEASON_ID") : "2026";

            Integer currentWeek = null;

            if (data.has("status") && data.get("status").isJsonObject()) {
                JsonObject status = data.getAsJsonObject("status");
                if (status.has("currentMatchupPeriod") && !status.get("currentMatchupPeriod").isJsonNull()) {
                    currentWeek = status.get("currentMatchupPeriod").getAsInt();
                }
            }

            if (currentWeek == null && data.has("scoringPeriodId") && !data.get("scoringPeriodId").isJsonNull()) {
                currentWeek = data.get("scoringPeriodId").getAsInt();
                System.out.println("Fell back to scoringPeriodId: " + currentWeek);
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
                    .addField("Current Week", currentWeekDisplay, true);

            event.getHook().sendMessageEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Failed to fetch league data. Check your configuration.").queue();
            e.printStackTrace();
        }
    }
}