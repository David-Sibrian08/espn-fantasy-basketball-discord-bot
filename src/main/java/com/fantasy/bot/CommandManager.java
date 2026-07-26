package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.commands.*;
import com.fantasy.bot.config.BotConfig;
import com.fantasy.bot.lineup.LineupAlertsState;
import com.fantasy.bot.lineup.TeamOwnerRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandManager {
    private static final Logger log = LoggerFactory.getLogger(CommandManager.class);

    private final JDA jda;
    private final ESPNApiClient apiClient;
    private final WeeklyRecapScheduler scheduler;
    private final TeamOwnerRegistry ownerRegistry;
    private final LineupAlertsState lineupAlertsState;

    public CommandManager(JDA jda, ESPNApiClient apiClient, WeeklyRecapScheduler scheduler,
                           TeamOwnerRegistry ownerRegistry, LineupAlertsState lineupAlertsState) {
        this.jda = jda;
        this.apiClient = apiClient;
        this.scheduler = scheduler;
        this.ownerRegistry = ownerRegistry;
        this.lineupAlertsState = lineupAlertsState;
    }

    public void registerCommands() {
        jda.addEventListener(new LeagueCommand(apiClient));
        jda.addEventListener(new StandingsCommand(apiClient));
        jda.addEventListener(new MatchupCommand(apiClient));
        jda.addEventListener(new RecapCommand(scheduler));
        jda.addEventListener(new LineupCheckCommand(apiClient, ownerRegistry));
        jda.addEventListener(new LineupAlertsCommand(lineupAlertsState));
        jda.addEventListener(new TeamCommand(apiClient));
        jda.addEventListener(new PowerRankingsCommand(apiClient));
        jda.addEventListener(new HeadToHeadCommand(apiClient));

        CommandData[] commandData = {
                LeagueCommand.getCommandData(),
                StandingsCommand.getCommandData(),
                MatchupCommand.getCommandData(),
                RecapCommand.getCommandData(),
                LineupCheckCommand.getCommandData(),
                LineupAlertsCommand.getCommandData(),
                TeamCommand.getCommandData(),
                PowerRankingsCommand.getCommandData(),
                HeadToHeadCommand.getCommandData()
        };

        Long guildId = BotConfig.get().getGuildId();
        Guild devGuild = guildId != null ? jda.getGuildById(guildId) : null;

        if (guildId != null && devGuild == null) {
            log.warn("GUILD_ID {} set but the bot isn't currently in that guild; registering commands globally instead", guildId);
        }

        if (devGuild != null) {
            // Dev mode: register to one guild for instant propagation (global
            // commands take up to an hour), and clear global commands so dev
            // and production registration can never coexist as visible
            // duplicates in Discord's command picker.
            devGuild.updateCommands().addCommands(commandData).queue();
            jda.updateCommands().queue();
            log.info("Registered commands to guild {} for instant propagation (dev mode)", guildId);
        } else {
            jda.updateCommands().addCommands(commandData).queue();
            // Clear any leftover guild-scoped commands on every guild the bot
            // is in, so a stale dev-mode registration can never sit alongside
            // these as a visible duplicate.
            for (Guild guild : jda.getGuilds()) {
                guild.updateCommands().queue();
            }
        }
    }
}
