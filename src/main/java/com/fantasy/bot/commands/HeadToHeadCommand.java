package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.fantasy.bot.headtohead.HeadToHeadCalculator;
import com.fantasy.bot.headtohead.HeadToHeadCalculator.HeadToHeadRecord;
import com.fantasy.bot.util.TeamLookup;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class HeadToHeadCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HeadToHeadCommand.class);

    private final ESPNApiClient apiClient;

    public HeadToHeadCommand(ESPNApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public static CommandData getCommandData() {
        return Commands.slash("headtohead", "All-time series record between two teams")
                .addOption(OptionType.STRING, "team1", "First team", true, true)
                .addOption(OptionType.STRING, "team2", "Second team", true, true);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("headtohead")) return;

        try {
            JsonObject data = apiClient.getLeagueData();
            event.replyChoices(TeamLookup.autocompleteChoices(data, event.getFocusedOption().getValue())).queue();
        } catch (Exception e) {
            log.error("Failed to build /headtohead autocomplete choices", e);
            event.replyChoices(List.of()).queue();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("headtohead")) return;

        event.deferReply().queue();

        try {
            String query1 = event.getOption("team1").getAsString();
            String query2 = event.getOption("team2").getAsString();

            JsonObject currentData = apiClient.getLeagueData();
            JsonArray teamsArray = currentData.getAsJsonArray("teams");

            JsonObject teamA = TeamLookup.findTeam(teamsArray, query1);
            JsonObject teamB = TeamLookup.findTeam(teamsArray, query2);

            if (teamA == null) {
                event.getHook().sendMessage("❌ No team found matching \"" + query1 + "\". Try `/league` to see team names.").queue();
                return;
            }
            if (teamB == null) {
                event.getHook().sendMessage("❌ No team found matching \"" + query2 + "\". Try `/league` to see team names.").queue();
                return;
            }

            int teamAId = teamA.get("id").getAsInt();
            int teamBId = teamB.get("id").getAsInt();

            if (teamAId == teamBId) {
                event.getHook().sendMessage("❌ Pick two different teams.").queue();
                return;
            }

            int firstSeason = BotConfig.get().getFirstSeasonId();
            int currentSeasonId = Integer.parseInt(BotConfig.get().getEspnSeasonId());

            List<JsonObject> seasons = new ArrayList<>();
            for (int season = firstSeason; season <= currentSeasonId; season++) {
                try {
                    seasons.add(apiClient.getLeagueData(season));
                } catch (Exception e) {
                    log.warn("Failed to fetch season {} for head-to-head, skipping", season, e);
                }
            }

            HeadToHeadRecord record = HeadToHeadCalculator.compute(seasons, teamAId, teamBId);
            String nameA = TeamLookup.displayName(teamA);
            String nameB = TeamLookup.displayName(teamB);

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("🆚 " + nameA + " vs " + nameB)
                    .setColor(Color.decode("#1a1a1a"))
                    .setFooter("All-time series • since " + firstSeason);

            if (record.gamesPlayed() == 0) {
                embed.setDescription("These teams haven't played each other yet.");
            } else {
                embed.addField("Series Record",
                        nameA + " " + record.teamAWins() + " — " + record.teamBWins() + " " + nameB +
                                (record.ties() > 0 ? " (" + record.ties() + " tie" + (record.ties() > 1 ? "s" : "") + ")" : ""),
                        false);

                double avgA = record.teamATotalPoints() / record.gamesPlayed();
                double avgB = record.teamBTotalPoints() / record.gamesPlayed();
                embed.addField("Avg Score", String.format("%s: %.1f — %s: %.1f", nameA, avgA, nameB, avgB), false);

                record.biggestBlowout().ifPresent(g -> {
                    String winner = g.teamAPoints() > g.teamBPoints() ? nameA : nameB;
                    embed.addField("Biggest Blowout",
                            winner + " by " + String.format("%.1f", g.margin()) + " (Week " + g.week() + ", " + g.season() + ")",
                            false);
                });

                record.mostRecent().ifPresent(g -> {
                    String winner = g.teamAPoints() == g.teamBPoints() ? "Tie" : (g.teamAPoints() > g.teamBPoints() ? nameA : nameB);
                    embed.addField("Most Recent Matchup",
                            "Week " + g.week() + ", " + g.season() + " — " + winner + " won " +
                                    String.format("%.1f - %.1f", g.teamAPoints(), g.teamBPoints()),
                            false);
                });
            }

            event.getHook().sendMessageEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Failed to compute head-to-head record.").queue();
            log.error("Failed to compute head-to-head record", e);
        }
    }
}
