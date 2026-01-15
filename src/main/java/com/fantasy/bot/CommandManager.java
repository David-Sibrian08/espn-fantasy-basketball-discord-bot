package com.fantasy.bot;

import com.fantasy.bot.commands.*;
import net.dv8tion.jda.api.JDA;

public class CommandManager {
    private final JDA jda;

    public CommandManager(JDA jda) {
        this.jda = jda;
    }

    public void registerCommands() {
        jda.addEventListener(new LeagueCommand());
        jda.addEventListener(new StandingsCommand());
        jda.addEventListener(new MatchupCommand());
        jda.addEventListener(new RecapCommand(jda));

        // Update commands globally
        jda.updateCommands().addCommands(
                LeagueCommand.getCommandData(),
                StandingsCommand.getCommandData(),
                MatchupCommand.getCommandData(),
                RecapCommand.getCommandData()
        ).queue();
    }
}