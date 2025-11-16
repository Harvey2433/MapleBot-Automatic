package me.maple_bamboo_team.maplebotAutomatic.module.detection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * JumpDrive 模块使用的内置检测服务实现。
 * 封装了位置抵达确认和玩家返回检测的逻辑。
 */
public class InternalDetectionService implements IDetectionService {

    private final MinecraftClient client;

    // 动态配置参数
    private double allowedError = 0.9;
    private double maxSearchDistance = 4.5;

    // 调试日志
    private void logDebug(String message) {
        System.out.println("[JumpDrive/Detection DEBUG] " + message);
    }

    public InternalDetectionService(MinecraftClient client) {
        this.client = client;
    }

    @Override
    public void updateDetectionParameters(double allowedError, double maxSearchDistance) {
        this.allowedError = allowedError;
        this.maxSearchDistance = maxSearchDistance;
        logDebug(String.format("检测参数已更新: AllowedError=%.2f, MaxSearchDistance=%.2f", allowedError, maxSearchDistance));
    }

    /**
     * 【新增】：获取当前允许的位置误差。
     */
    @Override
    public double getAllowedError() {
        return this.allowedError;
    }

    /**
     * 【原 JumpDriveModule.isArrivalConfirmed()】
     */
    @Override
    public boolean isArrivalConfirmed(Vec3d targetPos) {
        if (client.player == null) {
            logDebug("抵达检测失败：客户端玩家状态不可用。");
            return false;
        }

        Vec3d currentPos = client.player.getPos();
        double distance = currentPos.distanceTo(targetPos);

        // 仅在检测到抵达或距离较远时（例如距离 > 误差阈值的两倍）进行日志记录
        if (distance <= allowedError || distance > allowedError * 2) {
            logDebug("[Detection DEBUG] 正在内部检测... 距离目标中心: " + String.format("%.3f", distance) + " 方块。 (误差阈值: " + allowedError + ")");
        }

        return distance <= allowedError;
    }

    /**
     * 【原 JumpDriveModule.isPlayerReturned()】
     */
    @Override
    public boolean isPlayerReturned(String targetName) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
            logDebug("检测玩家返回失败：客户端状态不可用。");
            return false;
        }

        ClientPlayerEntity player = client.player;

        // 玩家列表条目检查 (不一定需要，但更安全)
        PlayerListEntry targetEntry = client.getNetworkHandler().getPlayerListEntry(targetName);
        if (targetEntry == null) {
            logDebug("检测玩家返回失败：玩家不在线。");
            return false;
        }

        Vec3d playerPos = player.getPos();

        // 遍历世界实体进行距离检查
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof PlayerEntity otherPlayer && !otherPlayer.equals(player)) {
                if (otherPlayer.getName().getString().equalsIgnoreCase(targetName)) {
                    double distance = playerPos.distanceTo(otherPlayer.getPos());

                    logDebug(String.format("玩家 %s 距离: %.2f (阈值: %.2f)", targetName, distance, maxSearchDistance));

                    if (distance <= maxSearchDistance) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}