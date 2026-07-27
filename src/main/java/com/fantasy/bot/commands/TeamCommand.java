package com.fantasy.bot.commands;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.util.ErrorReplies;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TeamCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TeamCommand.class);

    private static final int BENCH_SLOT_ID = 12;
    private static final int IR_SLOT_ID = 13;

    // ESPN's fixed basketball lineup slot IDs (counts are league-configurable, IDs aren't).
    private static final Map<Integer, String> SLOT_LABELS = Map.ofEntries(
            Map.entry(0, "PG"), Map.entry(1, "SG"), Map.entry(2, "SF"), Map.entry(3, "PF"), Map.entry(4, "C"),
            Map.entry(5, "G"), Map.entry(6, "F"), Map.entry(7, "SG/SF"), Map.entry(8, "PG/SG"),
            Map.entry(9, "SF/PF"), Map.entry(10, "PF/C"), Map.entry(11, "UTIL"),
            Map.entry(BENCH_SLOT_ID, "BE"), Map.entry(IR_SLOT_ID, "IR")
    );

    private final ESPNApiClient apiClient;

    public TeamCommand(ESPNApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public static CommandData getCommandData() {
        return Commands.slash("team", "Show a team's current roster")
                .addOption(OptionType.STRING, "name", "Team name", true, true);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("team")) return;

        try {
            JsonObject data = apiClient.getLeagueData();
            event.replyChoices(TeamLookup.autocompleteChoices(data, event.getFocusedOption().getValue())).queue();
        } catch (Exception e) {
            log.error("Failed to build /team autocomplete choices", e);
            event.replyChoices(List.of()).queue();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("team")) return;

        event.deferReply().queue();

        try {
            String query = event.getOption("name").getAsString();
            JsonObject data = apiClient.getLeagueData();
            JsonArray teamsArray = data.getAsJsonArray("teams");

            JsonObject team = TeamLookup.findTeam(teamsArray, query);
            if (team == null) {
                event.getHook().sendMessage("❌ No team found matching \"" + query + "\". Try `/league` to see team names.").queue();
                return;
            }

            event.getHook().sendMessageEmbeds(buildTeamEmbed(team, teamsArray).build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage(ErrorReplies.forFailure("fetch team roster", e)).queue();
            log.error("Failed to fetch team roster", e);
        }
    }

    private EmbedBuilder buildTeamEmbed(JsonObject team, JsonArray allTeams) {
        JsonObject record = team.getAsJsonObject("record").getAsJsonObject("overall");
        int wins = record.get("wins").getAsInt();
        int losses = record.get("losses").getAsInt();
        int ties = record.has("ties") ? record.get("ties").getAsInt() : 0;
        String wl = wins + "-" + losses + (ties > 0 ? "-" + ties : "");

        List<JsonObject> entries = new ArrayList<>();
        JsonObject roster = team.has("roster") ? team.getAsJsonObject("roster") : null;
        if (roster != null && roster.has("entries")) {
            JsonArray rosterEntries = roster.getAsJsonArray("entries");
            for (int i = 0; i < rosterEntries.size(); i++) {
                entries.add(rosterEntries.get(i).getAsJsonObject());
            }
        }

        List<JsonObject> starters = new ArrayList<>();
        List<JsonObject> bench = new ArrayList<>();
        List<JsonObject> ir = new ArrayList<>();
        for (JsonObject entry : entries) {
            int slot = slotId(entry);
            if (slot == BENCH_SLOT_ID) bench.add(entry);
            else if (slot == IR_SLOT_ID) ir.add(entry);
            else starters.add(entry);
        }
        starters.sort(Comparator.comparingInt(this::slotId));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🏀 " + TeamLookup.displayName(team))
                .setColor(Color.ORANGE)
                .addField("Record", wl, true)
                .addField("Rank", "#" + computeRank(team, allTeams), true)
                .addField("Starters", rosterBlock(starters), false);

        if (!bench.isEmpty()) embed.addField("Bench", rosterBlock(bench), false);
        if (!ir.isEmpty()) embed.addField("IR", rosterBlock(ir), false);

        return embed;
    }

    private int slotId(JsonObject entry) {
        return entry.has("lineupSlotId") ? entry.get("lineupSlotId").getAsInt() : -1;
    }

    private String rosterBlock(List<JsonObject> entries) {
        if (entries.isEmpty()) return "```txt\n—\n```";

        int slotW = 4;
        int nameW = 4;
        for (JsonObject e : entries) {
            slotW = Math.max(slotW, slotLabel(slotId(e)).length());
            nameW = Math.max(nameW, playerName(e).length());
        }

        StringBuilder sb = new StringBuilder("```txt\n");
        for (JsonObject e : entries) {
            sb.append(String.format("%-" + slotW + "s  %-" + nameW + "s%s%n",
                    slotLabel(slotId(e)), playerName(e), injuryFlag(e)));
        }
        sb.append("```");
        return sb.toString();
    }

    private String slotLabel(int id) {
        return SLOT_LABELS.getOrDefault(id, "Slot " + id);
    }

    private String playerName(JsonObject entry) {
        JsonObject player = player(entry);
        return player != null && player.has("fullName") ? player.get("fullName").getAsString() : "Unknown Player";
    }

    private String injuryFlag(JsonObject entry) {
        JsonObject player = player(entry);
        String status = player != null && player.has("injuryStatus") && !player.get("injuryStatus").isJsonNull()
                ? player.get("injuryStatus").getAsString() : "ACTIVE";
        return "ACTIVE".equals(status) ? "" : "  ⚠️ " + status;
    }

    private JsonObject player(JsonObject entry) {
        if (!entry.has("playerPoolEntry")) return null;
        JsonObject ppe = entry.getAsJsonObject("playerPoolEntry");
        return ppe.has("player") ? ppe.getAsJsonObject("player") : null;
    }

    private int computeRank(JsonObject team, JsonArray allTeams) {
        double thisPct = team.getAsJsonObject("record").getAsJsonObject("overall").get("percentage").getAsDouble();

        List<Double> percentages = new ArrayList<>();
        for (int i = 0; i < allTeams.size(); i++) {
            JsonObject t = allTeams.get(i).getAsJsonObject();
            percentages.add(t.getAsJsonObject("record").getAsJsonObject("overall").get("percentage").getAsDouble());
        }
        percentages.sort(Comparator.reverseOrder());
        return percentages.indexOf(thisPct) + 1;
    }
}
