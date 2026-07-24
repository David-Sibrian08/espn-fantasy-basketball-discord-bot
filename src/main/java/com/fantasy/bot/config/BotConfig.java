package com.fantasy.bot.config;

import io.github.cdimascio.dotenv.Dotenv;

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
