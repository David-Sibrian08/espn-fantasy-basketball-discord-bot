package com.fantasy.bot.lineup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TeamOwnerRegistryTest {

    @Test
    void missingFileReturnsNullForEveryTeam(@TempDir Path tempDir) {
        Path file = tempDir.resolve("team_owners.json");
        // never created

        TeamOwnerRegistry registry = new TeamOwnerRegistry(file);

        assertNull(registry.getDiscordUserId(1));
    }

    @Test
    void resolvesRealDiscordIdForConfiguredTeam(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("team_owners.json");
        Files.writeString(file, "{\"1\": \"253261863633158146\", \"2\": \"727583134195122258\"}");

        TeamOwnerRegistry registry = new TeamOwnerRegistry(file);

        assertEquals("253261863633158146", registry.getDiscordUserId(1));
        assertEquals("727583134195122258", registry.getDiscordUserId(2));
    }

    @Test
    void blankValueTreatedAsNoOwner(@TempDir Path tempDir) throws IOException {
        // real scenario from this session: two league members aren't on Discord
        Path file = tempDir.resolve("team_owners.json");
        Files.writeString(file, "{\"1\": \"253261863633158146\", \"12\": \"\", \"21\": \"\"}");

        TeamOwnerRegistry registry = new TeamOwnerRegistry(file);

        assertEquals("253261863633158146", registry.getDiscordUserId(1));
        assertNull(registry.getDiscordUserId(12));
        assertNull(registry.getDiscordUserId(21));
    }

    @Test
    void teamIdNotInFileReturnsNull(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("team_owners.json");
        Files.writeString(file, "{\"1\": \"253261863633158146\"}");

        TeamOwnerRegistry registry = new TeamOwnerRegistry(file);

        assertNull(registry.getDiscordUserId(99));
    }

    @Test
    void malformedJsonDoesNotThrowAndFallsBackToNoOwners(@TempDir Path tempDir) throws IOException {
        // real bug from this session: a missing comma between entries crashed the whole
        // bot at startup before this was fixed. This is the regression test for that fix.
        Path file = tempDir.resolve("team_owners.json");
        Files.writeString(file, "{\"1\": \"253261863633158146\" \"2\": \"727583134195122258\"}");

        TeamOwnerRegistry registry = assertDoesNotThrow(() -> new TeamOwnerRegistry(file));

        assertNull(registry.getDiscordUserId(1));
        assertNull(registry.getDiscordUserId(2));
    }

    @Test
    void configuredOwnerCountIsZeroWhenFileMissing(@TempDir Path tempDir) {
        Path file = tempDir.resolve("team_owners.json");

        TeamOwnerRegistry registry = new TeamOwnerRegistry(file);

        assertEquals(0, registry.configuredOwnerCount());
    }

    @Test
    void configuredOwnerCountExcludesBlankEntries(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("team_owners.json");
        Files.writeString(file, "{\"1\": \"253261863633158146\", \"2\": \"727583134195122258\", \"12\": \"\", \"21\": \"\"}");

        TeamOwnerRegistry registry = new TeamOwnerRegistry(file);

        assertEquals(2, registry.configuredOwnerCount());
    }
}
