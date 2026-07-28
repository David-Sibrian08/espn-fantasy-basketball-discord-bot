package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class WeeklyRecapScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeeklyRecapScheduler.class);

    private final JDA jda;
    private final ESPNApiClient apiClient;
    private final ScheduledExecutorService scheduler;
    private final long recapChannelId;

    // All-time record storage (so you don’t have to recompute every time)
    private static final Path ALL_TIME_FILE = Path.of("all_time_records.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public WeeklyRecapScheduler(JDA jda, ESPNApiClient apiClient) {
        this.jda = jda;
        this.apiClient = apiClient;
        this.scheduler = Executors.newScheduledThreadPool(1);

        Long channelId = BotConfig.get().getRecapChannelId();
        this.recapChannelId = channelId != null ? channelId : 0L;
    }

    // For later, when you actually schedule it
    public void start() {
        if (recapChannelId == 0) {
            log.info("RECAP_CHANNEL_ID not set. Weekly recaps disabled.");
            return;
        }

        long initialDelay = calculateDelayUntilNextMonday();

        scheduler.scheduleAtFixedRate(
                this::sendWeeklyRecap,
                initialDelay,
                TimeUnit.DAYS.toMinutes(7),
                TimeUnit.MINUTES
        );

        log.info("Weekly recap scheduler started!");
    }

    // Manual call to a specific channel / week, with success/failure callbacks
    // since the actual Discord send is asynchronous - without these, a
    // caller has no way to know whether the message really went out (e.g.
    // the bot lacking Send Messages/Embed Links in that channel) versus just
    // having been handed off to JDA.
    public void runNow(TextChannel channel, Integer weekOverride, Runnable onSuccess, Consumer<String> onFailure) {
        sendWeeklyRecapToChannel(channel, weekOverride, onSuccess, onFailure);
    }

    private long calculateDelayUntilNextMonday() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        LocalDateTime nextMonday = now.with(DayOfWeek.MONDAY)
                .withHour(9)
                .withMinute(0)
                .withSecond(0);

        if (now.isAfter(nextMonday)) {
            nextMonday = nextMonday.plusWeeks(1);
        }

        return ChronoUnit.MINUTES.between(now, nextMonday);
    }

    private void sendWeeklyRecap() {
        TextChannel channel = jda.getTextChannelById(recapChannelId);
        if (channel == null) return;

        sendWeeklyRecapToChannel(channel, null,
                () -> log.info("Weekly recap posted successfully"),
                reason -> log.warn("Weekly recap failed: {}", reason));
    }

    private void sendWeeklyRecapToChannel(TextChannel channel, Integer weekOverride, Runnable onSuccess, Consumer<String> onFailure) {
        try {
            int currentSeasonId = getCurrentSeasonId();
            JsonObject data = apiClient.getLeagueData(); // current season
            Integer currentWeek = getCurrentWeek(data);

            if (currentWeek == null) currentWeek = 1;

            int targetWeek = (weekOverride != null) ? weekOverride : (currentWeek - 1);
            if (targetWeek < 1) targetWeek = 1;

            // Build teamId -> display name
            Map<Integer, String> teams = buildTeamNameMap(data);

            // Grab matchups for target week
            List<JsonObject> weekMatchups = getMatchupsForWeek(data, targetWeek);
            if (weekMatchups.isEmpty()) {
                onFailure.accept("No completed matchups found for week " + targetWeek + ".");
                return;
            }

            // Weekly results (for week recap)
            WeeklyWinners weekly = computeWeeklyWinners(weekMatchups, teams);

            // Season counts through targetWeek
            SeasonAccolades seasonAccolades = computeSeasonAccolades(data, teams, targetWeek);

            // All-time record book (load cached; if missing, build once from ESPN)
            AllTimeRecordBook allTime = loadAllTimeRecordBook();
            if (!allTime.isInitialized()) {
                allTime = buildAllTimeRecordBookFromEspn(currentSeasonId);
                saveAllTimeRecordBook(allTime);
            }

            // Record watch: check if this week breaks any all-time record; update & persist if so
            List<String> recordWatchLines = new ArrayList<>();

            // Highest score ever
            if (weekly.highest.points > allTime.highestScore.points) {
                allTime.highestScore = AllTimeRecord.fromTeamScore("🔥 Highest Score Ever", currentSeasonId, targetWeek, weekly.highest);
                recordWatchLines.add("🔥 **Highest Score Ever** — " + weekly.highest.teamName + " (" + fmt1(weekly.highest.points) + ")");
            }

            // Lowest score ever (smaller is “better” record)
            if (weekly.lowest.points < allTime.lowestScore.points) {
                allTime.lowestScore = AllTimeRecord.fromTeamScore("❄️ Lowest Score Ever", currentSeasonId, targetWeek, weekly.lowest);
                recordWatchLines.add("❄️ **Lowest Score Ever** — " + weekly.lowest.teamName + " (" + fmt1(weekly.lowest.points) + ")");
            }

            // Biggest margin ever
            if (weekly.biggest.margin > allTime.biggestMargin.margin) {
                allTime.biggestMargin = AllTimeRecord.fromMargin("💥 Biggest Margin Ever", currentSeasonId, targetWeek, weekly.biggest);
                recordWatchLines.add("💥 **Biggest Margin Ever** — " + weekly.biggest.winnerName + " by " + fmt1(weekly.biggest.margin));
            }

            // Smallest margin of victory ever (exclude ties by design; margin must be > 0)
            if (weekly.closest.margin > 0 && weekly.closest.margin < allTime.smallestMargin.margin) {
                allTime.smallestMargin = AllTimeRecord.fromMargin("🤏 Smallest Margin Ever", currentSeasonId, targetWeek, weekly.closest);
                recordWatchLines.add("🤏 **Smallest Margin Ever** — " + weekly.closest.winnerName + " by " + fmt1(weekly.closest.margin));
            }

            if (!recordWatchLines.isEmpty()) {
                saveAllTimeRecordBook(allTime);
            }

            // Build recap embed
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("📊 Week " + targetWeek + " Recap")
                    .setColor(Color.BLUE);

            // Weekly recap (this week only) — inline so they sit side by side instead of stacking
            embed.addField("🔥 Highest Scorer",
                    weekly.highest.teamName + "\n**" + fmt1(weekly.highest.points) + "**",
                    true);

            embed.addField("❄️ Lowest Scorer",
                    weekly.lowest.teamName + "\n**" + fmt1(weekly.lowest.points) + "**",
                    true);

            embed.addField("💥 Biggest Win",
                    weekly.biggest.winnerName + " over " + weekly.biggest.loserName +
                            "\n**" + fmt1(weekly.biggest.margin) + " margin**",
                    true);

            // Blank spacer field so the trophy race doesn't crowd right up against
            // the "this week" fields above it - Discord embeds have no other way
            // to add vertical gap between rows. Uses U+2800 (braille blank) instead
            // of a zero-width space - Discord's mobile client collapses fields whose
            // name/value are pure zero-width space, but renders U+2800 fine.
            embed.addField("⠀", "⠀", false);

            // Season trophy race (counts through targetWeek + season best values)
            embed.addField("🏆 Season Trophy Race (through Week " + targetWeek + ")",
                    seasonAccolades.toDiscordBlock(),
                    false);

            // Record watch — only shown when there's actually something to report
            if (!recordWatchLines.isEmpty()) {
                embed.addField("📌 Record Watch", "🚨 **NEW ALL-TIME RECORD(S)!**\n" + String.join("\n", recordWatchLines), false);
            }

            embed.setFooter("Season " + currentSeasonId + " • Week " + targetWeek);

            channel.sendMessageEmbeds(embed.build()).queue(
                    message -> onSuccess.run(),
                    failure -> {
                        log.error("Discord rejected the recap message", failure);
                        onFailure.accept("Discord rejected the message (" + failure.getMessage() + "). " +
                                "Check the bot has Send Messages and Embed Links permission in this channel.");
                    });

        } catch (Exception e) {
            log.error("Failed to build weekly recap", e);
            onFailure.accept("Unexpected error: " + e.getMessage());
        }
    }

    // ----------------------------
    // Core computations
    // ----------------------------

    private List<JsonObject> getMatchupsForWeek(JsonObject data, int week) {
        JsonArray scheduleData = data.getAsJsonArray("schedule");
        if (scheduleData == null) return List.of();

        List<JsonObject> out = new ArrayList<>();
        for (int i = 0; i < scheduleData.size(); i++) {
            JsonObject matchup = scheduleData.get(i).getAsJsonObject();
            if (matchup == null || !matchup.has("matchupPeriodId")) continue;
            if (matchup.get("matchupPeriodId").getAsInt() != week) continue;

            if (!matchup.has("home") || !matchup.has("away")) continue;
            // Only count played games
            if (!looksPlayed(matchup)) continue;

            out.add(matchup);
        }
        return out;
    }

    private WeeklyWinners computeWeeklyWinners(List<JsonObject> matchups, Map<Integer, String> teams) {
        TeamScore highest = new TeamScore("—", -1);
        TeamScore lowest  = new TeamScore("—", Double.MAX_VALUE);

        MarginResult biggest = new MarginResult("—", "—", -1);
        MarginResult closest = new MarginResult("—", "—", Double.MAX_VALUE);

        for (JsonObject m : matchups) {
            JsonObject home = m.getAsJsonObject("home");
            JsonObject away = m.getAsJsonObject("away");

            int hId = home.get("teamId").getAsInt();
            int aId = away.get("teamId").getAsInt();

            double h = safePoints(home);
            double a = safePoints(away);

            String hName = teams.getOrDefault(hId, "Team " + hId);
            String aName = teams.getOrDefault(aId, "Team " + aId);

            // Highest / lowest single team score
            if (h > highest.points) highest = new TeamScore(hName, h);
            if (a > highest.points) highest = new TeamScore(aName, a);

            // A score of exactly 0 means the team never set a roster that week
            // (bye/forfeit in the playoff or consolation bracket), not a real
            // worst performance - exclude it so it can't be crowned "lowest score".
            if (h > 0 && h < lowest.points) lowest = new TeamScore(hName, h);
            if (a > 0 && a < lowest.points) lowest = new TeamScore(aName, a);

            // Margins
            double margin = Math.abs(h - a);
            if (margin > biggest.margin) {
                boolean homeWon = h > a;
                String winner = homeWon ? hName : aName;
                String loser  = homeWon ? aName : hName;
                biggest = new MarginResult(winner, loser, margin);
            }

            // smallest margin of victory (exclude ties = 0)
            if (margin > 0 && margin < closest.margin) {
                boolean homeWon = h > a;
                String winner = homeWon ? hName : aName;
                String loser  = homeWon ? aName : hName;
                closest = new MarginResult(winner, loser, margin);
            }
        }

        return new WeeklyWinners(highest, lowest, biggest, closest);
    }

    private SeasonAccolades computeSeasonAccolades(JsonObject data, Map<Integer, String> teams, int throughWeek) {
        JsonArray scheduleData = data.getAsJsonArray("schedule");
        if (scheduleData == null) return new SeasonAccolades();

        // week -> list matchups
        Map<Integer, List<JsonObject>> byWeek = new HashMap<>();
        for (int i = 0; i < scheduleData.size(); i++) {
            JsonObject m = scheduleData.get(i).getAsJsonObject();
            if (m == null || !m.has("matchupPeriodId")) continue;

            int w = m.get("matchupPeriodId").getAsInt();
            if (w < 1 || w > throughWeek) continue;
            if (!m.has("home") || !m.has("away")) continue;
            if (!looksPlayed(m)) continue;

            byWeek.computeIfAbsent(w, k -> new ArrayList<>()).add(m);
        }

        // how many times each team has taken each weekly accolade
        Map<String, Integer> highCount = new HashMap<>();
        Map<String, Integer> lowCount  = new HashMap<>();
        Map<String, Integer> winCount  = new HashMap<>();
        Map<String, Integer> lossCount = new HashMap<>();

        // season best values
        TeamScore seasonHigh = new TeamScore("—", -1);
        TeamScore seasonLow  = new TeamScore("—", Double.MAX_VALUE);
        MarginResult seasonBiggestBlowout = new MarginResult("—", "—", -1);
        int biggestBlowoutWeek = 0;

        for (int week = 1; week <= throughWeek; week++) {
            List<JsonObject> matchups = byWeek.getOrDefault(week, List.of());
            if (matchups.isEmpty()) continue;

            WeeklyWinners winners = computeWeeklyWinners(matchups, teams);

            bump(highCount, winners.highest.teamName);
            bump(lowCount, winners.lowest.teamName);
            bump(winCount, winners.biggest.winnerName);
            bump(lossCount, winners.biggest.loserName);

            if (winners.highest.points > seasonHigh.points) seasonHigh = winners.highest;
            if (winners.lowest.points < seasonLow.points) seasonLow = winners.lowest;

            if (winners.biggest.margin > seasonBiggestBlowout.margin) {
                seasonBiggestBlowout = winners.biggest;
                biggestBlowoutWeek = week;
            }
        }

        return new SeasonAccolades(highCount, lowCount, winCount, lossCount,
                seasonHigh, seasonLow, seasonBiggestBlowout, biggestBlowoutWeek);
    }

    // ----------------------------
    // All-time record book
    // ----------------------------

    private AllTimeRecordBook buildAllTimeRecordBookFromEspn(int currentSeasonId) {
        AllTimeRecordBook book = AllTimeRecordBook.defaultBook();

        int firstSeasonId = BotConfig.get().getFirstSeasonId();
        for (int season = firstSeasonId; season <= currentSeasonId; season++) {
            JsonObject seasonData;
            try {
                // IMPORTANT: requires ESPNApiClient#getLeagueData(int seasonId)
                seasonData = apiClient.getLeagueData(season);
            } catch (Exception e) {
                // If a season fetch fails, just skip it
                continue;
            }

            Map<Integer, String> teams = buildTeamNameMap(seasonData);
            JsonArray schedule = seasonData.getAsJsonArray("schedule");
            if (schedule == null) continue;

            for (int i = 0; i < schedule.size(); i++) {
                JsonObject m = schedule.get(i).getAsJsonObject();
                if (m == null || !m.has("matchupPeriodId")) continue;
                if (!m.has("home") || !m.has("away")) continue;
                if (!looksPlayed(m)) continue;

                int week = m.get("matchupPeriodId").getAsInt();

                JsonObject home = m.getAsJsonObject("home");
                JsonObject away = m.getAsJsonObject("away");

                int hId = home.get("teamId").getAsInt();
                int aId = away.get("teamId").getAsInt();

                double h = safePoints(home);
                double a = safePoints(away);

                String hName = teams.getOrDefault(hId, "Team " + hId);
                String aName = teams.getOrDefault(aId, "Team " + aId);

                // Highest score ever
                if (h > book.highestScore.points) book.highestScore = AllTimeRecord.team("🔥 Highest Score Ever", season, week, hName, aName, h);
                if (a > book.highestScore.points) book.highestScore = AllTimeRecord.team("🔥 Highest Score Ever", season, week, aName, hName, a);

                // Lowest score ever (exclude 0 - that's an unset roster/bye, not a real performance)
                if (h > 0 && h < book.lowestScore.points) book.lowestScore = AllTimeRecord.team("❄️ Lowest Score Ever", season, week, hName, aName, h);
                if (a > 0 && a < book.lowestScore.points) book.lowestScore = AllTimeRecord.team("❄️ Lowest Score Ever", season, week, aName, hName, a);

                // Margins
                double margin = Math.abs(h - a);
                boolean homeWon = h > a;

                String winner = homeWon ? hName : aName;
                String loser  = homeWon ? aName : hName;

                if (margin > book.biggestMargin.margin) {
                    book.biggestMargin = AllTimeRecord.margin("💥 Biggest Margin Ever", season, week, winner, loser, margin);
                }

                // smallest margin of victory: exclude ties
                if (margin > 0 && margin < book.smallestMargin.margin) {
                    book.smallestMargin = AllTimeRecord.margin("🤏 Smallest Margin Ever", season, week, winner, loser, margin);
                }
            }
        }

        book.initialized = true;
        return book;
    }

    private AllTimeRecordBook loadAllTimeRecordBook() {
        if (!Files.exists(ALL_TIME_FILE)) return AllTimeRecordBook.defaultBook();

        try {
            String json = Files.readString(ALL_TIME_FILE);
            AllTimeRecordBook book = GSON.fromJson(json, AllTimeRecordBook.class);
            return (book != null) ? book : AllTimeRecordBook.defaultBook();
        } catch (Exception e) {
            return AllTimeRecordBook.defaultBook();
        }
    }

    private void saveAllTimeRecordBook(AllTimeRecordBook book) {
        try {
            Files.writeString(ALL_TIME_FILE, GSON.toJson(book));
        } catch (IOException ignored) {}
    }

    // ----------------------------
    // Helpers
    // ----------------------------

    private int getCurrentSeasonId() {
        return Integer.parseInt(BotConfig.get().getEspnSeasonId());
    }

    private Integer getCurrentWeek(JsonObject data) {
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

        return currentWeek;
    }

    private Map<Integer, String> buildTeamNameMap(JsonObject data) {
        Map<Integer, String> teams = new HashMap<>();
        JsonArray teamsArray = data.getAsJsonArray("teams");
        if (teamsArray == null) return teams;

        for (int i = 0; i < teamsArray.size(); i++) {
            JsonObject team = teamsArray.get(i).getAsJsonObject();
            int id = team.get("id").getAsInt();
            teams.put(id, displayName(team));
        }
        return teams;
    }

    private static boolean looksPlayed(JsonObject matchup) {
        if (matchup == null || !matchup.has("home") || !matchup.has("away")) return false;
        JsonObject home = matchup.getAsJsonObject("home");
        JsonObject away = matchup.getAsJsonObject("away");
        double h = safePoints(home);
        double a = safePoints(away);

        // Either side has points OR winner is set
        if (h > 0 || a > 0) return true;
        return matchup.has("winner") && !matchup.get("winner").isJsonNull();
    }

    private static double safePoints(JsonObject side) {
        if (side == null) return 0.0;
        if (!side.has("totalPoints") || side.get("totalPoints").isJsonNull()) return 0.0;
        try { return side.get("totalPoints").getAsDouble(); } catch (Exception e) { return 0.0; }
    }

    private static void bump(Map<String, Integer> map, String key) {
        if (key == null) return;
        map.put(key, map.getOrDefault(key, 0) + 1);
    }

    private static String fmt1(double v) {
        return String.format("%.1f", v);
    }

    // Uses the same “best name” logic you used elsewhere
    private static String displayName(JsonObject t) {
        String[] candidates = new String[] {
                getString(t, "name"),
                joinNonBlank(getString(t, "location"), getString(t, "nickname")),
                joinNonBlank(getString(t, "teamLocation"), getString(t, "teamNickname")),
                getString(t, "location"),
                getString(t, "nickname"),
                getString(t, "teamLocation"),
                getString(t, "teamNickname"),
                getString(t, "abbrev")
        };

        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }

        int id = t.has("id") && !t.get("id").isJsonNull() ? t.get("id").getAsInt() : -1;
        return id > 0 ? ("Team " + id) : "Team";
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String s = obj.get(key).getAsString();
            return (s == null) ? null : s.trim();
        } catch (Exception e) {
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

    // ----------------------------
    // Data models
    // ----------------------------

    private record TeamScore(String teamName, double points) {
    }

    private record MarginResult(String winnerName, String loserName, double margin) {
    }

    private record WeeklyWinners(TeamScore highest, TeamScore lowest, MarginResult biggest, MarginResult closest) {
    }

    private record SeasonAccolades(Map<String, Integer> highCount, Map<String, Integer> lowCount,
                                   Map<String, Integer> winCount, Map<String, Integer> lossCount,
                                   TeamScore seasonHigh, TeamScore seasonLow,
                                   MarginResult seasonBiggestBlowout, int biggestBlowoutWeek) {
            SeasonAccolades() {
                this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                        new TeamScore("—", -1), new TeamScore("—", Double.MAX_VALUE),
                        new MarginResult("—", "—", -1), 0);
            }

        String toDiscordBlock() {
                return "```txt\n" +
                        "🔥 Highest Score (Best: " + fmt1(seasonHigh.points) + ", " + seasonHigh.teamName + ")\n" +
                        topThree(highCount) + "\n\n" +
                        "❄️ Lowest Score (Worst: " + fmt1(seasonLow.points) + ", " + seasonLow.teamName + ")\n" +
                        topThree(lowCount) + "\n\n" +
                        "💪 Most Blowout Wins\n" +
                        topThree(winCount) + "\n\n" +
                        "🧱 Most Blowout Losses\n" +
                        topThree(lossCount) + "\n\n" +
                        "💥 Biggest Blowout: " + seasonBiggestBlowout.winnerName + " over " + seasonBiggestBlowout.loserName +
                                " by " + fmt1(seasonBiggestBlowout.margin) +
                                (biggestBlowoutWeek > 0 ? " (Week " + biggestBlowoutWeek + ")" : "") + "\n" +
                        "```";
            }

            /** Top 3 teams by count, formatted as a numbered list; fewer than 3 shows however many exist. */
            private static String topThree(Map<String, Integer> counts) {
                List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
                entries.sort((a, b) -> b.getValue() - a.getValue());

                if (entries.isEmpty()) return "  —";

                int limit = Math.min(3, entries.size());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < limit; i++) {
                    sb.append("  ").append(i + 1).append(". ")
                            .append(entries.get(i).getKey()).append(" — ").append(entries.get(i).getValue());
                    if (i < limit - 1) sb.append("\n");
                }
                return sb.toString();
            }
        }

    private static class AllTimeRecordBook {
        boolean initialized;

        AllTimeRecord highestScore;
        AllTimeRecord lowestScore;
        AllTimeRecord biggestMargin;
        AllTimeRecord smallestMargin;

        static AllTimeRecordBook defaultBook() {
            AllTimeRecordBook b = new AllTimeRecordBook();
            b.initialized = false;
            b.highestScore = AllTimeRecord.team("🔥 Highest Score Ever", 0, 0, "—", "—", -1);
            b.lowestScore  = AllTimeRecord.team("❄️ Lowest Score Ever", 0, 0, "—", "—", Double.MAX_VALUE);
            b.biggestMargin = AllTimeRecord.margin("💥 Biggest Margin Ever", 0, 0, "—", "—", -1);
            b.smallestMargin = AllTimeRecord.margin("🤏 Smallest Margin Ever", 0, 0, "—", "—", Double.MAX_VALUE);
            return b;
        }

        boolean isInitialized() { return initialized; }
    }

    private static class AllTimeRecord {
        String label;

        int seasonId;
        int week;

        // For score records:
        String team;
        String opponent;
        double points;

        // For margin records:
        String winner;
        String loser;
        double margin;

        static AllTimeRecord team(String label, int seasonId, int week, String team, String opponent, double points) {
            AllTimeRecord r = new AllTimeRecord();
            r.label = label;
            r.seasonId = seasonId;
            r.week = week;
            r.team = team;
            r.opponent = opponent;
            r.points = points;
            return r;
        }

        static AllTimeRecord margin(String label, int seasonId, int week, String winner, String loser, double margin) {
            AllTimeRecord r = new AllTimeRecord();
            r.label = label;
            r.seasonId = seasonId;
            r.week = week;
            r.winner = winner;
            r.loser = loser;
            r.margin = margin;
            return r;
        }

        static AllTimeRecord fromTeamScore(String label, int seasonId, int week, TeamScore s) {
            return team(label, seasonId, week, s.teamName, "—", s.points);
        }

        static AllTimeRecord fromMargin(String label, int seasonId, int week, MarginResult m) {
            return margin(label, seasonId, week, m.winnerName, m.loserName, m.margin);
        }
    }
}