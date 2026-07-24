package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;

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
                    .addField("Current Week", currentWeekDisplay, true);

            event.getHook().sendMessageEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Failed to fetch league data. Check your configuration.").queue();
            log.error("Failed to fetch league data", e);
        }
    }
}
