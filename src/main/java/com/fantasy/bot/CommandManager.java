package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.commands.*;
import net.dv8tion.jda.api.JDA;

public class CommandManager {
    private final JDA jda;
    private final ESPNApiClient apiClient;
    private final WeeklyRecapScheduler scheduler;

    public CommandManager(JDA jda, ESPNApiClient apiClient, WeeklyRecapScheduler scheduler) {
        this.jda = jda;
        this.apiClient = apiClient;
        this.scheduler = scheduler;
    }

    public void registerCommands() {
        jda.addEventListener(new LeagueCommand(apiClient));
        jda.addEventListener(new StandingsCommand(apiClient));
        jda.addEventListener(new MatchupCommand(apiClient));
        jda.addEventListener(new RecapCommand(scheduler));

        // Update commands globally
        jda.updateCommands().addCommands(
                LeagueCommand.getCommandData(),
                StandingsCommand.getCommandData(),
                MatchupCommand.getCommandData(),
                RecapCommand.getCommandData()
        ).queue();
    }
}
