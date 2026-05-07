package me.maple_bamboo_team.maplebotAutomatic.client;

import me.maple_bamboo_team.maplebotAutomatic.config.ConfigManager;
import me.maple_bamboo_team.maplebotAutomatic.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
// 导入 GAME 事件
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import me.maple_bamboo_team.maplebotAutomatic.module.JumpDriveModule;
import me.maple_bamboo_team.maplebotAutomatic.client.JumpDriveMessageParser.MessageData;

import net.minecraft.text.Text;
import java.util.regex.Pattern;

public class MaplebotAutomaticClient implements ClientModInitializer {

    public static final String MOD_ID = "maplebot-automatic";
    public static final MaplebotAutomaticClient INSTANCE = new MaplebotAutomaticClient();

    // 用于匹配和移除 Minecraft 格式代码的 Pattern
    private static final Pattern FORMAT_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");
    private static final String DEBUG_PREFIX = "[MaplebotAutomaticClient]";

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

        // ** 修复点 1: 切换到 ClientReceiveMessageEvents.GAME 监听器 **
        // 用于捕获服务器将私信包装成系统消息发送的情况。
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {

            // 忽略操作栏 (Action Bar) 上的覆盖消息
            if (overlay) return;

            if (moduleManager == null || moduleManager.JUMP_DRIVE_MODULE == null) return;

            JumpDriveModule jumpDriveModule = moduleManager.JUMP_DRIVE_MODULE;

            // 获取聊天消息的纯文本内容 (Text -> String)
            String fullMessage = message.getString();

            // 修复逻辑: 移除所有格式代码
            // 示例: "[CHAT] §d来自 FengLiMeng_: §d回城" -> "[CHAT] 来自 FengLiMeng_: 回城"
            String cleanedMessage = FORMAT_CODE_PATTERN.matcher(fullMessage).replaceAll("");
            // 4a. 尝试解析是否为私信 (PM)
            JumpDriveMessageParser parser = jumpDriveModule.getMessageParser();

            if (parser != null) {
                // 使用清理后的消息进行解析
                MessageData data = parser.parsePrivateMessage(cleanedMessage);

                if (data != null) {
                    jumpDriveModule.onPrivateMessage(data.sender, data.content);
                }
            } else {
                // 如果解析器未初始化（配置正则错误），则不处理私信
                System.err.println(DEBUG_PREFIX + " 错误: JumpDriveMessageParser 未初始化。请检查配置中的正则表达式。");
            }
        });

        // 5. 注册关闭钩子，安全关闭模块
        Runtime.getRuntime().addShutdownHook(new Thread(this::onShutdown));
    }

    private void onShutdown() {
        if (moduleManager != null) {
            moduleManager.shutdownModules();
        }
        // 保存配置
        ConfigManager.saveConfig();
        System.out.println("MapleBotAutomatic Client Shutdown.");
    }

    // 新增一个获取 ModuleManager 的方法，以供其他组件调用（如果需要）
    public ModuleManager getModuleManager() {
        return moduleManager;
    }
}