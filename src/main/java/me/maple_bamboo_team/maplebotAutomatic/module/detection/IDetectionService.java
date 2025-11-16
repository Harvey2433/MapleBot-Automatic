package me.maple_bamboo_team.maplebotAutomatic.module.detection;

import net.minecraft.util.math.Vec3d;

/**
 * 通用检测服务接口。用于抽象出模块内部的位置、抵达和玩家查找逻辑。
 */
public interface IDetectionService {

    /**
     * 检查当前玩家是否已抵达目标位置的误差范围内。
     *
     * @param targetPos 目标位置的 Vec3d。
     * @return 如果在误差范围内则返回 true。
     */
    boolean isArrivalConfirmed(Vec3d targetPos);

    /**
     * 检查目标玩家是否已在回城位置附近（即在互动范围内）。
     *
     * @param targetName 目标玩家的名称。
     * @return 如果玩家在附近则返回 true。
     */
    boolean isPlayerReturned(String targetName);

    /**
     * 设置/更新模块配置中的动态参数 (如误差阈值)。
     * * @param allowedError 允许的位置误差。
     * @param maxSearchDistance 允许的玩家查找距离。
     */
    void updateDetectionParameters(double allowedError, double maxSearchDistance);

    /**
     * 【新增】：获取当前允许的位置误差。
     *
     * @return 允许的位置误差值 (allowedError)。
     */
    double getAllowedError();
}