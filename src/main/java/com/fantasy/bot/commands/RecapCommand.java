package com.fantasy.bot.commands;

import com.fantasy.bot.WeeklyRecapScheduler;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class RecapCommand extends ListenerAdapter {
    private final WeeklyRecapScheduler recap;

    public static CommandData getCommandData() {
        return Commands.slash("recap", "Post the weekly recap (accolades + record watch)")
                .addOption(OptionType.INTEGER, "week", "Week to recap (defaults to last completed)", false);
    }

    public RecapCommand(JDA jda) {
        this.recap = new WeeklyRecapScheduler(jda);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("recap")) return;

        event.deferReply(true).queue(); // ephemeral acknowledgment

        Integer weekOpt = event.getOption("week") != null ? event.getOption("week").getAsInt() : null;

        try {
            TextChannel channel = event.getChannel().asTextChannel();
            recap.runNow(channel, weekOpt);     // posts into the channel where the command was used
            event.getHook().sendMessage("✅ Recap posted.").queue();
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Failed to post recap.").queue();
            e.printStackTrace();
        }
    }
}