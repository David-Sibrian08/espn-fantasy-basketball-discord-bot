package com.fantasy.bot.commands;

import com.fantasy.bot.WeeklyRecapScheduler;
import com.fantasy.bot.util.ErrorReplies;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecapCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RecapCommand.class);

    private final WeeklyRecapScheduler recap;

    public static CommandData getCommandData() {
        return Commands.slash("recap", "Post the weekly recap (accolades + record watch)")
                .addOption(OptionType.INTEGER, "week", "Week to recap (defaults to last completed)", false);
    }

    public RecapCommand(WeeklyRecapScheduler recap) {
        this.recap = recap;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("recap")) return;

        event.deferReply(true).queue(); // ephemeral acknowledgment

        Integer weekOpt = event.getOption("week") != null ? event.getOption("week").getAsInt() : null;

        try {
            TextChannel channel = event.getChannel().asTextChannel();
            // Posting is asynchronous, so only report success once it's actually
            // confirmed - not just because runNow() returned control back to us.
            recap.runNow(channel, weekOpt,
                    () -> event.getHook().sendMessage("✅ Recap posted.").queue(),
                    reason -> event.getHook().sendMessage("❌ Failed to post recap: " + reason).queue());
        } catch (Exception e) {
            event.getHook().sendMessage(ErrorReplies.forFailure("post recap", e)).queue();
            log.error("Failed to post recap", e);
        }
    }
}
