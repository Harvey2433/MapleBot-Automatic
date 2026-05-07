package me.maple_bamboo_team.maplebotAutomatic.module;

import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * 自动重连模块
 * 级别：底层基础模块 (忽略业务锁，始终运行)
 * 功能：断线后按设定延迟重新连接，并维护业务锁的初始状态。
 */
public class AutoReconnect implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;

    private ServerInfo lastServer = null;
    private boolean isReconnecting = false;

    private long targetReconnectTime = 0;
    private int lastLoggedSecond = -1;

    @Override
    public String getName() {
        return "AutoReconnect";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // 1. 监听加入服务器
        ClientPlayConnectionEvents.JOIN.register((handler, sender, clientInstance) -> {
            isReconnecting = false;
            targetReconnectTime = 0;
            lastLoggedSecond = -1;

            if (clientInstance.getCurrentServerEntry() != null) {
                this.lastServer = clientInstance.getCurrentServerEntry();
            }

            // 【黑子提醒】：记得在 ModuleManager 里加上 `public static boolean isBusinessLocked = true;` 呐！
            ModuleManager.isBusinessLocked = true;
        });

        // 2. 监听断开连接
        ClientPlayConnectionEvents.DISCONNECT.register((handler, clientInstance) -> {
            if (lastServer != null && config.enableAutoReconnect) {
                isReconnecting = true;

                int delaySeconds = config.autoReconnectDelaySeconds;
                targetReconnectTime = System.currentTimeMillis() + (delaySeconds * 1000L);
                lastLoggedSecond = -1;

                System.out.println("[AutoReconnect] 连接已断开, 重连至: " + lastServer.address);
            }
        });

        // 3. 独立 Tick，无视 client.player == null
        ClientTickEvents.END_CLIENT_TICK.register(clientInstance -> {
            this.independentTick();
        });
    }

    @Override
    public void tick() {
        // 大管家的 tick 已经被抛弃啦！因为断线时大管家根本不会调用这里！
    }

    /**
     * 专属的独立 Tick，随时随地都在运行
     */
    private void independentTick() {
        if (!config.enableAutoReconnect || !isReconnecting || lastServer == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long remainingMillis = targetReconnectTime - currentTime;

        int remainingSeconds = (int) Math.ceil(remainingMillis / 1000.0);
        if (remainingSeconds < 0) remainingSeconds = 0;

        if (remainingSeconds != lastLoggedSecond && remainingSeconds > 0) {
            System.out.println("[AutoReconnect] " + remainingSeconds + "秒后尝试重新连接");
            lastLoggedSecond = remainingSeconds;
        }

        if (currentTime >= targetReconnectTime) {
            isReconnecting = false;
            lastLoggedSecond = -1;

            System.out.println("[AutoReconnect] 正在连接");

            executeReconnect();
        }
    }

    private void executeReconnect() {
        client.execute(() -> {
            if (lastServer != null) {
                ServerAddress address = ServerAddress.parse(lastServer.address);

                // 不使用 Mixin，直接调用原生 GUI 切换，完美衔接！
                ConnectScreen.connect(
                        new MultiplayerScreen(new TitleScreen()),
                        client,
                        address,
                        lastServer,
                        false,
                        null
                );
            }
        });
    }

    @Override
    public void shutdown() {
        isReconnecting = false;
        lastServer = null;
    }
}