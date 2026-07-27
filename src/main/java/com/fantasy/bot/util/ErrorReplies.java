package com.fantasy.bot.util;

import java.io.IOException;

public final class ErrorReplies {
    private ErrorReplies() {
    }

    /**
     * Builds a user-facing error message for a failed command. When the
     * exception is an IOException — ESPNApiClient's controlled, human-readable
     * failure messages for things like a bad league ID, a private league
     * missing cookies, or an ESPN outage — that message is shown, since it's
     * usually exactly what a self-hoster needs to fix their setup. Other
     * exception types (NPEs from unexpected JSON shapes, etc.) keep a generic
     * message instead, since their text isn't meant for end users.
     */
    public static String forFailure(String action, Exception e) {
        if (e instanceof IOException && e.getMessage() != null && !e.getMessage().isBlank()) {
            return "❌ Failed to " + action + ": " + e.getMessage();
        }
        return "❌ Failed to " + action + ". Check your configuration.";
    }
}
