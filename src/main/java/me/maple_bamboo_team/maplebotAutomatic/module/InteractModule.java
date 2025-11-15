package me.maple_bamboo_team.maplebotAutomatic.module;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 实现 /muse 指令，利用 RotationModule 实现平滑转向后交互。
 * 配置项从 MaplebotConfig.InteractSettings 中加载。
 */
public class InteractModule implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;
    private final RotationModule rotationModule;

    // 状态管理
    private BlockPos pendingPos = null;
    private Vec3d lastTargetVec = null;

    // 配置项：从配置文件加载
    private String museCommandName;
    private double maxInteractDistance;
    private double aimYOffset;
    private float rotationSpeed; // ✅ 确保此成员变量已声明

    public InteractModule(ModuleManager manager) {
        this.rotationModule = manager.ROTATION_MODULE;
    }

    @Override
    public String getName() {
        return "InteractModule";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // 【应用配置】: 从 InteractSettings 中加载所有配置
        this.museCommandName = config.interactSettings.museCommandName;
        this.maxInteractDistance = config.interactSettings.maxInteractDistance;
        this.aimYOffset = config.interactSettings.aimYOffset;
        this.rotationSpeed = config.interactSettings.rotationSpeed; // 确保此行不再报错

        if (config.enableInteractModule) {
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                registerCommands(dispatcher);
            });
        }
    }

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal(museCommandName)
                .then(ClientCommandManager.argument("x", StringArgumentType.string())
                        .then(ClientCommandManager.argument("y", StringArgumentType.string())
                                .then(ClientCommandManager.argument("z", StringArgumentType.string())
                                        .executes(this::executeMuseCommand)
                                )))
        );
        sendFeedback(Text.literal("§a[Maplebot] 指令 /" + museCommandName + " 已成功注册。"));
    }

    private int executeMuseCommand(CommandContext<FabricClientCommandSource> context) {
        if (!config.enableInteractModule) {
            sendFeedback(Text.literal("§e[Maplebot] InteractModule 已禁用。"));
            return 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            sendFeedback(Text.literal("§c[Maplebot] 玩家实体或世界不可用。"));
            return 0;
        }

        try {
            String xStr = StringArgumentType.getString(context, "x");
            String yStr = StringArgumentType.getString(context, "y");
            String zStr = StringArgumentType.getString(context, "z");

            BlockPos targetPos = parseBlockPos(xStr, yStr, zStr, player.getBlockPos());

            if (client.world.isAir(targetPos)) {
                sendFeedback(Text.literal("§c[Maplebot] 目标方块 (" + targetPos.toShortString() + ") 为空气或不可用。"));
                return 0;
            }

            // 距离检测
            Vec3d targetCenter = Vec3d.ofCenter(targetPos);
            double distance = player.getEyePos().distanceTo(targetCenter);

            if (distance > maxInteractDistance) {
                sendFeedback(Text.literal("§c[Maplebot] 目标过远 (" + String.format("%.2f", distance) + " > " + maxInteractDistance + " 格)，已取消操作。"));
                pendingPos = null;
                lastTargetVec = null;
                return 0;
            }

            // 设置平滑转头目标
            Vec3d targetVec = targetCenter.subtract(0, aimYOffset, 0);

            lastTargetVec = targetVec;
            pendingPos = targetPos;

            // 使用配置的速度启动 RotationModule
            rotationModule.lookAt(targetVec, rotationSpeed);

            sendFeedback(Text.literal("§e[Maplebot] 正在平滑转向方块 " + targetPos.toShortString() + "..."));

        } catch (Exception e) {
            sendFeedback(Text.literal("§c[Maplebot] 指令执行错误: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }

        return 1;
    }

    @Override
    public void tick() {
        if (!config.enableInteractModule || pendingPos == null || client.player == null || client.interactionManager == null || client.world == null) {
            return;
        }

        // 检查 RotationModule 是否已完成瞄准
        if (!rotationModule.isRotating()) {
            executeInteraction();
            pendingPos = null;
            lastTargetVec = null;
        }
    }

    /**
     * 执行实际的右键交互操作。
     */
    private void executeInteraction() {
        if (pendingPos == null || lastTargetVec == null) return;

        Vec3d targetVec = lastTargetVec;

        Vec3d playerEyePos = client.player.getEyePos();
        Vec3d diff = targetVec.subtract(playerEyePos);
        // 确定击中面 (与视线方向相反)
        Direction hitDir = Direction.getFacing(diff.x, diff.y, diff.z).getOpposite();

        BlockHitResult hitResult = new BlockHitResult(
                targetVec,
                hitDir,
                pendingPos,
                false
        );

        // 模拟玩家右键
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        BlockState state = client.world.getBlockState(pendingPos);
        sendFeedback(Text.literal("§a[Maplebot] 交互成功: §6" + state.getBlock().getName().getString() + " §a于 " + pendingPos.toShortString()));
    }

    // 解析相对坐标 (支持 ~)
    private BlockPos parseBlockPos(String xStr, String yStr, String zStr, BlockPos currentPos) {
        double x = parseCoordinate(xStr, currentPos.getX());
        double y = parseCoordinate(yStr, currentPos.getY());
        double z = parseCoordinate(zStr, currentPos.getZ());
        return new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
    }

    private double parseCoordinate(String coordStr, double currentCoord) {
        if (coordStr.startsWith("~")) {
            if (coordStr.length() > 1) {
                return currentCoord + Double.parseDouble(coordStr.substring(1));
            }
            return currentCoord;
        }
        return Double.parseDouble(coordStr);
    }

    // 发送聊天反馈给玩家
    private void sendFeedback(Text text) {
        if (client.player != null) {
            client.player.sendMessage(text, false);
        }
    }

    @Override
    public void shutdown() {
        pendingPos = null;
        lastTargetVec = null;
        if (rotationModule != null) {
            // 确保停止任何进行中的旋转
            rotationModule.targetVec = null;
        }
    }
}