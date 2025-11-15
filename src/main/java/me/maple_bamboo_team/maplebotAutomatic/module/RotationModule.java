package me.maple_bamboo_team.maplebotAutomatic.module;

import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 负责平滑旋转和视角管理的模块。
 * 实现了平滑步进逻辑 (injectStep) 和角度计算 (getRotation)，参考了 RotationManager。
 */
public class RotationModule implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;

    // 当前瞄准目标 (Vec3d) - 设为 public 供 InteractModule 访问瞄准点和状态检查
    public Vec3d targetVec = null;

    // 瞄准速度 (步进值)
    private float aimSpeed = 0.5f;

    /**
     * 设置一个 Vec3d 目标，模块将在接下来的 tick 中平滑地转向它。
     * @param vec 目标坐标
     * @param speed 旋转步进值 (0.01f 到 1.0f)
     */
    public void lookAt(Vec3d vec, float speed) {
        this.targetVec = vec;
        this.aimSpeed = MathHelper.clamp(speed, 0.01f, 1.0f);
    }

    /**
     * 检查模块是否正在执行旋转操作。
     */
    public boolean isRotating() {
        return targetVec != null;
    }

    // ===========================

    @Override
    public String getName() {
        return "RotationModule";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public void tick() {
        if (client.player == null || targetVec == null) {
            return;
        }

        // 1. 计算目标角度
        float[] targetAngle = getRotation(targetVec);

        // 2. 注入步进 (平滑插值)
        float[] newAngle = injectStep(targetAngle, aimSpeed);

        // 3. 应用旋转
        client.player.setYaw(newAngle[0]);
        client.player.setPitch(newAngle[1]);

        // 4. 检查是否到达目标
        // 使用非常小的阈值 0.001f 来判断是否停止转动
        if (MathHelper.angleBetween(newAngle[0], targetAngle[0]) < 0.001f && Math.abs(newAngle[1] - targetAngle[1]) < 0.001f) {
            // 如果旋转已到达目标，则清除目标，停止转头
            targetVec = null;
        }
    }

    @Override
    public void shutdown() {
        // 模块关闭时，清理目标
        targetVec = null;
    }

    // === 核心计算逻辑 ===

    /**
     * 计算玩家眼睛到目标 Vec3d 的 Yaw 和 Pitch。
     */
    public float[] getRotation(Vec3d vec) {
        if (client.player == null) {
            return new float[]{0, 0};
        }
        Vec3d eyesPos = client.player.getEyePos();

        double diffX = vec.x - eyesPos.x;
        double diffY = vec.y - eyesPos.y;
        double diffZ = vec.z - eyesPos.z;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        // Yaw: atan2(diffZ, diffX) - 90.0f
        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0f;
        // Pitch: (-Math.toDegrees(Math.atan2(diffY, diffXZ)))
        float pitch = (float) (-Math.toDegrees(Math.atan2(diffY, diffXZ)));

        return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.wrapDegrees(pitch)};
    }

    /**
     * 平滑地步进到目标角度。
     * @param targetAngle [Yaw, Pitch]
     * @param steps 步进值 (0.01f 到 1.0f)
     * @return 步进后的角度 [Yaw, Pitch]
     */
    public float[] injectStep(float[] targetAngle, float steps) {
        if (client.player == null) {
            return targetAngle;
        }

        steps = MathHelper.clamp(steps, 0.01f, 1.0f);

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        // Yaw 平滑
        float diffYaw = MathHelper.angleBetween(targetAngle[0], currentYaw);
        float yawStepLimit = 180 * steps;

        if (Math.abs(diffYaw) > yawStepLimit) {
            targetAngle[0] = currentYaw + (diffYaw > 0 ? yawStepLimit : -yawStepLimit);
        }

        // Pitch 平滑
        float diffPitch = targetAngle[1] - currentPitch;
        float pitchStepLimit = 90 * steps;

        if (Math.abs(diffPitch) > pitchStepLimit) {
            targetAngle[1] = currentPitch + (diffPitch > 0 ? pitchStepLimit : -pitchStepLimit);
        }

        // 限制 Pitch
        targetAngle[1] = MathHelper.clamp(targetAngle[1], -90.0F, 90.0F);

        return new float[]{MathHelper.wrapDegrees(targetAngle[0]), targetAngle[1]};
    }
}