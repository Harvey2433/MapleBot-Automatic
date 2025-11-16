package me.maple_bamboo_team.maplebotAutomatic.client;

import me.maple_bamboo_team.maplebotAutomatic.config.ConfigManager;
import me.maple_bamboo_team.maplebotAutomatic.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import me.maple_bamboo_team.maplebotAutomatic.module.JumpDriveModule;
import me.maple_bamboo_team.maplebotAutomatic.client.JumpDriveMessageParser.MessageData;


public class MaplebotAutomaticClient implements ClientModInitializer {

    public static final String MOD_ID = "maplebot-automatic";
    public static final MaplebotAutomaticClient INSTANCE = new MaplebotAutomaticClient();

    private ModuleManager moduleManager;

    @Override
    public void onInitializeClient() {
        System.out.println("MapleBotAutomatic Client Initializing...");

        // 1. 加载配置
        ConfigManager.loadConfig();

        // 2. 初始化模块管理器
        this.moduleManager = new ModuleManager(
                MinecraftClient.getInstance(),
                ConfigManager.getConfig()
        );

        // 3. 注册客户端游戏刻事件，驱动所有模块
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (moduleManager != null && client.player != null) {
                moduleManager.onClientTick();
            }
        });

        // 聊天事件监听器使用配置驱动的解析器实例
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, original) -> {

            if (moduleManager == null || moduleManager.JUMP_DRIVE_MODULE == null) return;

            JumpDriveModule jumpDriveModule = moduleManager.JUMP_DRIVE_MODULE;

            // 获取聊天消息的纯文本内容 (Text -> String)
            String fullMessage = message.getString();

            // 4a. 尝试解析是否为私信 (PM)
            JumpDriveMessageParser parser = jumpDriveModule.getMessageParser();

            if (parser != null) {
                // 使用模块提供的配置驱动的解析器实例
                MessageData data = parser.parsePrivateMessage(fullMessage);

                if (data != null) {
                    jumpDriveModule.onPrivateMessage(data.sender, data.content);
                }
            } else {
                // 如果解析器未初始化（配置正则错误），则不处理私信
            }



            // 这是一个 Consumer 接口，不需要返回 boolean
        });

        // 5. 注册关闭钩子，安全关闭模块
        Runtime.getRuntime().addShutdownHook(new Thread(this::onShutdown));
    }

    public void onShutdown() {
        if (moduleManager != null) {
            moduleManager.shutdownModules();
        }
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }
}