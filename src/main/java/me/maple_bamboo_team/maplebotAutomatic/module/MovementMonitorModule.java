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
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * 实现位置监控指令。
 * 已修改：增加外部接口供 JumpDriveModule 调用，不再自动发送抵达消息。
 */
public class MovementMonitorModule implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;

    // 目标范围的最小点和最大点 (两个对角点)
    private Vec3d minVec = null;
    private Vec3d maxVec = null;

    // 配置项：从配置文件加载
    private double allowedError;
    private String monitorCommandName;

    // 【移除】不再需要 ARRIVAL_MESSAGE，因为 JumpDriveModule 会发送反馈
    // private static final String ARRIVAL_MESSAGE = "已抵达目标位置: ";

    @Override
    public String getName() {
        return "MovementMonitorModule";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // 【应用配置】
        this.allowedError = config.monitorSettings.allowedError;
        this.monitorCommandName = config.monitorSettings.monitorCommandName;

        if (config.enableMovementMonitorModule) {
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                registerCommands(dispatcher);
            });
        }
    }

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal(monitorCommandName)
                .then(ClientCommandManager.argument("x", StringArgumentType.string())
                        .then(ClientCommandManager.argument("y", StringArgumentType.string())
                                .then(ClientCommandManager.argument("z", StringArgumentType.string())
                                        .executes(this::executePosdCommand)
                                )))
        );
        sendFeedback(Text.literal("§a[Maplebot] 指令 /" + monitorCommandName + " 已成功注册。"));
    }

    private int executePosdCommand(CommandContext<FabricClientCommandSource> context) {
        if (!config.enableMovementMonitorModule) {
            sendFeedback(Text.literal("§e[Maplebot] MovementMonitorModule 已禁用。"));
            return 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            sendFeedback(Text.literal("§c[Maplebot] 玩家实体不可用。"));
            return 0;
        }

        try {
            String xStr = StringArgumentType.getString(context, "x");
            String yStr = StringArgumentType.getString(context, "y");
            String zStr = StringArgumentType.getString(context, "z");

            // 解析指令传入的中心点
            Vec3d centerVec = parseCoordinateToVec3d(xStr, yStr, zStr, player.getPos());

            // 调用公共方法设置目标，并让 Move/JumpDrive 模块来处理反馈
            setMonitorTarget(centerVec, allowedError);

            sendFeedback(Text.literal("§e[Maplebot] 目标范围已设定。中心点: §6" + centerVec.toString() + "§e。开始即时检测..."));

        } catch (Exception e) {
            sendFeedback(Text.literal("§c[Maplebot] 指令执行错误: 坐标解析失败。"));
            e.printStackTrace();
            return 0;
        }

        return 1;
    }

    // --- 【新增公共方法】：供 JumpDriveModule 调用 ---

    /**
     * 外部调用：设置目标并启动检测。
     * @param centerVec 目标中心坐标
     * @param error 允许的误差范围
     */
    public void setMonitorTarget(Vec3d centerVec, double error) {
        // 根据中心点和误差值，计算出范围的最小和最大点
        this.minVec = centerVec.add(-error, -error, -error);
        this.maxVec = centerVec.add(error, error, error);
        // 无需发送反馈，等待 JumpDriveModule 在 tick 中查询
    }

    /**
     * 外部调用：检查玩家是否已抵达目标并清空目标。
     * @return 如果玩家在范围内，返回 true 并停止检测；否则返回 false。
     */
    public boolean checkAndClearArrival() {
        if (client.player == null || minVec == null || maxVec == null) {
            return false;
        }

        Vec3d playerPos = client.player.getPos();

        // 判断玩家是否在范围内
        if (isPlayerInBox(playerPos)) {
            // 清理状态，停止检测
            minVec = null;
            maxVec = null;
            return true;
        }
        return false;
    }

    /**
     * 外部调用：立即停止所有检测并清空目标。
     */
    public void stopMonitoring() {
        minVec = null;
        maxVec = null;
    }

    // --- tick 方法修改：仅清除目标，不再发送消息 ---

    @Override
    public void tick() {
        // 如果模块未启用或没有设置目标，则立即返回
        if (!config.enableMovementMonitorModule || client.player == null || minVec == null || maxVec == null) {
            return;
        }

        // 每 tick 立即执行检测
        Vec3d playerPos = client.player.getPos();

        // 判断玩家是否在范围内
        if (isPlayerInBox(playerPos)) {
            // 【移除】不再发送 sendArrivalMessage(playerPos);
            // 而是等待 JumpDriveModule 在 tick 中通过 checkAndClearArrival() 查询并处理。

            // 保持 tick 中的清理状态，以防 JumpDriveModule 遗漏
            minVec = null;
            maxVec = null;
        }
    }

    /**
     * 判断玩家位置是否位于 minVec 和 maxVec 定义的 3D 盒内。
     */
    private boolean isPlayerInBox(Vec3d playerPos) {
        // 检查 X 轴
        if (playerPos.x < minVec.x || playerPos.x > maxVec.x) {
            return false;
        }
        // 检查 Y 轴
        if (playerPos.y < minVec.y || playerPos.y > maxVec.y) {
            return false;
        }
        // 检查 Z 轴
        if (playerPos.z < minVec.z || playerPos.z > maxVec.z) {
            return false;
        }
        return true;
    }

    // 【移除】sendArrivalMessage 方法

    // 解析坐标（支持 ~ 相对坐标）并转换为 Vec3d
    private Vec3d parseCoordinateToVec3d(String xStr, String yStr, String zStr, Vec3d currentPos) {
        double x = parseSingleCoordinate(xStr, currentPos.x);
        double y = parseSingleCoordinate(yStr, currentPos.y);
        double z = parseSingleCoordinate(zStr, currentPos.z);
        return new Vec3d(x, y, z);
    }

    private double parseSingleCoordinate(String coordStr, double currentCoord) {
        if (coordStr.startsWith("~")) {
            if (coordStr.length() > 1) {
                return currentCoord + Double.parseDouble(coordStr.substring(1));
            }
            return currentCoord; // 只有 ~ 符号，表示当前坐标
        }
        return Double.parseDouble(coordStr);
    }

    private void sendFeedback(Text text) {
        if (client.player != null) {
            client.player.sendMessage(text, false);
        }
    }

    @Override
    public void shutdown() {
        minVec = null;
        maxVec = null;
    }
}