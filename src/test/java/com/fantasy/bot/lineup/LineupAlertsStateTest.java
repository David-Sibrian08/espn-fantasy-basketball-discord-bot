package com.fantasy.bot.lineup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LineupAlertsStateTest {

    @Test
    void defaultsToEnabledWhenNoFileExists(@TempDir Path tempDir) {
        Path file = tempDir.resolve("lineup_alerts_state.json");

        LineupAlertsState state = new LineupAlertsState(file);

        assertTrue(state.isEnabled());
        assertFalse(Files.exists(file), "file shouldn't be created just by reading the default");
    }

    @Test
    void togglingOffPersistsToDisk(@TempDir Path tempDir) {
        Path file = tempDir.resolve("lineup_alerts_state.json");
        LineupAlertsState state = new LineupAlertsState(file);

        state.setEnabled(false);

        assertFalse(state.isEnabled());
        assertTrue(Files.exists(file));
    }

    @Test
    void settingSurvivesACleanRestart(@TempDir Path tempDir) {
        Path file = tempDir.resolve("lineup_alerts_state.json");
        LineupAlertsState first = new LineupAlertsState(file);
        first.setEnabled(false);

        // simulate a bot restart: brand new instance reading the same file
        LineupAlertsState second = new LineupAlertsState(file);

        assertFalse(second.isEnabled());
    }

    @Test
    void reEnablingAlsoPersists(@TempDir Path tempDir) {
        Path file = tempDir.resolve("lineup_alerts_state.json");
        LineupAlertsState first = new LineupAlertsState(file);
        first.setEnabled(false);
        first.setEnabled(true);

        LineupAlertsState second = new LineupAlertsState(file);

        assertTrue(second.isEnabled());
    }

    @Test
    void malformedFileDefaultsToEnabledInsteadOfCrashing(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("lineup_alerts_state.json");
        Files.writeString(file, "{not valid json");

        LineupAlertsState state = assertDoesNotThrow(() -> new LineupAlertsState(file));

        assertTrue(state.isEnabled());
    }
}
