package me.maple_bamboo_team.maplebotAutomatic.module;

import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.minecraft.client.MinecraftClient;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责管理所有功能模块的生命周期和执行。
 */
public class ModuleManager {

    private final List<IModule> allModules = new ArrayList<>();
    private final MaplebotConfig config;
    private final MinecraftClient client;

    // 【核心修复】：声明 JumpDriveModule 为 public final，供外部访问
    public final JumpDriveModule JUMP_DRIVE_MODULE;

    // 假设您还需要访问其他模块
    public final RotationModule ROTATION_MODULE;
    public final InteractModule INTERACT_MODULE;
    public final MovementMonitorModule MOVEMENT_MONITOR_MODULE;
    public final PlayerFinderModule PLAYER_FINDER_MODULE;

    public ModuleManager(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // --- 模块实例化 ---
        this.ROTATION_MODULE = new RotationModule();
        // 注意：InteractModule, MovementMonitorModule 和 PlayerFinderModule 需要被实例化
        this.INTERACT_MODULE = new InteractModule(this);
        this.MOVEMENT_MONITOR_MODULE = new MovementMonitorModule();
        this.PLAYER_FINDER_MODULE = new PlayerFinderModule();

        // 【核心修复】：实例化 JumpDriveModule
        // ⚠️ 注意：JumpDriveModule 的构造函数需要匹配您实际的实现。这里假设它不需要参数。
        this.JUMP_DRIVE_MODULE = new JumpDriveModule();

        // --- 模块注册 ---

        // 1. 注册 RotationModule
        registerModule(ROTATION_MODULE);

        // 2. 注册 InteractModule
        registerModule(INTERACT_MODULE);

        // 3. 注册 MovementMonitorModule
        registerModule(MOVEMENT_MONITOR_MODULE);

        // 4. 注册 PlayerFinderModule
        registerModule(PLAYER_FINDER_MODULE);

        // 【核心修复】：注册 JumpDriveModule
        registerModule(JUMP_DRIVE_MODULE);

        initializeModules();
    }

    private void registerModule(IModule module) {
        allModules.add(module);
        System.out.println("[Maplebot] Registered Module: " + module.getName());
    }

    private void initializeModules() {
        System.out.println("[Maplebot] Initializing " + allModules.size() + " modules...");
        for (IModule module : allModules) {
            module.initialize(client, config);
        }
    }

    /**
     * 在每个客户端刻调用，驱动所有模块。
     */
    public void onClientTick() {
        for (IModule module : allModules) {
            module.tick();
        }
    }

    /**
     * 关闭所有模块。
     */
    public void shutdownModules() {
        for (IModule module : allModules) {
            module.shutdown();
        }
        System.out.println("[Maplebot] All modules shut down.");
    }
}