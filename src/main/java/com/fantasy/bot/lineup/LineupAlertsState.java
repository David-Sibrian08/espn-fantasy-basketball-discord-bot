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
    private static final Path DEFAULT_FILE = Path.of("lineup_alerts_state.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record State(boolean enabled) {
    }

    private final Path file;
    private final AtomicBoolean enabled;

    public LineupAlertsState() {
        this(DEFAULT_FILE);
    }

    /** Package-private: lets tests point at a temp file instead of the real project file. */
    LineupAlertsState(Path file) {
        this.file = file;
        this.enabled = new AtomicBoolean(load(file));
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
        save(file, value);
    }

    private static boolean load(Path file) {
        if (!Files.exists(file)) return true; // default on

        try {
            String json = Files.readString(file);
            State state = GSON.fromJson(json, State.class);
            return state != null ? state.enabled() : true;
        } catch (Exception e) {
            log.error("Failed to read lineup_alerts_state.json, defaulting to enabled", e);
            return true;
        }
    }

    private static void save(Path file, boolean value) {
        try {
            Files.writeString(file, GSON.toJson(new State(value)));
        } catch (IOException e) {
            log.error("Failed to persist lineup_alerts_state.json", e);
        }
    }
}
