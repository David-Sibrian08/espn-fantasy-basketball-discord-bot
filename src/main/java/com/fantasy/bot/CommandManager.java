package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.commands.*;
import com.fantasy.bot.lineup.TeamOwnerRegistry;
import net.dv8tion.jda.api.JDA;

public class CommandManager {
    private final JDA jda;
    private final ESPNApiClient apiClient;
    private final WeeklyRecapScheduler scheduler;
    private final TeamOwnerRegistry ownerRegistry;

    public CommandManager(JDA jda, ESPNApiClient apiClient, WeeklyRecapScheduler scheduler, TeamOwnerRegistry ownerRegistry) {
        this.jda = jda;
        this.apiClient = apiClient;
        this.scheduler = scheduler;
        this.ownerRegistry = ownerRegistry;
    }

    public void registerCommands() {
        jda.addEventListener(new LeagueCommand(apiClient));
        jda.addEventListener(new StandingsCommand(apiClient));
        jda.addEventListener(new MatchupCommand(apiClient));
        jda.addEventListener(new RecapCommand(scheduler));
        jda.addEventListener(new LineupCheckCommand(apiClient, ownerRegistry));

        // Update commands globally
        jda.updateCommands().addCommands(
                LeagueCommand.getCommandData(),
                StandingsCommand.getCommandData(),
                MatchupCommand.getCommandData(),
                RecapCommand.getCommandData(),
                LineupCheckCommand.getCommandData()
        ).queue();
    }
}
