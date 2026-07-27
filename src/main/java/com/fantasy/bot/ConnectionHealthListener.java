package com.fantasy.bot;

import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs Discord gateway connection health. JDA already auto-reconnects on its
 * own, so this doesn't change behavior — it just makes that visible in logs,
 * which matters for a bot expected to run unattended for months at a time.
 */
public class ConnectionHealthListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ConnectionHealthListener.class);

    @Override
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        log.warn("Discord gateway disconnected (closed by {}): {}. JDA will attempt to reconnect automatically.",
                event.isClosedByServer() ? "server" : "client", event.getCloseCode());
    }

    @Override
    public void onSessionResume(SessionResumeEvent event) {
        log.info("Discord gateway session resumed.");
    }

    @Override
    public void onSessionRecreate(SessionRecreateEvent event) {
        log.info("Discord gateway session re-established (new session).");
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        log.warn("Discord gateway shut down (code {}): {}. The bot will not reconnect.",
                event.getCode(), event.getCloseCode());
    }
}
