package me.maple_bamboo_team.maplebotAutomatic.client;

import me.maple_bamboo_team.maplebotAutomatic.config.ConfigManager;
import me.maple_bamboo_team.maplebotAutomatic.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;



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

        // 【关键修复点】：将 Lambda 表达式的参数从 6 个修正为 5 个
        // 移除原有的第六个参数 (filtered)
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, original) -> {

            if (moduleManager == null || moduleManager.JUMP_DRIVE_MODULE == null) return;

            // 获取聊天消息的纯文本内容 (Text -> String)
            String fullMessage = message.getString();

            // 4a. 尝试解析是否为私信 (PM)
            JumpDriveMessageParser.MessageData data = JumpDriveMessageParser.parsePrivateMessage(fullMessage);

            if (data != null) {
                moduleManager.JUMP_DRIVE_MODULE.onPrivateMessage(data.sender, data.content);
            }

            // 4b. 将所有消息（包括指令反馈）转发给 onChatMessage
            moduleManager.JUMP_DRIVE_MODULE.onChatMessage(fullMessage);

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