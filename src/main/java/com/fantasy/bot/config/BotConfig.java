package com.fantasy.bot.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.ArrayList;
import java.util.List;

/**
 * Single point of access for environment configuration. Checks the .env file
 * first (local dev) and falls back to real process environment variables
 * (Docker/production), so behavior is consistent regardless of how the bot
 * is deployed.
 */
public final class BotConfig {
    private static final BotConfig INSTANCE = new BotConfig();

    private final Dotenv dotenv;

    private BotConfig() {
        this.dotenv = Dotenv.configure().ignoreIfMissing().load();
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

        boolean hasS2 = !isBlank(getRaw("ESPN_S2"));
        boolean hasSwid = !isBlank(getRaw("SWID"));
        if (hasS2 != hasSwid) {
            errors.add("Only one of ESPN_S2/SWID is set — private leagues need both, public leagues need neither");
        }

        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
        String value = dotenv.get(key);
        if (value == null) {
            value = System.getenv(key);
        }
        return value;
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
