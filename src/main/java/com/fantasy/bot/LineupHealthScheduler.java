package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.fantasy.bot.lineup.LineupAlertsState;
import com.fantasy.bot.lineup.LineupHealthChecker;
import com.fantasy.bot.lineup.LineupHealthChecker.LineupAlert;
import com.fantasy.bot.lineup.TeamOwnerRegistry;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls periodically for starting-lineup players who are OUT/DOUBTFUL with
 * a game about to lock, and @mentions their owner in a Discord channel.
 * NBA fantasy locks each player individually at their own game's tip-off
 * (daily, not weekly), so this can't reuse the weekly recap's cadence.
 */
public class LineupHealthScheduler {
    private static final Logger log = LoggerFactory.getLogger(LineupHealthScheduler.class);

    private static final long POLL_INTERVAL_MINUTES = 10;
    private static final long ALERT_WINDOW_MILLIS = 30 * 60 * 1000L;

    private final JDA jda;
    private final ESPNApiClient apiClient;
    private final TeamOwnerRegistry ownerRegistry;
    private final LineupAlertsState state;
    private final ScheduledExecutorService scheduler;
    private final long alertChannelId;
    private final Set<String> alreadyAlerted = ConcurrentHashMap.newKeySet();

    public LineupHealthScheduler(JDA jda, ESPNApiClient apiClient, TeamOwnerRegistry ownerRegistry, LineupAlertsState state) {
        this.jda = jda;
        this.apiClient = apiClient;
        this.ownerRegistry = ownerRegistry;
        this.state = state;
        this.scheduler = Executors.newScheduledThreadPool(1);

        Long channelId = BotConfig.get().getLineupAlertChannelId();
        this.alertChannelId = channelId != null ? channelId : 0L;
    }

    public void start() {
        if (alertChannelId == 0) {
            log.info("LINEUP_ALERT_CHANNEL_ID not set. Lineup health alerts disabled.");
            return;
        }

        scheduler.scheduleAtFixedRate(this::checkNow, 0, POLL_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("Lineup health scheduler started!");
    }

    private void checkNow() {
        if (!state.isEnabled()) return;

        TextChannel channel = jda.getTextChannelById(alertChannelId);
        if (channel == null) {
            log.warn("LINEUP_ALERT_CHANNEL_ID {} not found", alertChannelId);
            return;
        }

        try {
            JsonObject league = apiClient.getLeagueData();
            JsonObject schedule = apiClient.getProTeamSchedules();
            int scoringPeriodId = league.get("scoringPeriodId").getAsInt();

            List<LineupAlert> alerts = LineupHealthChecker.computeAlerts(
                    league, schedule, scoringPeriodId, System.currentTimeMillis(),
                    ALERT_WINDOW_MILLIS, LineupHealthChecker.DEFAULT_FLAGGED_STATUSES, alreadyAlerted);

            for (LineupAlert alert : alerts) {
                channel.sendMessage(formatAlert(alert)).queue();
                alreadyAlerted.add(alert.dedupeKey());
            }
        } catch (Exception e) {
            log.error("Failed to run lineup health check", e);
        }
    }

    private String formatAlert(LineupAlert alert) {
        String discordUserId = ownerRegistry.getDiscordUserId(alert.espnTeamId());
        String mention = discordUserId != null ? "<@" + discordUserId + ">" : "**" + alert.teamName() + "**";

        long minutesUntilLock = Math.max(0, (alert.gameStartEpochMillis() - System.currentTimeMillis()) / 60_000);

        return mention + " ⚠️ **" + alert.playerName() + "** is **" + alert.injuryStatus() +
                "** and starting for **" + alert.teamName() + "** — locks in about " + minutesUntilLock + " min!";
    }
}
