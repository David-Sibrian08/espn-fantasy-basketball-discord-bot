package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.commands.*;
import com.fantasy.bot.lineup.LineupAlertsState;
import com.fantasy.bot.lineup.TeamOwnerRegistry;
import net.dv8tion.jda.api.JDA;

public class CommandManager {
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

        // Update commands globally
        jda.updateCommands().addCommands(
                LeagueCommand.getCommandData(),
                StandingsCommand.getCommandData(),
                MatchupCommand.getCommandData(),
                RecapCommand.getCommandData(),
                LineupCheckCommand.getCommandData(),
                LineupAlertsCommand.getCommandData(),
                TeamCommand.getCommandData(),
                PowerRankingsCommand.getCommandData(),
                HeadToHeadCommand.getCommandData()
        ).queue();
    }
}
