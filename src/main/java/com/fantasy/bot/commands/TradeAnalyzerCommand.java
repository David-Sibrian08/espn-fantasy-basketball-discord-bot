package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.lineup.LineupHealthChecker;
import com.fantasy.bot.trade.TradeAnalyzerCalculator;
import com.fantasy.bot.trade.TradeAnalyzerCalculator.FitNote;
import com.fantasy.bot.trade.TradeAnalyzerCalculator.PlayerValue;
import com.fantasy.bot.trade.TradeAnalyzerCalculator.TradeResult;
import com.fantasy.bot.util.ErrorReplies;
import com.fantasy.bot.util.TeamLookup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compares the rest-of-season point value of both sides of a proposed
 * trade (up to 3-for-3), adjusted for each team's own positional surplus/
 * deficiency. See TradeAnalyzerCalculator for the actual math.
 */
public class TradeAnalyzerCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TradeAnalyzerCommand.class);
    private static final String[] TEAM1_PLAYER_OPTIONS = {"team1_player1", "team1_player2", "team1_player3"};
    private static final String[] TEAM2_PLAYER_OPTIONS = {"team2_player1", "team2_player2", "team2_player3"};

    private final ESPNApiClient apiClient;

    public TradeAnalyzerCommand(ESPNApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public static CommandData getCommandData() {
        // Discord requires all required options before any optional ones, so
        // the four required fields (both teams + one player each) come
        // first, and the optional extra players (for 2-for-2/3-for-3 trades)
        // come after.
        return Commands.slash("tradeanalyzer", "Compare the rest-of-season value of both sides of a trade")
                .addOption(OptionType.STRING, "team1", "Your team", true, true)
                .addOption(OptionType.STRING, "team1_player1", "Player you're giving up", true, true)
                .addOption(OptionType.STRING, "team2", "The other team", true, true)
                .addOption(OptionType.STRING, "team2_player1", "Player you'd receive", true, true)
                .addOption(OptionType.STRING, "team1_player2", "Another player you're giving up (optional)", false, true)
                .addOption(OptionType.STRING, "team1_player3", "Another player you're giving up (optional)", false, true)
                .addOption(OptionType.STRING, "team2_player2", "Another player you'd receive (optional)", false, true)
                .addOption(OptionType.STRING, "team2_player3", "Another player you'd receive (optional)", false, true);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("tradeanalyzer")) return;

        String focusedName = event.getFocusedOption().getName();
        String typed = event.getFocusedOption().getValue();

        try {
            JsonObject data = apiClient.getLeagueData();

            if (focusedName.equals("team1") || focusedName.equals("team2")) {
                event.replyChoices(TeamLookup.autocompleteChoices(data, typed)).queue();
                return;
            }

            String teamOptionName = focusedName.startsWith("team1_") ? "team1" : "team2";
            OptionMapping teamOption = event.getOption(teamOptionName);
            if (teamOption == null) {
                event.replyChoices(List.of()).queue(); // pick a team first
                return;
            }

            JsonObject team = TeamLookup.findTeam(data.getAsJsonArray("teams"), teamOption.getAsString());
            if (team == null) {
                event.replyChoices(List.of()).queue();
                return;
            }

            event.replyChoices(playerAutocompleteChoices(team, typed)).queue();
        } catch (Exception e) {
            log.error("Failed to build /tradeanalyzer autocomplete choices", e);
            event.replyChoices(List.of()).queue();
        }
    }

    private List<Command.Choice> playerAutocompleteChoices(JsonObject team, String typed) {
        List<Command.Choice> choices = new ArrayList<>();
        String typedLower = typed.toLowerCase();

        if (!team.has("roster")) return choices;
        JsonArray entries = team.getAsJsonObject("roster").getAsJsonArray("entries");
        if (entries == null) return choices;

        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            JsonObject player = entry.getAsJsonObject("playerPoolEntry").getAsJsonObject("player");
            String name = player.has("fullName") ? player.get("fullName").getAsString() : "Unknown Player";
            if (!typedLower.isEmpty() && !name.toLowerCase().contains(typedLower)) continue;

            int position = player.has("defaultPositionId") ? player.get("defaultPositionId").getAsInt() : 0;
            String posName = TradeAnalyzerCalculator.POSITION_NAMES.getOrDefault(position, "?");
            int playerId = entry.get("playerId").getAsInt();

            choices.add(new Command.Choice(name + " (" + posName + ")", String.valueOf(playerId)));
            if (choices.size() >= 25) break;
        }
        return choices;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("tradeanalyzer")) return;

        event.deferReply().queue();

        try {
            String team1Query = event.getOption("team1").getAsString();
            String team2Query = event.getOption("team2").getAsString();

            JsonObject leagueData = apiClient.getLeagueData();
            JsonArray teamsArray = leagueData.getAsJsonArray("teams");

            JsonObject team1 = TeamLookup.findTeam(teamsArray, team1Query);
            JsonObject team2 = TeamLookup.findTeam(teamsArray, team2Query);

            if (team1 == null) {
                event.getHook().sendMessage("❌ No team found matching \"" + team1Query + "\". Try `/league` to see team names.").queue();
                return;
            }
            if (team2 == null) {
                event.getHook().sendMessage("❌ No team found matching \"" + team2Query + "\". Try `/league` to see team names.").queue();
                return;
            }

            int team1Id = team1.get("id").getAsInt();
            int team2Id = team2.get("id").getAsInt();

            if (team1Id == team2Id) {
                event.getHook().sendMessage("❌ Pick two different teams.").queue();
                return;
            }

            List<Integer> team1PlayerIds = collectPlayerIds(event, TEAM1_PLAYER_OPTIONS);
            List<Integer> team2PlayerIds = collectPlayerIds(event, TEAM2_PLAYER_OPTIONS);

            if (team1PlayerIds.isEmpty() || team2PlayerIds.isEmpty()) {
                event.getHook().sendMessage("❌ Pick at least one player from each team.").queue();
                return;
            }

            JsonObject scheduleData = apiClient.getProTeamSchedules();

            TradeResult result = TradeAnalyzerCalculator.analyze(leagueData, scheduleData,
                    team1Id, team1PlayerIds, team2Id, team2PlayerIds, System.currentTimeMillis());

            String name1 = TeamLookup.displayName(team1);
            String name2 = TeamLookup.displayName(team2);

            event.getHook().sendMessageEmbeds(buildEmbed(name1, name2, result).build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage(ErrorReplies.forFailure("analyze trade", e)).queue();
            log.error("Failed to analyze trade", e);
        }
    }

    private List<Integer> collectPlayerIds(SlashCommandInteractionEvent event, String[] optionNames) {
        List<Integer> ids = new ArrayList<>();
        for (String name : optionNames) {
            OptionMapping option = event.getOption(name);
            if (option == null) continue;
            try {
                ids.add(Integer.parseInt(option.getAsString()));
            } catch (NumberFormatException ignored) {
                // stale/invalid autocomplete value - just skip it
            }
        }
        return ids;
    }

    private EmbedBuilder buildEmbed(String name1, String name2, TradeResult result) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🔄 Trade Analyzer")
                .setColor(Color.decode("#9b59b6"));

        embed.addField(name1 + " gives", namesOf(result.team1Gives()), true);
        embed.addField(name2 + " gives", namesOf(result.team2Gives()), true);

        embed.addField("Raw rest-of-season value",
                String.format("You give: **%.0f pts**\nYou get: **%.0f pts**\nGap: **%+.0f pts**",
                        result.team1RawGiven(), result.team1RawReceived(), result.rawGap()),
                false);

        embed.addField("Position-adjusted",
                String.format("You give: **%.0f pts**\nYou get: **%.0f pts**\nGap: **%+.0f pts**",
                        result.team1AdjustedGiven(), result.team1AdjustedReceived(), result.adjustedGap()),
                false);

        if (!result.fitNotes().isEmpty()) {
            String fitLines = result.fitNotes().stream()
                    .map(this::formatFitNote)
                    .collect(Collectors.joining("\n"));
            embed.addField("⚖️ Position fit", fitLines, false);
        }

        String injuryLines = injuryFlags(result);
        if (!injuryLines.isEmpty()) {
            embed.addField("⚠️ Injury flags", injuryLines, false);
        }

        embed.setFooter("Value = season PPG × real games remaining this season");
        return embed;
    }

    private String namesOf(List<PlayerValue> players) {
        if (players.isEmpty()) return "—";
        return players.stream()
                .map(p -> p.name() + " (" + p.positionName() + ")")
                .collect(Collectors.joining("\n"));
    }

    private String formatFitNote(FitNote note) {
        String verb = note.receiving() ? "Getting" : "Giving";
        String description = note.fit() == TradeAnalyzerCalculator.Fit.DEFICIENT
                ? "you're thin at " + note.positionName()
                : "you're stacked at " + note.positionName();
        return verb + " " + note.playerName() + " (" + note.positionName() + ") — " + description;
    }

    private String injuryFlags(TradeResult result) {
        List<String> lines = new ArrayList<>();
        for (PlayerValue p : result.team1Gives()) {
            if (LineupHealthChecker.DEFAULT_FLAGGED_STATUSES.contains(p.injuryStatus())) {
                lines.add(p.name() + " is " + p.injuryStatus());
            }
        }
        for (PlayerValue p : result.team2Gives()) {
            if (LineupHealthChecker.DEFAULT_FLAGGED_STATUSES.contains(p.injuryStatus())) {
                lines.add(p.name() + " is " + p.injuryStatus());
            }
        }
        return String.join("\n", lines);
    }
}
