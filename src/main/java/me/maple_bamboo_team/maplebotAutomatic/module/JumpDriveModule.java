package me.maple_bamboo_team.maplebotAutomatic.module;

import me.maple_bamboo_team.maplebotAutomatic.client.JumpDriveMessageParser;
import me.maple_bamboo_team.maplebotAutomatic.config.ConfigManager;
import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import me.maple_bamboo_team.maplebotAutomatic.module.JumpDrive.PearlPosition;
import me.maple_bamboo_team.maplebotAutomatic.module.JumpDrive.PearlPosition.PlayerPearlData;
import me.maple_bamboo_team.maplebotAutomatic.module.detection.IDetectionService;
import me.maple_bamboo_team.maplebotAutomatic.module.detection.InternalDetectionService;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JumpDrive (回城) 核心模块
 * 负责处理回城请求、状态机管理和初始化位置 (InitPos) 维护
 */
public class JumpDriveModule implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;
    private final Random random = new Random();

    private IDetectionService detectionService;
    private Pattern jumpDriveCommandPattern;
    private JumpDriveMessageParser messageParser;

    private PearlPosition pearlData;
    private Path pearlFilePath;

    private final Queue<String> requestQueue = new LinkedList<>();
    private String currentProcessingPlayer = null;
    private PlayerPearlData currentTargetPearl = null;
    private Vec3d currentTargetMove = null;

    private Vec3d lastPlayerPos = null;
    private int stationaryTicks = 0;
    private int dynamicTimeoutTicks = 0;
    private String playerDiedDuringProcessing = null;

    // 核心状态控制
    private boolean startupCheckDone = false;
    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();
    private final Map<String, Long> ignoredMap = new ConcurrentHashMap<>();

    // 重试逻辑状态
    private final Map<String, Integer> playerPearlIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> excludedPearlIndices = new ConcurrentHashMap<>();
    private final Map<String, Long> exclusionResetMap = new ConcurrentHashMap<>();
    private String retryWaitPlayer = null;
    private long retryWaitTimeoutTimestamp = 0;

    private final AtomicInteger tickTimer = new AtomicInteger(0);

    // 状态阶段定义: Phase 1/2 (Init Move for request) 已被移除
    private int phase = 0; // 0: Idle/Ready

    private static final int PHASE_IDLE_MOVE = -1; // 返回初始化位置 (InitPos)
    private static final int PHASE_MOVE_TO_PEARL_START = 3; // 启动 GOTO 珍珠位置
    private static final int PHASE_MOVE_TO_PEARL_GOTO = 4;  // GOTO 珍珠位置进行中
    private static final int PHASE_PRE_MUSE = 5;          // 抵达后, 运行 /muse 前的延迟
    private static final int PHASE_CHECK_PLAYER = 6;     // 执行玩家检测
    private static final int PHASE_COMPLETE = 7;         // 流程完成
    private static final int PHASE_RETRY_WAIT = 65;      // 等待重试回复

    // 配置项
    private int cooldownMs, ignoreMs, stationaryToleranceTicks, timeoutAfterStationaryTicks;
    private boolean enableJumpDriveRetry;
    private int retryWaitTimeoutMs, excludedPearlResetMs, playerCheckWaitTicks, preMuseDelayTicks;
    private int queueDelayTicks, initMoveDelayTicks, detectionStartDelayTicks, yTolerance;
    private String jumpDriveMessagePrefix, deathCancelMessage, retryTimeoutMessage;
    private int randomSuffixLength;


    private void logDebug(String message) {
        System.out.println("[JumpDrive DEBUG] " + message);
    }

    @Override
    public String getName() {
        return "JumpDriveModule";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;
        this.detectionService = new InternalDetectionService(client);

        if (client.runDirectory != null) {
            this.pearlFilePath = Paths.get(client.runDirectory.getAbsolutePath(), config.jumpDriveSettings.pearlFileName);
        }

        reloadConfigAndData();
        resetModuleState();

        if (config.enableJumpDriveModule) {
            logDebug("模块已启用指令: /" + config.jumpDriveSettings.jumpDriveCommand);
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                dispatcher.register(ClientCommandManager.literal(config.jumpDriveSettings.jumpDriveCommand)
                        .then(ClientCommandManager.literal("reload").executes(context -> {
                            try {
                                this.config = ConfigManager.reloadAndGetConfig();
                            } catch (Exception e) {
                                sendFeedback(Text.literal("§c[JumpDrive] 配置文件重载失败: " + e.getMessage()));
                                return 0;
                            }
                            reloadConfigAndData();
                            resetModuleState();
                            sendFeedback(Text.literal("§a[JumpDrive] 模块已重置, 配置已刷新"));
                            return 1;
                        }))
                );
            });
        } else {
            logDebug("模块已禁用 (配置)");
        }
    }

    private void compileRegex() {
        try {
            Pattern privateMessageLogPattern = Pattern.compile(config.jumpDriveSettings.privateMessageLogRegex);
            this.messageParser = new JumpDriveMessageParser(privateMessageLogPattern);
        } catch (Exception e) {
            this.messageParser = null;
        }
        try {
            this.jumpDriveCommandPattern = Pattern.compile(
                    config.jumpDriveSettings.jumpDriveMessageRegex,
                    Pattern.CASE_INSENSITIVE
            );
        } catch (Exception e) {
            this.jumpDriveCommandPattern = Pattern.compile("回城", Pattern.CASE_INSENSITIVE);
        }
    }

    private void reloadConfigAndData() {
        loadPearlData();
        compileRegex();

        // 加载配置值
        var s = config.jumpDriveSettings;
        this.cooldownMs = s.cooldownMs;
        this.ignoreMs = s.ignoreMs;
        this.stationaryToleranceTicks = s.stationaryToleranceTicks;
        this.timeoutAfterStationaryTicks = s.timeoutAfterStationaryTicks;
        this.playerCheckWaitTicks = s.playerCheckWaitTicks;
        this.preMuseDelayTicks = s.preMuseDelayTicks;
        this.queueDelayTicks = s.queueDelayTicks;
        this.detectionStartDelayTicks = s.detectionStartDelayTicks;
        this.yTolerance = s.yTolerance;
        this.jumpDriveMessagePrefix = s.jumpDriveMessagePrefix.trim();
        this.randomSuffixLength = s.randomSuffixLength;
        this.deathCancelMessage = s.deathCancelMessage;
        this.retryTimeoutMessage = s.retryTimeoutMessage;
        this.enableJumpDriveRetry = s.enableJumpDriveRetry;
        this.retryWaitTimeoutMs = s.retryWaitTimeoutMs;
        this.excludedPearlResetMs = s.excludedPearlResetMs;

        this.detectionService.updateDetectionParameters(
                config.jumpDriveSettings.allowedError,
                config.finderSettings.maxSearchDistance
        );
        logDebug("配置依赖数据已重新加载");
    }

    private void resetModuleState() {
        currentProcessingPlayer = null;
        currentTargetPearl = null;
        currentTargetMove = null;
        requestQueue.clear();
        phase = 0;
        tickTimer.set(0);

        lastPlayerPos = null;
        stationaryTicks = dynamicTimeoutTicks = 0;
        playerDiedDuringProcessing = null;

        startupCheckDone = false; // 允许下次进入服务器时执行一次检查

        ignoredMap.clear();
        cooldownMap.clear();
        playerPearlIndexMap.clear();
        excludedPearlIndices.clear();
        exclusionResetMap.clear();
        retryWaitPlayer = null;
        retryWaitTimeoutTimestamp = 0;

        logDebug("模块运行状态已重置");
    }

    private boolean isAtInitPos() {
        if (pearlData == null || pearlData.initPosition == null || client.player == null) {
            return false;
        }
        // 使用配置的误差阈值
        return client.player.getPos().distanceTo(pearlData.initPosition) <= detectionService.getAllowedError() + 0.5;
    }

    @Override
    public void tick() {
        if (!config.enableJumpDriveModule || client.player == null) return;

        // 1. 首次启动的 InitPos 检查 (仅执行一次)
        // 玩家首次进入服务器/存档且位置没有位于初始化位置时执行一次
        if (client.world != null && !startupCheckDone) {
            startupCheckDone = true;
            if (pearlData != null && pearlData.initPosition != null && phase == 0 && !isAtInitPos()) {
                logDebug("启动检查：首次进入服务器/存档且不在 InitPos, 开始前往 InitPos (Phase -1)所有请求等待");
                currentTargetMove = pearlData.initPosition;
                String initPosString = String.format("%.0f %.0f %.0f", currentTargetMove.x, currentTargetMove.y, currentTargetMove.z);
                client.player.networkHandler.sendChatMessage("#goto " + initPosString);

                phase = PHASE_IDLE_MOVE;
                lastPlayerPos = client.player.getPos();
                stationaryTicks = dynamicTimeoutTicks = 0;
            } else {
                logDebug("启动检查完成：已在 InitPos 或 InitPos 未配置");
            }
        }

        long currentTime = System.currentTimeMillis();
        // 排除列表复位逻辑
        exclusionResetMap.entrySet().removeIf(entry -> {
            if (currentTime >= entry.getValue()) {
                excludedPearlIndices.remove(entry.getKey());
                logDebug("玩家 " + entry.getKey() + " 的珍珠排除列表已复位");
                return true;
            }
            return false;
        });

        // 死亡检测和复活反馈
        if (client.player.isDead()) {
            if (phase != 0) {
                retryWaitPlayer = null;
                playerDiedDuringProcessing = currentProcessingPlayer;
                client.player.networkHandler.sendChatCommand("#goto cancel");
                finishProcessing(currentProcessingPlayer, true);
            }
            return;
        } else if (playerDiedDuringProcessing != null) {
            String diedPlayer = playerDiedDuringProcessing;
            playerDiedDuringProcessing = null;
            client.player.networkHandler.sendChatMessage(diedPlayer + " " + deathCancelMessage);
            if (!requestQueue.isEmpty()) tickTimer.set(1);
        }

        // 重试等待超时检测 (Phase 65)
        if (phase == PHASE_RETRY_WAIT) {
            if (currentTime >= retryWaitTimeoutTimestamp) {
                if (retryWaitPlayer != null) {
                    client.player.networkHandler.sendChatMessage(retryWaitPlayer + " " + retryTimeoutMessage);
                    finishProcessing(retryWaitPlayer, true);
                } else {
                    phase = 0;
                }
                return;
            }
        }

        // GOTO 移动阶段 (Phase 4 / -1)
        if (phase == PHASE_MOVE_TO_PEARL_GOTO || phase == PHASE_IDLE_MOVE) {
            Vec3d currentPos = client.player.getPos();

            // 动态运动检测和超时
            if (lastPlayerPos != null) {
                if (currentPos.distanceTo(lastPlayerPos) > 0.01) {
                    stationaryTicks = dynamicTimeoutTicks = 0;
                } else {
                    stationaryTicks++;
                    if (stationaryTicks > stationaryToleranceTicks) {
                        dynamicTimeoutTicks++;
                        if (dynamicTimeoutTicks > timeoutAfterStationaryTicks) {
                            client.player.networkHandler.sendChatCommand("goto cancel");
                            if (phase != PHASE_IDLE_MOVE) {
                                client.player.networkHandler.sendChatCommand(buildJumpMessage(currentProcessingPlayer + " 跃迁引擎故障：导航系统无响应或目标不可达"));
                                finishProcessing(currentProcessingPlayer, true);
                            } else {
                                phase = 0;
                                currentTargetMove = null;
                            }
                            return;
                        }
                    }
                }
            }
            lastPlayerPos = currentPos;

            // 抵达检测
            boolean isTaskPhaseArrival = phase == PHASE_MOVE_TO_PEARL_GOTO && tickTimer.get() == 0;
            boolean isIdlePhaseArrival = phase == PHASE_IDLE_MOVE;

            if ((isTaskPhaseArrival || isIdlePhaseArrival) && detectionService.isArrivalConfirmed(currentTargetMove)) {
                lastPlayerPos = null;
                stationaryTicks = dynamicTimeoutTicks = 0;

                if (phase == PHASE_IDLE_MOVE) {
                    client.player.sendMessage(Text.literal("§a[Maplebot] 已抵达初始化位置, 等待回城请求"), false);
                    phase = 0;
                    currentTargetMove = null;
                    return;
                } else { // phase == PHASE_MOVE_TO_PEARL_GOTO (Phase 4)
                    client.player.sendMessage(Text.literal("§a[Maplebot] 已抵达目标位置"), false);
                    tickTimer.set(preMuseDelayTicks);
                    phase = PHASE_PRE_MUSE; // Phase 5
                }
            }
        }

        if (tickTimer.get() > 0) {
            tickTimer.decrementAndGet();
        }

        // 核心状态机 (Phase 3, 5, 6, 7)
        // 其他时候不执行（包括处理下一个请求的时候）
        if (tickTimer.get() == 0 && phase != PHASE_MOVE_TO_PEARL_GOTO && phase != PHASE_RETRY_WAIT && phase != PHASE_IDLE_MOVE && phase != 0) {
            handlePhase();
        }

        // 队列处理: 仅在完全空闲 (phase == 0) 时启动下一个请求
        if (currentProcessingPlayer == null && client.player != null && phase == 0 && tickTimer.get() == 0) {
            if (!requestQueue.isEmpty()) {
                currentProcessingPlayer = requestQueue.poll();
                logDebug("队列延迟/闲置结束, 开始处理队列中的玩家: " + currentProcessingPlayer);
                startJumpDrive(currentProcessingPlayer);
            }
        }
    }


    private void handlePhase() {
        if (currentProcessingPlayer == null || currentTargetPearl == null) {
            if (phase != 0 && phase != PHASE_IDLE_MOVE) phase = 0;
            return;
        }

        String playerName = currentProcessingPlayer;

        switch (phase) {
            // PHASE 1 (Move to InitPos for request) 已被移除

            case PHASE_MOVE_TO_PEARL_START: // 3: 启动回城移动 (直接从第二步开始)
                currentTargetMove = currentTargetPearl.position;
                String pearlPosString = String.format("%.0f %.0f %.0f", currentTargetMove.x, currentTargetMove.y, currentTargetMove.z);

                client.player.networkHandler.sendChatMessage("#goto " + pearlPosString);
                client.player.networkHandler.sendChatCommand(buildJumpMessage("跃迁引擎正在启动..."));
                tickTimer.set(detectionStartDelayTicks);
                phase = PHASE_MOVE_TO_PEARL_GOTO; // 4
                lastPlayerPos = client.player.getPos();
                stationaryTicks = dynamicTimeoutTicks = 0;
                break;

            case PHASE_PRE_MUSE: // 5: 运行 /muse 并等待玩家检测延迟
                String musePosString = String.format("%.0f %.0f %.0f", currentTargetMove.x, currentTargetMove.y, currentTargetMove.z);
                client.player.networkHandler.sendChatCommand(config.interactSettings.museCommandName + " " + musePosString);

                tickTimer.set(playerCheckWaitTicks);
                phase = PHASE_CHECK_PLAYER; // 6
                break;

            case PHASE_CHECK_PLAYER: // 6: 执行玩家检测
                if (detectionService.isPlayerReturned(playerName)) {
                    client.player.networkHandler.sendChatCommand(buildJumpMessage("尊敬的" + playerName + ", 您已成功跃迁至指定位置"));
                    phase = PHASE_COMPLETE; // 7
                    handlePhase();
                } else {
                    int currentIdx = playerPearlIndexMap.getOrDefault(playerName, -1);
                    PlayerPearlData nextPearl = findNextAvailablePearl(playerName, currentIdx + 1);

                    if (!enableJumpDriveRetry || nextPearl == null) {
                        String failureMessage = nextPearl == null ? "已尝试所有可用的跃迁点" : "重试逻辑已被禁用";
                        client.player.networkHandler.sendChatCommand(buildJumpMessage(playerName + " 您的传送似乎失败, 因为" + failureMessage + ""));
                        finishProcessing(playerName, true);
                        return;
                    }

                    excludedPearlIndices.computeIfAbsent(playerName, k -> ConcurrentHashMap.newKeySet()).add(currentIdx);
                    exclusionResetMap.put(playerName, System.currentTimeMillis() + excludedPearlResetMs);

                    retryWaitPlayer = playerName;
                    retryWaitTimeoutTimestamp = System.currentTimeMillis() + retryWaitTimeoutMs;

                    long waitSeconds = retryWaitTimeoutMs / 1000;
                    client.player.networkHandler.sendChatCommand(buildJumpMessage(playerName + "跃迁失败, 未在目标点检测到您是否尝试下一个跃迁点？(请在 " + waitSeconds + " 秒内私信回复 Y 或 N)"));

                    phase = PHASE_RETRY_WAIT;
                }
                break;

            case PHASE_COMPLETE: // 7: 流程完成, 清理状态
                finishProcessing(playerName);
                break;
        }
    }

    // --- 启动和重试逻辑 ---

    private void startJumpDrive(String playerName) {
        PlayerPearlData initialPearl = findNextAvailablePearl(playerName, 0);

        if (initialPearl == null) {
            client.player.networkHandler.sendChatCommand(buildJumpMessage(" 跃迁引擎启动失败：未找到可用的回城坐标"));
            finishProcessing(playerName, true);
            return;
        }

        playerPearlIndexMap.put(playerName, initialPearl.id);
        currentTargetPearl = initialPearl;
        currentProcessingPlayer = playerName;

        // ** 队列中有待处理的请求时, 直接从 Phase 3 (Move to Pearl) 开始 **
        phase = PHASE_MOVE_TO_PEARL_START; // 3

        handlePhase();
    }

    private void startRetryJumpDrive(String playerName) {
        int currentIdx = playerPearlIndexMap.getOrDefault(playerName, -1);
        PlayerPearlData nextPearl = findNextAvailablePearl(playerName, currentIdx + 1);

        if (nextPearl == null) {
            client.player.networkHandler.sendChatCommand(buildJumpMessage(playerName + " 跃迁引擎启动失败：已尝试所有可用跃迁点"));
            finishProcessing(playerName, true);
            return;
        }

        playerPearlIndexMap.put(playerName, nextPearl.id);
        currentTargetPearl = nextPearl;

        client.player.networkHandler.sendChatCommand(buildJumpMessage(playerName + " 正在尝试切换到下一个跃迁点"));
        phase = PHASE_MOVE_TO_PEARL_START; // 3
        handlePhase();
    }

    private PlayerPearlData findNextAvailablePearl(String playerName, int startIndex) {
        if (pearlData == null) return null;

        String trimmedPlayerName = playerName.trim();
        Set<Integer> excluded = excludedPearlIndices.getOrDefault(trimmedPlayerName, Collections.emptySet());

        List<PlayerPearlData> allPearls = pearlData.pearlData.stream()
                .filter(data -> data.playerName.equalsIgnoreCase(trimmedPlayerName))
                .collect(Collectors.toList());

        for (int i = startIndex; i < allPearls.size(); i++) {
            PlayerPearlData data = allPearls.get(i);
            if (!excluded.contains(i) && isPosInActivityRange(data.position)) {
                data.id = i;
                return data;
            }
        }
        return null;
    }

    // --- 外部事件接口 ---

    public JumpDriveMessageParser getMessageParser() {
        return this.messageParser;
    }

    public void onPrivateMessage(String sender, String message) {
        if (!config.enableJumpDriveModule || client.player == null) return;

        long currentTime = System.currentTimeMillis();
        String trimmedMessage = message.trim();

        // 1. 重试回复处理
        if (phase == PHASE_RETRY_WAIT && sender.equalsIgnoreCase(retryWaitPlayer)) {
            String upperMessage = trimmedMessage.toUpperCase();
            if (upperMessage.equals("Y")) {
                retryWaitPlayer = null;
                retryWaitTimeoutTimestamp = 0;
                startRetryJumpDrive(sender);
                return;
            } else if (upperMessage.equals("N")) {
                client.player.networkHandler.sendChatCommand(buildJumpMessage(sender + " 跃迁请求已取消, 请稍后重试"));
                retryWaitPlayer = null;
                retryWaitTimeoutTimestamp = 0;
                finishProcessing(sender, true);
                return;
            }
            return;
        }

        // 2. 新的回城请求
        boolean commandMatches = (jumpDriveCommandPattern != null && jumpDriveCommandPattern.matcher(trimmedMessage).matches()) || trimmedMessage.equalsIgnoreCase("回城");

        if (!commandMatches || sender.equals(currentProcessingPlayer) || requestQueue.contains(sender)) return;

        if ((cooldownMap.containsKey(sender) && cooldownMap.get(sender) > currentTime) ||
                (ignoredMap.containsKey(sender) && ignoredMap.get(sender) > currentTime)) {
            return;
        }

        if (findNextAvailablePearl(sender, 0) == null) {
            String msg = isPlayerInPearlFile(sender) ? "所有回城坐标已被暂时排除, 请稍后重试" : "您需要初始化回城坐标";
            ignoredMap.put(sender, currentTime + ignoreMs);
            client.player.networkHandler.sendChatCommand(buildJumpMessage(" 跃迁引擎初始化失败：" + msg));
            return;
        }

        if (currentProcessingPlayer != null) {
            client.player.networkHandler.sendChatCommand(buildJumpMessage("请稍后.."));
        }
        requestQueue.add(sender);
    }

    // --- 清理和辅助逻辑 ---

    private void finishProcessing(String playerName, boolean isFailure) {
        logDebug(isFailure ? "故障处理完成" : "流程成功完成" + "玩家 " + playerName + " 状态清理");

        currentProcessingPlayer = null;
        currentTargetPearl = null;
        currentTargetMove = null;

        if (isFailure && playerName != null) {
            cooldownMap.put(playerName, System.currentTimeMillis() + cooldownMs);
            logDebug("玩家 " + playerName + " 已进入冷却期");
        }

        playerPearlIndexMap.remove(playerName);
        retryWaitPlayer = null;
        retryWaitTimeoutTimestamp = 0;
        lastPlayerPos = null;
        stationaryTicks = dynamicTimeoutTicks = 0;

        // ** 检测队列剩余请求 **
        if (!requestQueue.isEmpty()) {
            // ** 队列中仍有请求 (≥1), 准备处理下一个请求 **
            if (isFailure) tickTimer.set(1);
            else tickTimer.set(queueDelayTicks);

            String nextPlayer = requestQueue.peek();
            if (nextPlayer != null) {
                client.player.networkHandler.sendChatCommand(buildJumpMessage(nextPlayer + " 跃迁引擎已恢复, 您的请求正在处理"));
            }
            phase = 0; // 切换到空闲/就绪状态, 让 tick() 在下一刻拉取新任务
        }
        // ** 队列为空 (≤0), 执行返回初始化位置逻辑 **
        else {
            if (pearlData != null && pearlData.initPosition != null && !isAtInitPos()) {
                logDebug("任务结束, 队列为空：不在 InitPos, 启动 Idle Move (Phase -1)");
                currentTargetMove = pearlData.initPosition;
                String initPosString = String.format("%.0f %.0f %.0f", currentTargetMove.x, currentTargetMove.y, currentTargetMove.z);
                client.player.networkHandler.sendChatMessage("#goto " + initPosString);

                phase = PHASE_IDLE_MOVE;
                lastPlayerPos = client.player.getPos();
                stationaryTicks = dynamicTimeoutTicks = 0;
            } else {
                phase = 0; // 彻底空闲待命
            }
        }
    }

    private void finishProcessing(String playerName) {
        finishProcessing(playerName, false);
    }

    private boolean isPlayerInPearlFile(String playerName) {
        if (pearlData == null) return false;
        return pearlData.pearlData.stream()
                .anyMatch(data -> data.playerName.equalsIgnoreCase(playerName.trim()));
    }

    private boolean isPosInActivityRange(Vec3d pos) {
        if (pearlData.activityMin == null || pearlData.activityMax == null) return true;

        int minX = Math.min(pearlData.activityMin.getX(), pearlData.activityMax.getX());
        int maxX = Math.max(pearlData.activityMin.getX(), pearlData.activityMax.getX());
        int minZ = Math.min(pearlData.activityMin.getZ(), pearlData.activityMax.getZ());
        int maxZ = Math.max(pearlData.activityMin.getZ(), pearlData.activityMax.getZ());
        int minY = Math.min(pearlData.activityMin.getY(), pearlData.activityMax.getY()) - yTolerance;
        int maxY = Math.max(pearlData.activityMin.getY(), pearlData.activityMax.getY()) + yTolerance;

        return pos.x >= minX && pos.x <= maxX &&
                pos.z >= minZ && pos.z <= maxZ &&
                pos.y >= minY && pos.y <= maxY;
    }

    private void loadPearlData() {
        if (pearlFilePath == null) return;
        try {
            if (!Files.exists(pearlFilePath)) {
                createDefaultPearlFile();
                return;
            }
            List<String> lines = Files.readAllLines(pearlFilePath);
            PearlPosition newPearlData = new PearlPosition();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("Activity:")) {
                    String[] parts = line.substring(9).trim().split("\\s+");
                    if (parts.length >= 6) {
                        newPearlData.activityMin = new BlockPos(
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        newPearlData.activityMax = new BlockPos(
                                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
                    }
                    continue;
                }

                if (line.startsWith("InitPos:")) {
                    String[] parts = line.substring(8).trim().split("\\s+");
                    if (parts.length >= 3) {
                        newPearlData.initPosition = new Vec3d(
                                Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()));
                    }
                    continue;
                }

                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 5) {
                    PlayerPearlData data = new PlayerPearlData();
                    data.id = Integer.parseInt(parts[0].trim());
                    data.playerName = parts[1].trim();
                    data.position = new Vec3d(
                            Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim()), Double.parseDouble(parts[4].trim()));
                    newPearlData.pearlData.add(data);
                }
            }
            this.pearlData = newPearlData;
        } catch (Exception e) {
            sendFeedback(Text.literal("§c[JumpDrive] 珍珠数据加载失败: " + e.getMessage()));
        }
    }

    private void createDefaultPearlFile() throws IOException {
        String defaultContent =
                "# 这是回城模块的珍珠坐标文件\n" +
                        "# Activity:X1 Y1 Z1 X2 Y2 Z2 (活动范围, 可选)\n" +
                        "Activity:1000 64 1000 2000 64 2000\n" +
                        "# InitPos:X Y Z (模块空闲时的返回坐标, 可选)\n" +
                        "InitPos:1500 65 1500\n" +
                        "\n" +
                        "# 示例玩家数据: 编号 玩家名 X Y Z\n" +
                        "1 Maple_Bamboo_Team 1500.5 65.0 1500.5\n";

        Files.writeString(pearlFilePath, defaultContent);
        logDebug("已创建默认珍珠文件: " + pearlFilePath.getFileName());
        loadPearlData();
    }

    private String generateRandomSuffix() {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(randomSuffixLength);
        for (int i = 0; i < randomSuffixLength; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }

    private String buildJumpMessage(String baseMessage) {
        String pureMessage = jumpDriveMessagePrefix + " " + baseMessage;
        return "msg " + currentProcessingPlayer + " " + pureMessage + " ";
    }

    private void sendFeedback(Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, false);
        }
    }

    @Override
    public void shutdown() {
        currentProcessingPlayer = null;
        requestQueue.clear();
        phase = 0;
    }

}