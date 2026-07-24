package com.fantasy.bot;

import com.fantasy.bot.api.ESPNApiClient;
import com.fantasy.bot.config.BotConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            String token = BotConfig.get().getDiscordToken();

            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .build();

            jda.awaitReady();

            ESPNApiClient apiClient = new ESPNApiClient();
            WeeklyRecapScheduler scheduler = new WeeklyRecapScheduler(jda, apiClient);

            CommandManager commandManager = new CommandManager(jda, apiClient, scheduler);
            commandManager.registerCommands();

            scheduler.start();

            log.info("Bot is online!");

        } catch (Exception e) {
            log.error("Failed to start bot", e);
        }
    }
}
