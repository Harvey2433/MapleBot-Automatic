package me.maple_bamboo_team.maplebotAutomatic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.maple_bamboo_team.maplebotAutomatic.client.MaplebotAutomaticClient; // 导入主客户端类以获取 ClassLoader
import net.minecraft.client.MinecraftClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 负责 MaplebotConfig 的加载和保存。
 * 首次运行时，从内置资源文件释放默认配置。
 */
public class ConfigManager {
    // 启用美化打印，使配置文件易读
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MaplebotConfig config;
    private static final String CONFIG_FILE_NAME = "maplebot_automatic.json";
    // 内置资源路径 (对应于 src/main/resources/assets/maplebot-automatic/default_config.json)
    private static final String RESOURCE_PATH = "/assets/" + MaplebotAutomaticClient.MOD_ID + "/default_config.json";


    public static void loadConfig() {
        if (MinecraftClient.getInstance().runDirectory == null) {
            System.err.println("[MaplebotConfig] 客户端运行目录不可用，使用硬编码默认配置。");
            config = new MaplebotConfig();
            return;
        }

        Path configDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config");
        Path configFile = configDir.resolve(CONFIG_FILE_NAME);

        try {
            // 1. 检查外部配置文件是否存在
            if (Files.notExists(configFile)) {
                System.out.println("[MaplebotConfig] 配置文件不存在，从内置资源生成默认配置...");

                // 尝试从 JAR 资源中释放默认配置
                if (releaseDefaultConfig(configFile)) {
                    System.out.println("[MaplebotConfig] 成功释放默认配置文件。");
                } else {
                    // 如果资源释放失败，则使用 Java 类中的硬编码默认值
                    System.err.println("[MaplebotConfig] 资源文件释放失败，使用 Java 类默认值。");
                    config = new MaplebotConfig();
                    saveConfig(configFile); // 并将其保存到外部
                    return;
                }
            }

            // 2. 加载并解析外部配置文件 (无论是刚释放的还是已存在的)
            Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8);
            config = GSON.fromJson(reader, MaplebotConfig.class);
            reader.close();

            if (config == null) {
                System.err.println("[MaplebotConfig] 配置文件加载失败或为空，使用硬编码默认配置。");
                config = new MaplebotConfig();
            }

        } catch (Exception e) {
            System.err.println("[MaplebotConfig] 配置文件加载/创建时发生错误，使用硬编码默认配置。错误: " + e.getMessage());
            e.printStackTrace();
            config = new MaplebotConfig();
        }
    }

    /**
     * 从 Mod 的 JAR 包中读取内置资源文件并将其写入外部配置文件。
     * @param targetPath 外部配置文件的目标路径。
     * @return 成功写入返回 true，否则返回 false。
     */
    private static boolean releaseDefaultConfig(Path targetPath) {
        try (InputStream inputStream = MaplebotAutomaticClient.class.getResourceAsStream(RESOURCE_PATH)) {

            if (inputStream == null) {
                System.err.println("[MaplebotConfig] 错误: 未在 JAR 包中找到内置资源文件: " + RESOURCE_PATH);
                return false;
            }

            // 确保配置目录存在
            Files.createDirectories(targetPath.getParent());

            // 将资源内容写入目标文件
            Files.copy(inputStream, targetPath);
            return true;
        } catch (IOException e) {
            System.err.println("[MaplebotConfig] 无法释放内置资源到外部文件。");
            e.printStackTrace();
            return false;
        }
    }

    public static void saveConfig() {
        // 使用默认路径保存
        Path configDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config");
        Path configFile = configDir.resolve(CONFIG_FILE_NAME);
        saveConfig(configFile);
    }

    private static void saveConfig(Path configFile) {
        try {
            Files.createDirectories(configFile.getParent());

            Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8);
            GSON.toJson(config, writer);
            writer.close();
        } catch (IOException e) {
            System.err.println("[MaplebotConfig] 配置文件保存失败！");
            e.printStackTrace();
        }
    }

    public static MaplebotConfig getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }
}