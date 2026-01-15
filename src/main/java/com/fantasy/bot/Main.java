package com.fantasy.bot;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Main {
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        String token = dotenv.get("DISCORD_TOKEN");

        if (token == null || token.isEmpty()) {
            System.err.println("ERROR: DISCORD_TOKEN environment variable not set!");
            return;
        }

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .build();

            jda.awaitReady();

            // Register commands and listeners
            CommandManager commandManager = new CommandManager(jda);
            commandManager.registerCommands();

            // Start weekly recap scheduler
            WeeklyRecapScheduler scheduler = new WeeklyRecapScheduler(jda);
            scheduler.start();

            System.out.println("Bot is online!");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}