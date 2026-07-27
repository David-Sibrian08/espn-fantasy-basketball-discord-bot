package com.fantasy.bot.lineup;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Static, manually-edited mapping of ESPN team ID -> Discord user ID, so
 * lineup health alerts can @mention the right person. Loaded once at
 * startup; edit team_owners.json and restart the bot to change it.
 */
public class TeamOwnerRegistry {
    private static final Logger log = LoggerFactory.getLogger(TeamOwnerRegistry.class);
    private static final Path DEFAULT_FILE = Path.of("team_owners.json");
    private static final Gson GSON = new Gson();

    private final Map<String, String> teamIdToDiscordUserId;

    public TeamOwnerRegistry() {
        this(DEFAULT_FILE);
    }

    /** Package-private: lets tests point at a temp file instead of the real project file. */
    TeamOwnerRegistry(Path file) {
        this.teamIdToDiscordUserId = load(file);
    }

    /** Discord user ID for the given ESPN team ID, or null if not configured (including blank entries, e.g. owners without Discord). */
    public String getDiscordUserId(int espnTeamId) {
        String value = teamIdToDiscordUserId.get(String.valueOf(espnTeamId));
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Number of teams with a non-blank Discord user ID configured. Used by /diagnostics. */
    public int configuredOwnerCount() {
        return (int) teamIdToDiscordUserId.values().stream()
                .filter(v -> v != null && !v.isBlank())
                .count();
    }

    private static Map<String, String> load(Path file) {
        if (!Files.exists(file)) {
            log.warn("team_owners.json not found — lineup health alerts won't be able to @mention anyone. See team_owners.example.json.");
            return Map.of();
        }

        try {
            String json = Files.readString(file);
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> parsed = GSON.fromJson(json, type);
            return parsed != null ? parsed : Map.of();
        } catch (IOException e) {
            log.error("Failed to read team_owners.json, no owners will be mapped", e);
            return Map.of();
        } catch (Exception e) {
            log.error("team_owners.json is malformed (invalid JSON) — no owners will be mapped until it's fixed", e);
            return Map.of();
        }
    }
}
