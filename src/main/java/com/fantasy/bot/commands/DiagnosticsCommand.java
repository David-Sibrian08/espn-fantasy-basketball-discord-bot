package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.fantasy.bot.lineup.LineupAlertsState;
import com.fantasy.bot.lineup.TeamOwnerRegistry;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.awt.Color;

/**
 * Self-check for people self-hosting this bot: is ESPN reachable, are the
 * optional channels/files configured correctly, is the bot running in dev
 * mode by accident. Meant to be the first thing to run after setup, and the
 * first thing to check when something isn't working.
 */
public class DiagnosticsCommand extends ListenerAdapter {
    private final ESPNApiClient apiClient;
    private final TeamOwnerRegistry ownerRegistry;
    private final LineupAlertsState lineupAlertsState;

    public DiagnosticsCommand(ESPNApiClient apiClient, TeamOwnerRegistry ownerRegistry, LineupAlertsState lineupAlertsState) {
        this.apiClient = apiClient;
        this.ownerRegistry = ownerRegistry;
        this.lineupAlertsState = lineupAlertsState;
    }

    public static CommandData getCommandData() {
        return Commands.slash("diagnostics", "Check your bot's configuration and ESPN connectivity")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("diagnostics")) return;

        event.deferReply(true).queue(); // ephemeral - config details aren't for the whole channel

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🩺 Bot Diagnostics")
                .setColor(Color.CYAN)
                .addField("ESPN API", checkEspnApi(), false)
                .addField("Team Owners (team_owners.json)", checkTeamOwners(), false)
                .addField("Weekly Recap", checkOptionalChannel(event.getJDA(), BotConfig.get().getRecapChannelId(), null), false)
                .addField("Lineup Health Alerts", checkLineupAlerts(event.getJDA()), false)
                .addField("Command Registration", checkCommandRegistration(), false);

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private String checkEspnApi() {
        try {
            JsonObject data = apiClient.getLeagueData();
            JsonObject settings = data.getAsJsonObject("settings");
            String name = settings.get("name").getAsString();
            int size = settings.get("size").getAsInt();
            return "✅ Connected — **" + name + "** (" + size + " teams)";
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    private String checkTeamOwners() {
        int count = ownerRegistry.configuredOwnerCount();
        if (count == 0) {
            return "⚪ Not configured — lineup alerts won't be able to @mention owners. See `team_owners.example.json`.";
        }
        return "✅ " + count + " team(s) mapped to a Discord user";
    }

    private String checkLineupAlerts(JDA jda) {
        Long channelId = BotConfig.get().getLineupAlertChannelId();
        String status = checkOptionalChannel(jda, channelId, "set LINEUP_ALERT_CHANNEL_ID to enable");
        if (channelId != null) {
            status += lineupAlertsState.isEnabled()
                    ? " (alerts **on**)"
                    : " (alerts **off** — `/lineupalerts enabled:true`)";
        }
        return status;
    }

    private String checkOptionalChannel(JDA jda, Long channelId, String notConfiguredHint) {
        if (channelId == null) {
            return "⚪ Not configured (optional)" + (notConfiguredHint != null ? " — " + notConfiguredHint : "");
        }

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return "❌ Channel ID `" + channelId + "` not found — is the bot still in that server, or was the channel deleted?";
        }

        boolean canSend = channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_SEND);
        if (!canSend) {
            return "⚠️ Found #" + channel.getName() + " but missing **Send Messages** permission there";
        }

        return "✅ #" + channel.getName();
    }

    private String checkCommandRegistration() {
        Long guildId = BotConfig.get().getGuildId();
        if (guildId != null) {
            return "⚠️ Dev mode — commands registered only to guild `" + guildId + "`. Unset `GUILD_ID` before running in production.";
        }
        return "✅ Global (production mode)";
    }
}
