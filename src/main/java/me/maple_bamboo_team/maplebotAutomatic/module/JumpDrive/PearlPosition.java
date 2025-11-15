package me.maple_bamboo_team.maplebotAutomatic.module.JumpDrive;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/**
 * 封装 JumpDriveModule 从文件读取的珍珠坐标数据。
 * 适配新的文件格式:
 * - Activity:X1 Y1 Z1 X2 Y2 Z2
 * - InitPos:X Y Z  <-- 新增字段支持
 * - 编号 玩家名 X Y Z
 */
public class PearlPosition {

    /**
     * 【新增】：初始化移动的目标坐标 (来自 InitPos:X Y Z)
     */
    public Vec3d initPosition = null;

    // 活动范围的最小和最大坐标（可选）
    public BlockPos activityMin = null;
    public BlockPos activityMax = null;

    // 玩家各自的回城珍珠数据列表
    public final List<PlayerPearlData> pearlData = new ArrayList<>();

    // 内部类：单个玩家的珍珠数据
    public static class PlayerPearlData {
        public int id;
        public String playerName;
        public Vec3d position; // 目标回城坐标
    }
}