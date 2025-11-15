package me.maple_bamboo_team.maplebotAutomatic.module;

import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.minecraft.client.MinecraftClient;

/**
 * 所有功能模块的通用接口。
 */
public interface IModule {

    /**
     * 获取模块的唯一标识名称。
     */
    String getName();

    /**
     * 初始化模块，获取客户端和配置实例。
     */
    void initialize(MinecraftClient client, MaplebotConfig config);

    /**
     * 在每个客户端游戏刻 (Client Tick) 调用。
     */
    void tick();

    /**
     * 模块关闭或卸载时调用。
     */
    void shutdown();
}