package me.maple_bamboo_team.maplebotAutomatic.module;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandRegistryAccess;
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
    private static final String NOT_FOUND_MESSAGE_PREFIX = "未在附近找到玩家:";

    @Override
    public String getName() {
        return "PlayerFinderModule";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // 加载配置
        this.maxSearchDistance = config.finderSettings.maxSearchDistance;
        this.finderCommandName = config.finderSettings.finderCommandName;

        // 注册指令 (修复不兼容的类型错误)
        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    // 修正方法签名，匹配 FabricClientCommandSource
    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal(finderCommandName)
                .then(ClientCommandManager.argument("player", StringArgumentType.string())
                        .executes(this::findPlayerCommand)
                )
        );
    }

    private int findPlayerCommand(CommandContext<FabricClientCommandSource> context) throws CommandSyntaxException {
        if (client.player == null || client.world == null) return 0;

        String targetName = StringArgumentType.getString(context, "player");
        ClientPlayerEntity player = client.player;
        Vec3d playerPos = player.getPos();
        PlayerEntity foundPlayer = null;

        // 1. 检查目标玩家是否在线 (基础检查)
        PlayerListEntry targetEntry = client.getNetworkHandler().getPlayerListEntry(targetName);
        if (targetEntry == null) {
            sendNotFoundMessage(targetName + " (不在线)");
            return 0;
        }

        // 2. 遍历世界实体进行距离检查
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