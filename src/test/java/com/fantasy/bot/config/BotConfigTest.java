package com.fantasy.bot.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BotConfigTest {

    private BotConfig configOf(Map<String, String> env) {
        return new BotConfig(env::get);
    }

    private BotConfig configOf() {
        return configOf(new HashMap<>());
    }

    // --- validate() ---

    @Test
    void validate_flagsMissingRequiredFields() {
        List<String> errors = configOf().validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("DISCORD_TOKEN")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESPN_LEAGUE_ID")));
    }

    @Test
    void validate_passesWithOnlyRequiredFieldsSet() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "58113");

        assertTrue(configOf(env).validate().isEmpty());
    }

    @Test
    void validate_rejectsNonNumericLeagueId() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "not-a-number");

        List<String> errors = configOf(env).validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("ESPN_LEAGUE_ID must be numeric")));
    }

    @Test
    void validate_rejectsBadSeasonIdFormat() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "58113");
        env.put("ESPN_SEASON_ID", "25");

        List<String> errors = configOf(env).validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("ESPN_SEASON_ID should be a 4-digit year")));
    }

    @Test
    void validate_rejectsNonNumericRecapChannelId() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "58113");
        env.put("RECAP_CHANNEL_ID", "general");

        List<String> errors = configOf(env).validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("RECAP_CHANNEL_ID must be numeric")));
    }

    @Test
    void validate_rejectsNonNumericGuildId() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "58113");
        env.put("GUILD_ID", "not-a-guild");

        List<String> errors = configOf(env).validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("GUILD_ID must be numeric")));
    }

    @Test
    void validate_rejectsFirstSeasonAfterCurrentSeason() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "58113");
        env.put("ESPN_SEASON_ID", "2024");
        env.put("ESPN_FIRST_SEASON_ID", "2026");

        List<String> errors = configOf(env).validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("is after ESPN_SEASON_ID")));
    }

    @Test
    void validate_rejectsOnlyOneOfS2AndSwidSet() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "58113");
        env.put("ESPN_S2", "some-cookie-value");
        // SWID intentionally left unset

        List<String> errors = configOf(env).validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("Only one of ESPN_S2/SWID is set")));
    }

    @Test
    void validate_acceptsBothS2AndSwidSetTogether() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");
        env.put("ESPN_LEAGUE_ID", "58113");
        env.put("ESPN_S2", "some-cookie-value");
        env.put("SWID", "{some-guid}");

        assertTrue(configOf(env).validate().isEmpty());
    }

    // --- getFirstSeasonId() ---

    @Test
    void getFirstSeasonId_defaultsToCurrentSeasonWhenUnset() {
        Map<String, String> env = new HashMap<>();
        env.put("ESPN_SEASON_ID", "2026");

        assertEquals(2026, configOf(env).getFirstSeasonId());
    }

    @Test
    void getFirstSeasonId_usesExplicitValueWhenSet() {
        Map<String, String> env = new HashMap<>();
        env.put("ESPN_SEASON_ID", "2026");
        env.put("ESPN_FIRST_SEASON_ID", "2019");

        assertEquals(2019, configOf(env).getFirstSeasonId());
    }

    // --- getEspnSeasonId() ---

    @Test
    void getEspnSeasonId_defaultsTo2025WhenUnset() {
        assertEquals("2025", configOf().getEspnSeasonId());
    }

    // --- nullable long getters: null on unset/invalid, parsed value otherwise ---

    @Test
    void getRecapChannelId_nullWhenUnset() {
        assertNull(configOf().getRecapChannelId());
    }

    @Test
    void getRecapChannelId_nullWhenNotNumeric() {
        Map<String, String> env = new HashMap<>();
        env.put("RECAP_CHANNEL_ID", "not-a-channel");

        assertNull(configOf(env).getRecapChannelId());
    }

    @Test
    void getRecapChannelId_parsesValidValue() {
        Map<String, String> env = new HashMap<>();
        env.put("RECAP_CHANNEL_ID", "1416946617264771194");

        assertEquals(1416946617264771194L, configOf(env).getRecapChannelId());
    }

    @Test
    void getGuildId_nullWhenUnset() {
        assertNull(configOf().getGuildId());
    }

    @Test
    void getGuildId_parsesValidValue() {
        Map<String, String> env = new HashMap<>();
        env.put("GUILD_ID", "123456789");

        assertEquals(123456789L, configOf(env).getGuildId());
    }

    @Test
    void getLineupAlertChannelId_nullWhenUnset() {
        assertNull(configOf().getLineupAlertChannelId());
    }

    @Test
    void getLineupAlertChannelId_parsesValidValue() {
        Map<String, String> env = new HashMap<>();
        env.put("LINEUP_ALERT_CHANNEL_ID", "987654321");

        assertEquals(987654321L, configOf(env).getLineupAlertChannelId());
    }

    // --- required-field getters throw rather than silently return null ---

    @Test
    void getDiscordToken_throwsWhenUnset() {
        assertThrows(IllegalStateException.class, () -> configOf().getDiscordToken());
    }

    @Test
    void getDiscordToken_returnsValueWhenSet() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "abc123");

        assertEquals("abc123", configOf(env).getDiscordToken());
    }

    @Test
    void getEspnLeagueId_throwsWhenUnset() {
        assertThrows(IllegalStateException.class, () -> configOf().getEspnLeagueId());
    }
}
