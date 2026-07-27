package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.powerrankings.PowerRankingsCalculator;
import com.fantasy.bot.powerrankings.PowerRankingsCalculator.TeamPowerRank;
import com.fantasy.bot.util.ErrorReplies;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class PowerRankingsCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PowerRankingsCommand.class);

    private final ESPNApiClient apiClient;

    public PowerRankingsCommand(ESPNApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public static CommandData getCommandData() {
        return Commands.slash("powerrankings", "Show power rankings (all-play record) through a given week")
                .addOption(OptionType.INTEGER, "week", "Compute through this week (defaults to last completed week)", false);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("powerrankings")) return;

        event.deferReply().queue();

        try {
            JsonObject data = apiClient.getLeagueData();

            Integer currentWeek = getCurrentWeek(data);
            if (currentWeek == null) currentWeek = 1;

            Integer weekOption = event.getOption("week") != null ? event.getOption("week").getAsInt() : null;
            int throughWeek = weekOption != null ? weekOption : Math.max(1, currentWeek - 1);

            List<TeamPowerRank> ranks = PowerRankingsCalculator.compute(data, throughWeek);

            int rankW = String.valueOf(ranks.size()).length();
            int nameW = 8;
            for (TeamPowerRank r : ranks) {
                nameW = Math.max(nameW, r.teamName().length());
            }

            List<String> lines = new ArrayList<>();
            for (int i = 0; i < ranks.size(); i++) {
                TeamPowerRank r = ranks.get(i);
                String rank = String.format("%" + rankW + "d", i + 1);
                String name = String.format("%-" + nameW + "s", r.teamName());
                String allPlay = r.allPlayWins() + "-" + r.allPlayLosses() + (r.allPlayTies() > 0 ? "-" + r.allPlayTies() : "");
                String actual = r.actualWins() + "-" + r.actualLosses() + (r.actualTies() > 0 ? "-" + r.actualTies() : "");
                lines.add(rank + ". " + name + "  " + String.format("%-10s", allPlay) + "  actual: " + actual);
            }

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("💪 Power Rankings (through Week " + throughWeek + ")")
                    .setColor(Color.decode("#1a1a1a"))
                    .setDescription("```txt\n" + String.join("\n", lines) + "\n```")
                    .setFooter("All-play record: how each team would've done if they played everyone every week");

            event.getHook().sendMessageEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage(ErrorReplies.forFailure("compute power rankings", e)).queue();
            log.error("Failed to compute power rankings", e);
        }
    }

    private Integer getCurrentWeek(JsonObject data) {
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

        return currentWeek;
    }
}
