package com.fantasy.bot.lineup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live on/off switch for lineup health alerts, toggled via /lineupalerts
 * without needing a restart. Persisted to disk so the setting survives one.
 */
public class LineupAlertsState {
    private static final Logger log = LoggerFactory.getLogger(LineupAlertsState.class);
    private static final Path FILE = Path.of("lineup_alerts_state.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record State(boolean enabled) {
    }

    private final AtomicBoolean enabled;

    public LineupAlertsState() {
        this.enabled = new AtomicBoolean(load());
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
        save(value);
    }

    private static boolean load() {
        if (!Files.exists(FILE)) return true; // default on

        try {
            String json = Files.readString(FILE);
            State state = GSON.fromJson(json, State.class);
            return state != null ? state.enabled() : true;
        } catch (Exception e) {
            log.error("Failed to read lineup_alerts_state.json, defaulting to enabled", e);
            return true;
        }
    }

    private static void save(boolean value) {
        try {
            Files.writeString(FILE, GSON.toJson(new State(value)));
        } catch (IOException e) {
            log.error("Failed to persist lineup_alerts_state.json", e);
        }
    }
}
