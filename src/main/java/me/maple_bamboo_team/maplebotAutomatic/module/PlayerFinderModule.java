package me.maple_bamboo_team.maplebotAutomatic.module;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * 实现 /findplayer 指令，用于在当前玩家视野范围 (配置距离) 内查找指定玩家。
 */
public class PlayerFinderModule implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;

    // 配置项：从配置文件加载
    private double maxSearchDistance;
    private String finderCommandName;

    // 用于正则表达式匹配的固定提示文本
    private static final String FOUND_MESSAGE_PREFIX = "已找到指定玩家:";
    private static final String NOT_FOUND_MESSAGE_PREFIX = "未在当前范围找到指定玩家";

    @Override
    public String getName() {
        return "PlayerFinderModule";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // 【应用配置】
        this.maxSearchDistance = config.finderSettings.maxSearchDistance;
        this.finderCommandName = config.finderSettings.finderCommandName;

        if (config.enablePlayerFinderModule) {
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                registerCommands(dispatcher);
            });
        }
    }

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal(finderCommandName)
                .then(ClientCommandManager.argument("playername", StringArgumentType.string())
                        .executes(this::executeFindPlayerCommand)
                )
        );
        sendFeedback(Text.literal("§a[Maplebot] 指令 /" + finderCommandName + " 已成功注册。"));
    }

    private int executeFindPlayerCommand(CommandContext<FabricClientCommandSource> context) {
        if (!config.enablePlayerFinderModule) {
            sendFeedback(Text.literal("§e[Maplebot] PlayerFinderModule 已禁用。"));
            return 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.getNetworkHandler() == null) {
            sendFeedback(Text.literal("§c[Maplebot] 客户端状态不可用。"));
            return 0;
        }

        String targetName = StringArgumentType.getString(context, "playername");

        // 1. 在线检测
        PlayerListEntry targetEntry = client.getNetworkHandler().getPlayerListEntry(targetName);
        if (targetEntry == null) {
            sendFeedback(Text.literal("§e[Maplebot] 玩家 §6" + targetName + " §e当前不在线或名称错误。"));
            return 0;
        }

        // 2. 距离检测
        PlayerEntity foundPlayer = null;
        Vec3d playerPos = player.getPos();

        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof PlayerEntity otherPlayer && !otherPlayer.equals(player)) {
                if (otherPlayer.getName().getString().equalsIgnoreCase(targetName)) {
                    // 检查距离
                    double distance = playerPos.distanceTo(otherPlayer.getPos());

                    if (distance <= maxSearchDistance) {
                        foundPlayer = otherPlayer;
                        break;
                    }
                }
            }
        }

        // 3. 输出结果
        if (foundPlayer != null) {
            sendFoundMessage(targetName);
        } else {
            sendNotFoundMessage(targetName);
        }

        return 1;
    }

    @Override
    public void tick() {
        // 此模块不需要每 tick 执行逻辑
    }

    @Override
    public void shutdown() {
        // 模块关闭时无需清理
    }

    private void sendFoundMessage(String playerName) {
        String message = FOUND_MESSAGE_PREFIX + playerName;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§a" + message), false);
        }
    }

    private void sendNotFoundMessage(String playerName) {
        String message = NOT_FOUND_MESSAGE_PREFIX + playerName;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§e" + message), false);
        }
    }

    private void sendFeedback(Text text) {
        if (client.player != null) {
            client.player.sendMessage(text, false);
        }
    }
}