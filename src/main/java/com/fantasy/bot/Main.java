package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import com.fantasy.bot.lineup.TeamOwnerRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        List<String> configErrors = BotConfig.get().validate();
        if (!configErrors.isEmpty()) {
            log.error("Invalid configuration — fix the following in your .env and restart:");
            for (String error : configErrors) {
                log.error("  - {}", error);
            }
            System.exit(1);
        }

        try {
            String token = BotConfig.get().getDiscordToken();

            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .build();

            jda.awaitReady();

            ESPNApiClient apiClient = new ESPNApiClient();
            TeamOwnerRegistry ownerRegistry = new TeamOwnerRegistry();
            WeeklyRecapScheduler recapScheduler = new WeeklyRecapScheduler(jda, apiClient);
            LineupHealthScheduler lineupScheduler = new LineupHealthScheduler(jda, apiClient, ownerRegistry);

            CommandManager commandManager = new CommandManager(jda, apiClient, recapScheduler, ownerRegistry);
            commandManager.registerCommands();

            recapScheduler.start();
            lineupScheduler.start();

            log.info("Bot is online!");

        } catch (Exception e) {
            log.error("Failed to start bot", e);
        }
    }
}
