package com.fantasy.bot.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.awt.Color;

public class HelpCommand extends ListenerAdapter {

    public static CommandData getCommandData() {
        return Commands.slash("help", "List available commands and what they do");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("help")) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🏀 Bot Commands")
                .setColor(Color.ORANGE)
                .addField("League",
                        "`/league` — League name, season, current week, team IDs\n" +
                        "`/standings` — Current league standings",
                        false)
                .addField("Matchups",
                        "`/matchup [week]` — Matchups for a week, with box scores\n" +
                        "`/recap [week]` — Weekly recap (accolades + record watch)\n" +
                        "`/powerrankings [week]` — All-play record power rankings\n" +
                        "`/headtohead <team1> <team2>` — All-time series record between two teams",
                        false)
                .addField("Teams",
                        "`/team <name>` — A team's current roster",
                        false)
                .addField("Lineup Health",
                        "`/lineupcheck` — Check for OUT/DOUBTFUL players in a starting lineup slot right now\n" +
                        "`/lineupalerts <enabled>` — Turn automatic lineup health alerts on or off (requires Manage Server)",
                        false);

        event.replyEmbeds(embed.build()).queue();
    }
}
