package me.maple_bamboo_team.maplebotAutomatic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 负责 MaplebotConfig 的加载和保存。
 */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("maplebot-automatic.json");

    private static MaplebotConfig config;

    public static MaplebotConfig getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    public static void loadConfig() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                config = GSON.fromJson(reader, MaplebotConfig.class);
                if (config == null) {
                    config = new MaplebotConfig();
                }
                saveConfig();
            } catch (IOException e) {
                System.err.println("Failed to load Maplebot config!");
                e.printStackTrace();
                config = new MaplebotConfig();
            }
        } else {
            config = new MaplebotConfig();
            saveConfig();
        }
    }

    public static void saveConfig() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            System.err.println("Failed to save Maplebot config!");
            e.printStackTrace();
        }
    }
}