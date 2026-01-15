package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.utils.MatchupView;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.HashMap;
import java.util.Map;

public class MatchupCommand extends ListenerAdapter {
    private final ESPNApiClient apiClient = new ESPNApiClient();
    private final Map<String, MatchupView> activeViews = new HashMap<>();

    public static CommandData getCommandData() {
        return Commands.slash("matchup", "Show league matchups for a week")
                .addOption(OptionType.INTEGER, "week", "Specify a scoring period/week", false);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("matchup")) return;

        event.deferReply().queue();

        try {
            JsonObject data = apiClient.getLeagueData();

            // Get current week from scoringPeriodId
            Integer currentWeek = null;

            if (data.has("status") && data.get("status").isJsonObject()) {
                JsonObject status = data.getAsJsonObject("status");
                if (status.has("currentMatchupPeriod") && !status.get("currentMatchupPeriod").isJsonNull()) {
                    currentWeek = status.get("currentMatchupPeriod").getAsInt();
                }
            }

            if (currentWeek == null && data.has("scoringPeriodId") && !data.get("scoringPeriodId").isJsonNull()) {
                currentWeek = data.get("scoringPeriodId").getAsInt();
            }

            if (currentWeek == null) {
                currentWeek = 1; // Fallback
            }

            int maxWeek = currentWeek;

            Integer weekOption = event.getOption("week") != null ?
                    event.getOption("week").getAsInt() : null;
            int selectedWeek = weekOption != null ? weekOption : currentWeek;

            MatchupView view = new MatchupView(apiClient, selectedWeek, currentWeek, maxWeek);

            event.getHook().sendMessageEmbeds(view.createEmbeds(data))
                    .addActionRow(view.getPrevButton(), view.getResetButton(), view.getNextButton())
                    .addActionRow(view.getWeekSelect())
                    .queue(message -> {
                        // Store the view with the message ID for later button handling
                        activeViews.put(message.getId(), view);
                    });

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Failed to fetch matchup data.").queue();
            e.printStackTrace();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String messageId = event.getMessageId();
        MatchupView view = activeViews.get(messageId);

        if (view == null) return;

        String buttonId = event.getComponentId();

        try {
            if (buttonId.equals("matchup_prev")) {
                view.previousWeek();
            } else if (buttonId.equals("matchup_next")) {
                view.nextWeek();
            } else if (buttonId.equals("matchup_reset")) {
                view.resetWeek();
            } else {
                return;
            }

            event.deferEdit().queue();

            JsonObject data = apiClient.getLeagueData();
            event.getHook().editOriginalEmbeds(view.createEmbeds(data))
                    .setActionRow(view.getPrevButton(), view.getResetButton(), view.getNextButton())
                    .setActionRow(view.getWeekSelect())
                    .queue();

        } catch (Exception e) {
            event.reply("❌ Failed to update matchup.").setEphemeral(true).queue();
            e.printStackTrace();
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals("matchup_week_select")) return;

        String messageId = event.getMessageId();
        MatchupView view = activeViews.get(messageId);

        if (view == null) return;

        try {
            int selectedWeek = Integer.parseInt(event.getValues().get(0));
            view.setWeek(selectedWeek);

            event.deferEdit().queue();

            JsonObject data = apiClient.getLeagueData();
            event.getHook().editOriginalEmbeds(view.createEmbeds(data))
                    .setActionRow(view.getPrevButton(), view.getResetButton(), view.getNextButton())
                    .setActionRow(view.getWeekSelect())
                    .queue();

        } catch (Exception e) {
            event.reply("❌ Failed to update matchup.").setEphemeral(true).queue();
            e.printStackTrace();
        }
    }
}