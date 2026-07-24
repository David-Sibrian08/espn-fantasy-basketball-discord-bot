package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.lineup.LineupHealthChecker;
import com.fantasy.bot.lineup.LineupHealthChecker.LineupAlert;
import com.fantasy.bot.lineup.TeamOwnerRegistry;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.List;
import java.util.Set;

public class LineupCheckCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(LineupCheckCommand.class);

    // Wide window: on-demand should show anything not yet locked today, not just the next 30 min.
    private static final long CHECK_WINDOW_MILLIS = 24 * 60 * 60 * 1000L;

    private final ESPNApiClient apiClient;
    private final TeamOwnerRegistry ownerRegistry;

    public LineupCheckCommand(ESPNApiClient apiClient, TeamOwnerRegistry ownerRegistry) {
        this.apiClient = apiClient;
        this.ownerRegistry = ownerRegistry;
    }

    public static CommandData getCommandData() {
        return Commands.slash("lineupcheck", "Check for OUT/DOUBTFUL players currently in a starting lineup slot");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("lineupcheck")) return;

        event.deferReply().queue();

        try {
            JsonObject league = apiClient.getLeagueData();
            JsonObject schedule = apiClient.getProTeamSchedules();
            int scoringPeriodId = league.get("scoringPeriodId").getAsInt();

            List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                    league, schedule, scoringPeriodId, System.currentTimeMillis(),
                    CHECK_WINDOW_MILLIS, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, Set.of());

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("🩺 Lineup Health Check")
                    .setColor(Color.RED);

            if (alerts.isEmpty()) {
                embed.setDescription("No OUT/DOUBTFUL players found starting in today's remaining games.");
            } else {
                StringBuilder sb = new StringBuilder();
                for (LineupAlert alert : alerts) {
                    String discordUserId = ownerRegistry.getDiscordUserId(alert.espnTeamId());
                    String owner = discordUserId != null ? "<@" + discordUserId + ">" : alert.teamName();

                    long minutesUntilLock = Math.max(0, (alert.gameStartEpochMillis() - System.currentTimeMillis()) / 60_000);
                    sb.append("⚠️ **").append(alert.playerName()).append("** (").append(alert.injuryStatus()).append(") — ")
                            .append(alert.teamName()).append(" (").append(owner).append(") — locks in ~")
                            .append(minutesUntilLock).append(" min\n");
                }
                embed.setDescription(sb.toString());
            }

            event.getHook().sendMessageEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Failed to run lineup check.").queue();
            log.error("Failed to run lineup check", e);
        }
    }
}
