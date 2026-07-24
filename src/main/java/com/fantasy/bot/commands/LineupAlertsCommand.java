package com.fantasy.bot.commands;

import com.fantasy.bot.lineup.LineupAlertsState;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class LineupAlertsCommand extends ListenerAdapter {
    private final LineupAlertsState state;

    public LineupAlertsCommand(LineupAlertsState state) {
        this.state = state;
    }

    public static CommandData getCommandData() {
        return Commands.slash("lineupalerts", "Turn automatic lineup health alerts on or off")
                .addOption(OptionType.BOOLEAN, "enabled", "true to turn on, false to turn off", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("lineupalerts")) return;

        boolean enabled = event.getOption("enabled").getAsBoolean();
        state.setEnabled(enabled);

        event.reply(enabled
                ? "✅ Lineup health alerts are now **on**."
                : "🔕 Lineup health alerts are now **off**.").queue();
    }
}
