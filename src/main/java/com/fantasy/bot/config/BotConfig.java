package com.fantasy.bot.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Single point of access for environment configuration. Checks the .env file
 * first (local dev) and falls back to real process environment variables
 * (Docker/production), so behavior is consistent regardless of how the bot
 * is deployed.
 */
public final class BotConfig {
    private static final BotConfig INSTANCE = new BotConfig();

    private final Function<String, String> envLookup;

    private BotConfig() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        this.envLookup = key -> {
            String value = dotenv.get(key);
            return value != null ? value : System.getenv(key);
        };
    }

    /** Package-private: lets tests inject a fake environment instead of reading real dotenv/system env. */
    BotConfig(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    public static BotConfig get() {
        return INSTANCE;
    }

    public String getDiscordToken() {
        return require("DISCORD_TOKEN");
    }

    public String getEspnLeagueId() {
        return require("ESPN_LEAGUE_ID");
    }

    public String getEspnSeasonId() {
        return getOrDefault("ESPN_SEASON_ID", "2025");
    }

    /**
     * The earliest season to include when computing history-spanning stats
     * (all-time records, head-to-head). Defaults to the current season
     * (i.e. no history) if unset, since assuming a specific start year would
     * silently be wrong for anyone else self-hosting this for their own league.
     */
    public int getFirstSeasonId() {
        String raw = getRaw("ESPN_FIRST_SEASON_ID");
        if (raw == null || raw.isBlank()) return Integer.parseInt(getEspnSeasonId());
        return Integer.parseInt(raw.trim());
    }

    public String getEspnS2() {
        return getRaw("ESPN_S2");
    }

    public String getSwid() {
        return getRaw("SWID");
    }

    /** Returns null if unset or not a valid long, rather than throwing. */
    public Long getRecapChannelId() {
        String raw = getRaw("RECAP_CHANNEL_ID");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Checks all required/format-constrained env vars upfront and returns
     * every problem found, so startup fails fast with one complete list
     * instead of crashing on whichever var happens to be read first.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (isBlank(getRaw("DISCORD_TOKEN"))) {
            errors.add("DISCORD_TOKEN is required (Discord Developer Portal > your app > Bot > Reset Token)");
        }

        String leagueId = getRaw("ESPN_LEAGUE_ID");
        if (isBlank(leagueId)) {
            errors.add("ESPN_LEAGUE_ID is required (found in your league's URL on fantasy.espn.com)");
        } else if (!leagueId.trim().matches("\\d+")) {
            errors.add("ESPN_LEAGUE_ID must be numeric, got: " + leagueId);
        }

        String season = getRaw("ESPN_SEASON_ID");
        if (!isBlank(season) && !season.trim().matches("\\d{4}")) {
            errors.add("ESPN_SEASON_ID should be a 4-digit year, got: " + season);
        }

        String recapChannel = getRaw("RECAP_CHANNEL_ID");
        if (!isBlank(recapChannel) && !recapChannel.trim().matches("\\d+")) {
            errors.add("RECAP_CHANNEL_ID must be numeric (a Discord channel ID), got: " + recapChannel);
        }

        String lineupAlertChannel = getRaw("LINEUP_ALERT_CHANNEL_ID");
        if (!isBlank(lineupAlertChannel) && !lineupAlertChannel.trim().matches("\\d+")) {
            errors.add("LINEUP_ALERT_CHANNEL_ID must be numeric (a Discord channel ID), got: " + lineupAlertChannel);
        }

        String firstSeason = getRaw("ESPN_FIRST_SEASON_ID");
        if (!isBlank(firstSeason)) {
            if (!firstSeason.trim().matches("\\d{4}")) {
                errors.add("ESPN_FIRST_SEASON_ID should be a 4-digit year, got: " + firstSeason);
            } else if (Integer.parseInt(firstSeason.trim()) > Integer.parseInt(getEspnSeasonId())) {
                errors.add("ESPN_FIRST_SEASON_ID (" + firstSeason.trim() + ") is after ESPN_SEASON_ID (" + getEspnSeasonId() + ")");
            }
        }

        boolean hasS2 = !isBlank(getRaw("ESPN_S2"));
        boolean hasSwid = !isBlank(getRaw("SWID"));
        if (hasS2 != hasSwid) {
            errors.add("Only one of ESPN_S2/SWID is set — private leagues need both, public leagues need neither");
        }

        String guildId = getRaw("GUILD_ID");
        if (!isBlank(guildId) && !guildId.trim().matches("\\d+")) {
            errors.add("GUILD_ID must be numeric (a Discord server ID), got: " + guildId);
        }

        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * When set, slash commands register to this single guild instead of
     * globally, for instant propagation during development (global commands
     * take up to an hour). Leave unset for production.
     */
    public Long getGuildId() {
        String raw = getRaw("GUILD_ID");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns null if unset or not a valid long, rather than throwing. */
    public Long getLineupAlertChannelId() {
        String raw = getRaw("LINEUP_ALERT_CHANNEL_ID");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getRaw(String key) {
        return envLookup.apply(key);
    }

    private String getOrDefault(String key, String defaultValue) {
        String value = getRaw(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private String require(String key) {
        String value = getRaw(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " environment variable is required but not set");
        }
        return value;
    }
}
