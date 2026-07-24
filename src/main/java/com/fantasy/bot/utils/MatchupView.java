package com.fantasy.bot.utils;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MatchupView {
    private final ESPNApiClient apiClient;
    private int currentWeek;
    private final int originalWeek;
    private final int maxWeek;

    public MatchupView(ESPNApiClient apiClient, int currentWeek, int originalWeek, int maxWeek) {
        this.apiClient = apiClient;
        this.currentWeek = currentWeek;
        this.originalWeek = originalWeek;
        this.maxWeek = maxWeek;
    }

    public List<MessageEmbed> createEmbeds(JsonObject data) {
        Map<Integer, JsonObject> teams = new HashMap<>();
        JsonArray teamsArray = data.getAsJsonArray("teams");
        for (int i = 0; i < teamsArray.size(); i++) {
            JsonObject team = teamsArray.get(i).getAsJsonObject();
            teams.put(team.get("id").getAsInt(), team);
        }

        JsonArray scheduleData = data.getAsJsonArray("schedule");
        if (scheduleData == null) {
            EmbedBuilder err = new EmbedBuilder()
                    .setTitle("🏀 Week " + currentWeek + " Head-to-Head Matchups • 0 game(s)")
                    .setColor(Color.BLUE)
                    .setDescription("📊 Weekly fantasy results");
            return List.of(err.build());
        }

        // --- records entering this week (accurate "at time of matchup") ---
        Map<Integer, WLT> recordEnteringWeek = computeRecordEnteringWeek(scheduleData, currentWeek);

        // Filter games for this week
        List<JsonObject> games = new ArrayList<>();
        for (int i = 0; i < scheduleData.size(); i++) {
            JsonObject m = scheduleData.get(i).getAsJsonObject();
            if (m == null || !m.has("matchupPeriodId")) continue;
            if (m.get("matchupPeriodId").getAsInt() != currentWeek) continue;
            if (!m.has("home") || !m.has("away")) continue;
            games.add(m);
        }

        // Header embed (like TS)
        String seasonRaw = BotConfig.get().getEspnSeasonId();
        EmbedBuilder header = new EmbedBuilder()
                .setTitle("Week " + currentWeek + " Head-to-Head Matchups • " + games.size() + " game(s)")
                .setColor(Color.BLUE)
                .setDescription("📊 Weekly fantasy results")
                .setFooter("Season " + seasonRaw + " • Week " + currentWeek);

        // Build one embed per game
        List<MessageEmbed> embeds = new ArrayList<>();
        embeds.add(header.build());

        for (JsonObject matchup : games) {
            JsonObject home = matchup.getAsJsonObject("home");
            JsonObject away = matchup.getAsJsonObject("away");
            if (home == null || away == null) continue;

            int homeId = home.get("teamId").getAsInt();
            int awayId = away.get("teamId").getAsInt();

            JsonObject homeTeam = teams.get(homeId);
            JsonObject awayTeam = teams.get(awayId);

            String homeName = homeTeam != null ? displayName(homeTeam) : ("Team " + homeId);
            String awayName = awayTeam != null ? displayName(awayTeam) : ("Team " + awayId);

            WLT homeWlt = recordEnteringWeek.getOrDefault(homeId, new WLT());
            WLT awayWlt = recordEnteringWeek.getOrDefault(awayId, new WLT());

            String homeLabel = homeName + " (" + homeWlt.toDisplay() + ")";
            String awayLabel = awayName + " (" + awayWlt.toDisplay() + ")";

            double homePtsNum = (home.has("totalPoints") && !home.get("totalPoints").isJsonNull())
                    ? home.get("totalPoints").getAsDouble() : 0.0;
            double awayPtsNum = (away.has("totalPoints") && !away.get("totalPoints").isJsonNull())
                    ? away.get("totalPoints").getAsDouble() : 0.0;

            String homeScore = String.format("%.2f", homePtsNum);
            String awayScore = String.format("%.2f", awayPtsNum);

            // Align like TS: fixed name column, scores right-aligned
            int nameW = Math.max(homeLabel.length(), awayLabel.length());
            String lineAway = String.format("%-" + nameW + "s  %7s", awayLabel, awayScore);
            String lineHome = String.format("%-" + nameW + "s  %7s", homeLabel, homeScore);

            String body = "```txt\n" + lineAway + "\n" + lineHome + "\n```";

            // Winner logic: prefer ESPN winner if present, else fallback (past weeks)
            String winnerName = null;
            String winnerScore = null;

            if (matchup.has("winner") && !matchup.get("winner").isJsonNull()) {
                String w = matchup.get("winner").getAsString();
                if ("HOME".equalsIgnoreCase(w)) { winnerName = homeName; winnerScore = homeScore; }
                else if ("AWAY".equalsIgnoreCase(w)) { winnerName = awayName; winnerScore = awayScore; }
                else if ("TIE".equalsIgnoreCase(w)) { winnerName = "Tie"; winnerScore = null; }
            }

            // fallback if earlier week and ESPN didn't set winner
            if (winnerName == null && currentWeek < originalWeek) {
                if (homePtsNum != awayPtsNum) {
                    boolean homeWon = homePtsNum > awayPtsNum;
                    winnerName = homeWon ? homeName : awayName;
                    winnerScore = homeWon ? homeScore : awayScore;
                } else {
                    winnerName = "Tie";
                    winnerScore = null;
                }
            }

            if (winnerName != null) {
                body += "\n🏆 **Winner:** " + winnerName + (winnerScore != null ? " (" + winnerScore + ")" : "");
            }

            EmbedBuilder game = new EmbedBuilder()
                    .setColor(Color.BLUE)
                    .setDescription(body);

            embeds.add(game.build());
        }

        // Discord max 10 embeds per message. If you might exceed that, you’ll need paging.
        // For now, we’ll send up to 10 (header + 9 games).
        int limit = Math.min(10, embeds.size());
        return embeds.subList(0, limit);
    }

    public Button getPrevButton() {
        return Button.secondary("matchup_prev", "◀ Prev").withDisabled(currentWeek <= 1);
    }

    public Button getResetButton() {
        return Button.primary("matchup_reset", "Reset");
    }

    public Button getNextButton() {
        return Button.secondary("matchup_next", "Next ▶").withDisabled(currentWeek >= maxWeek);
    }

    public StringSelectMenu getWeekSelect() {
        StringSelectMenu.Builder menu = StringSelectMenu.create("matchup_week_select")
                .setPlaceholder("Jump to week...");

        for (int i = 1; i <= maxWeek; i++) {
            String label = (i == currentWeek ? "▶ Week " : "Week ") + i;
            menu.addOption(label, String.valueOf(i));
        }
        return menu.build();
    }

    public void previousWeek() {
        if (currentWeek > 1) {
            currentWeek--;
        }
    }

    public void nextWeek() {
        if (currentWeek < maxWeek) {
            currentWeek++;
        }
    }

    public void resetWeek() {
        currentWeek = originalWeek;
    }

    public void setWeek(int week) {
        if (week >= 1 && week <= maxWeek) {
            currentWeek = week;
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String s = obj.get(key).getAsString();
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        String s = (a + " " + b).trim();
        return s.isEmpty() ? null : s;
    }

    private static String displayName(JsonObject team) {
        String name = getString(team, "name");
        if (name != null) return name;

        String loc = getString(team, "location");
        String nick = getString(team, "nickname");
        String locNick = joinNonBlank(loc, nick);
        if (locNick != null) return locNick;

        String teamLoc = getString(team, "teamLocation");
        String teamNick = getString(team, "teamNickname");
        String teamLocNick = joinNonBlank(teamLoc, teamNick);
        if (teamLocNick != null) return teamLocNick;

        if (loc != null) return loc;
        if (nick != null) return nick;
        if (teamLoc != null) return teamLoc;
        if (teamNick != null) return teamNick;

        String abbrev = getString(team, "abbrev");
        if (abbrev != null) return abbrev;

        int id = team.has("id") && !team.get("id").isJsonNull() ? team.get("id").getAsInt() : -1;
        return id > 0 ? ("Team " + id) : "Team";
    }

    private static class WLT {
        int w, l, t;
        String toDisplay() {
            if (t > 0) return w + "-" + l + "-" + t;
            return w + "-" + l;
        }
    }

    private static Map<Integer, WLT> computeRecordEnteringWeek(JsonArray schedule, int week) {
        Map<Integer, WLT> map = new HashMap<>();
        if (schedule == null) return map;

        for (int i = 0; i < schedule.size(); i++) {
            JsonObject m = schedule.get(i).getAsJsonObject();
            if (m == null || !m.has("matchupPeriodId") || m.get("matchupPeriodId").isJsonNull()) continue;

            int matchupWeek = m.get("matchupPeriodId").getAsInt();
            if (matchupWeek >= week) continue; // only weeks before the selected week

            JsonObject home = m.has("home") && m.get("home").isJsonObject() ? m.getAsJsonObject("home") : null;
            JsonObject away = m.has("away") && m.get("away").isJsonObject() ? m.getAsJsonObject("away") : null;
            if (home == null || away == null) continue;

            if (!home.has("teamId") || !away.has("teamId")) continue;

            int homeId = home.get("teamId").getAsInt();
            int awayId = away.get("teamId").getAsInt();

            double homePts = (home.has("totalPoints") && !home.get("totalPoints").isJsonNull()) ? home.get("totalPoints").getAsDouble() : 0.0;
            double awayPts = (away.has("totalPoints") && !away.get("totalPoints").isJsonNull()) ? away.get("totalPoints").getAsDouble() : 0.0;

            // If it doesn't look played yet, skip (prevents future weeks / placeholders affecting record)
            if (homePts <= 0.0 && awayPts <= 0.0) continue;

            map.computeIfAbsent(homeId, k -> new WLT());
            map.computeIfAbsent(awayId, k -> new WLT());

            if (homePts > awayPts) {
                map.get(homeId).w++;
                map.get(awayId).l++;
            } else if (awayPts > homePts) {
                map.get(awayId).w++;
                map.get(homeId).l++;
            } else {
                map.get(homeId).t++;
                map.get(awayId).t++;
            }
        }

        return map;
    }
}
