package me.maple_bamboo_team.maplebotAutomatic.module;

import me.maple_bamboo_team.maplebotAutomatic.client.JumpDriveMessageParser;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JumpDrive (回城) 核心模块，现已通过 IDetectionService 抽象出内部的位置抵达和玩家存在检测。
 */
public class JumpDriveModule implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;
    private final Random random = new Random();

    // 【检测服务实例】
    private IDetectionService detectionService;

    // 配置相关的 Pattern 和解析器实例
    private Pattern jumpDriveCommandPattern;
    private JumpDriveMessageParser messageParser; // 私有实例

    // 核心数据和文件路径
    private PearlPosition pearlData;
    private Path pearlFilePath;

    // 状态机和队列
    private final Queue<String> requestQueue = new LinkedList<>();
    private String currentProcessingPlayer = null;
    private PlayerPearlData currentTargetPearl = null;
    private Vec3d currentTargetMove = null;

    // 【动态超时检测状态】
    private Vec3d lastPlayerPos = null;
    private int stationaryTicks = 0;
    private int dynamicTimeoutTicks = 0;
    // 【死亡处理状态】用于在复活后发送反馈
    private String playerDiedDuringProcessing = null;

    // 冷却和拒绝列表
    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();
    private final Map<String, Long> ignoredMap = new ConcurrentHashMap<>();

    // 计时器和状态控制
    private final AtomicInteger tickTimer = new AtomicInteger(0);
    /** * phase 0: Idle
     * phase 1: Start Init Move (#goto <Init Pos>)
     * phase 2: Wait Init Move Arrival (Dynamic Timeout Check)
     * phase 3: Start Pearl Move (GOTO + Send Messages + Start Detection Delay)
     * phase 4: Wait Pearl Move Arrival (Dynamic Timeout Check)
     * phase 5: Run /muse and Wait Player Check Delay
     * phase 6: Check Player Returned & Finish
     */
    private int phase = 0;

    // 常量/配置值 (从配置中加载)
    private int cooldownMs;
    private int ignoreMs;
    private int stationaryToleranceTicks;
    private int timeoutAfterStationaryTicks;

    private static final int QUEUE_DELAY_TICKS = 100;
    private static final int DELAY_0_7S_TICKS = 14;
    private static final int INIT_MOVE_DELAY_TICKS = 10;
    private static final int DETECTION_START_DELAY_TICKS = 60;
    private static final int PLAYER_CHECK_WAIT_TICKS = 30;

    private static final int Y_TOLERANCE = 3;

    // --------------------------------------------------------------------
    private void logDebug(String message) {
        System.out.println("[JumpDrive DEBUG] " + message);
    }

    @Override
    public String getName() {
        return "JumpDriveModule";
    }

    // 编译配置中的正则
    private void compileRegex() {
        // 1. 编译私信解析用的正则
        try {
            Pattern privateMessageLogPattern = Pattern.compile(config.jumpDriveSettings.privateMessageLogRegex);
            this.messageParser = new JumpDriveMessageParser(privateMessageLogPattern);
            logDebug("已编译私信解析正则并初始化解析器。");
        } catch (Exception e) {
            logDebug("警告: 私信解析正则表达式编译失败或配置字段缺失，私信解析将无法工作。请检查 privateMessageLogRegex 配置。错误: " + e.getMessage());
            this.messageParser = null; // 禁用解析器
        }

        // 2. 编译回城指令正则
        try {
            this.jumpDriveCommandPattern = Pattern.compile(
                    config.jumpDriveSettings.jumpDriveMessageRegex,
                    Pattern.CASE_INSENSITIVE
            );
            logDebug("回城指令: " + config.jumpDriveSettings.jumpDriveMessageRegex);
        } catch (Exception e) {
            logDebug("警告: 非法回城指令，使用硬编码 '回城'. 错误: " + e.getMessage());
            this.jumpDriveCommandPattern = Pattern.compile("回城", Pattern.CASE_INSENSITIVE);
        }
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // 初始化检测服务
        this.detectionService = new InternalDetectionService(client);

        // 路径初始化
        if (client.runDirectory != null) {
            this.pearlFilePath = Paths.get(client.runDirectory.getAbsolutePath(), config.jumpDriveSettings.pearlFileName);
        }

        // 模块初始化时执行一次完整加载和复位
        reloadConfigAndData();
        resetModuleState();

        if (config.enableJumpDriveModule) {
            logDebug("模块已启用。指令: /" + config.jumpDriveSettings.jumpDriveCommand);

            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                dispatcher.register(ClientCommandManager.literal(config.jumpDriveSettings.jumpDriveCommand)
                        .executes(context -> {
                            sendFeedback(Text.literal("§e[JumpDrive] 运行 /" + config.jumpDriveSettings.jumpDriveCommand + " reload 清理拒绝列表。"));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("reload").executes(context -> {
                            // 重新加载配置依赖数据 (重新加载 Pearl.pos, 正则, 计时参数)
                            reloadConfigAndData();
                            // 重置所有状态和队列 (清空队列、冷却、状态机归零)
                            resetModuleState();

                            sendFeedback(Text.literal("§a[JumpDrive] 模块已重置，拒绝列表已清理，珍珠数据已重新加载，配置已刷新。"));
                            logDebug("已执行 /jd reload，模块状态已重置，配置数据已重新加载。");
                            return 1;
                        }))
                );
            });
        } else {
            logDebug("模块已禁用 (配置)");
        }
    }

    // --- 新增：重置和重载方法 ---

    /**
     * 重新加载配置文件依赖的数据：珍珠文件、正则表达式、检测服务参数、所有计时配置。
     */
    private void reloadConfigAndData() {
        // 重新加载珍珠数据
        loadPearlData();
        // 重新编译正则
        compileRegex();

        // 【配置加载】加载计时器和动态检测配置
        this.cooldownMs = config.jumpDriveSettings.cooldownMs;
        this.ignoreMs = config.jumpDriveSettings.ignoreMs;
        this.stationaryToleranceTicks = config.jumpDriveSettings.stationaryToleranceTicks;
        this.timeoutAfterStationaryTicks = config.jumpDriveSettings.timeoutAfterStationaryTicks;

        // 更新检测服务参数
        this.detectionService.updateDetectionParameters(
                config.jumpDriveSettings.allowedError,
                config.finderSettings.maxSearchDistance
        );
        logDebug("配置依赖数据已重新加载：珍珠数据、正则表达式、计时参数、检测服务参数已更新。");
    }

    /**
     * 重置模块的运行状态：清空队列、清空冷却/拒绝列表，并将状态机归零。
     */
    private void resetModuleState() {
        // 清理所有运行时状态
        currentProcessingPlayer = null;
        currentTargetPearl = null;
        currentTargetMove = null;
        requestQueue.clear();
        phase = 0;
        tickTimer.set(0);

        // 清理动态运动状态
        lastPlayerPos = null;
        stationaryTicks = 0;
        dynamicTimeoutTicks = 0;
        playerDiedDuringProcessing = null;

        // 清理玩家特定数据
        ignoredMap.clear();
        cooldownMap.clear();

        logDebug("模块运行状态已重置。");
    }

    // --- 核心 Tick 逻辑 ---

    @Override
    public void tick() {
        if (!config.enableJumpDriveModule || client.player == null) return;

        // 【死亡检测和复活反馈】
        if (client.player.isDead()) {
            if (phase != 0) { // 正在处理请求时死亡
                playerDiedDuringProcessing = currentProcessingPlayer; // 记录当前处理的玩家
                logDebug("玩家死亡，立即取消当前处理和队列。");
                client.player.networkHandler.sendChatCommand("#stop");
                // 立即清空状态和队列，标记为故障 (isFailure=true)
                finishProcessing(currentProcessingPlayer, true);
            }
            return; // 死亡时停止所有 Tick 逻辑
        } else if (playerDiedDuringProcessing != null) {
            // 玩家复活，发送反馈
            String diedPlayer = playerDiedDuringProcessing;
            playerDiedDuringProcessing = null; // 清除死亡标记

            client.player.networkHandler.sendChatCommand("msg " + diedPlayer + " 跃迁引擎故障：控制员意外阵亡，请求已取消。");
            logDebug("玩家复活，已发送故障反馈给 " + diedPlayer + "。");

            // 尝试启动队列中的下一个请求
            if (!requestQueue.isEmpty()) {
                tickTimer.set(QUEUE_DELAY_TICKS);
            }
        }
        // 【结束】死亡检测和复活反馈


        // 核心状态检测（Phase 2/4: GOTO 移动阶段）
        if (phase == 2 || phase == 4) {

            Vec3d currentPos = client.player.getPos();

            // 1. 动态运动检测
            if (lastPlayerPos != null) {
                // 检查玩家是否移动 (阈值 0.01)
                if (currentPos.distanceTo(lastPlayerPos) > 0.01) {
                    // 玩家移动了
                    stationaryTicks = 0;
                    dynamicTimeoutTicks = 0;
                } else {
                    // 玩家静止
                    stationaryTicks++;

                    if (stationaryTicks > stationaryToleranceTicks) {
                        // 静止时间超过容忍值，启动超时计时
                        dynamicTimeoutTicks++;

                        if (dynamicTimeoutTicks > timeoutAfterStationaryTicks) {
                            logDebug("Phase " + phase + ": GOTO 静止超时（总静止超过 " + (stationaryToleranceTicks + timeoutAfterStationaryTicks) + " 刻），放弃当前请求。");
                            client.player.networkHandler.sendChatCommand("#stop");
                            client.player.networkHandler.sendChatMessage("msg " + currentProcessingPlayer + " 跃迁引擎故障：导航系统无响应或目标不可达。");
                            // 超时，标记为故障 (isFailure=true)
                            finishProcessing(currentProcessingPlayer, true);
                            return;
                        }
                    }
                }
            }
            lastPlayerPos = currentPos; // 更新位置用于下一刻比较

            // 2. 抵达检测
            if (tickTimer.get() == 0 && detectionService.isArrivalConfirmed(currentTargetMove)) {
                logDebug("Phase " + phase + ": JumpDrive 内部检测到抵达！");

                // 抵达后重置动态移动状态
                lastPlayerPos = null;
                stationaryTicks = 0;
                dynamicTimeoutTicks = 0;

                if (phase == 2) {
                    logDebug("初始化移动完成，推进到 Phase 3。");
                    client.player.sendMessage(Text.literal("§a[Maplebot] 初始化位置抵达。"), false);
                    phase = 3;
                    handlePhase();
                } else { // phase == 4
                    logDebug("回城位置抵达，推进到 Phase 5。");
                    client.player.sendMessage(Text.literal("§a[Maplebot] 已抵达目标位置: " + String.format("%.2f, %.2f, %.2f", client.player.getX(), client.player.getY(), client.player.getZ())), false);
                    tickTimer.set(DELAY_0_7S_TICKS);
                    phase = 5;
                }
            }
        }

        if (phase == 5 && tickTimer.get() == 0) {
            handlePhase();
        }

        if (tickTimer.get() > 0) {
            tickTimer.decrementAndGet();
            if (tickTimer.get() % 20 == 0 && tickTimer.get() > 0) {
                logDebug("Tick计时器: " + tickTimer.get() + " ticks 剩余。当前 Phase: " + phase);
            }
        }

        if (tickTimer.get() == 0 && phase != 5) {
            handlePhase();
        }

        if (currentProcessingPlayer == null && !requestQueue.isEmpty() && tickTimer.get() == 0) {
            currentProcessingPlayer = requestQueue.poll();
            logDebug("队列延迟结束，开始处理队列中的玩家: " + currentProcessingPlayer);
            startJumpDrive(currentProcessingPlayer);
        }
    }


    /**
     * 核心状态机。
     */
    private void handlePhase() {
        if (currentProcessingPlayer == null || currentTargetPearl == null) {
            if (phase != 0) {
                logDebug("状态机重置为 Idle (Phase 0)。");
                phase = 0;
            }
            return;
        }

        String playerName = currentProcessingPlayer;

        switch (phase) {
            case 1: // 启动初始化移动
                if (pearlData.initPosition == null) {
                    logDebug("警告: 未配置初始化位置，跳过 Phase 1 和 2，直接进入 Phase 3。");
                    phase = 3;
                    handlePhase();
                    return;
                }

                currentTargetMove = pearlData.initPosition;
                String initPosString = String.format("%.0f %.0f %.0f", currentTargetMove.x, currentTargetMove.y, currentTargetMove.z);

                logDebug("Phase 1: 启动初始化移动。目标坐标: " + initPosString);

                client.player.networkHandler.sendChatMessage("#goto " + initPosString);
                client.player.networkHandler.sendChatMessage(buildJumpMessage("跃迁引擎正在启动，请稍后"));

                tickTimer.set(INIT_MOVE_DELAY_TICKS);
                phase = 2;

                // 【动态超时】初始化状态
                lastPlayerPos = client.player.getPos();
                stationaryTicks = 0;
                dynamicTimeoutTicks = 0;

                logDebug("Phase 1 -> 2: 设置计时器 " + INIT_MOVE_DELAY_TICKS + " ticks。");
                break;

            case 2: // 等待初始化移动抵达 (在 tick() 中轮询动态检测)
                break;

            case 3: // 启动回城移动 (GOTO + 消息发送 + 启动检测延迟)
                currentTargetMove = currentTargetPearl.position;
                String pearlPosString = String.format("%.0f %.0f %.0f", currentTargetMove.x, currentTargetMove.y, currentTargetMove.z);

                logDebug("Phase 3: 启动回城移动. 目标坐标: " + pearlPosString);

                // 1. 启动 GOTO
                client.player.networkHandler.sendChatMessage("#goto " + pearlPosString);

                // 2. 发送两条消息
                client.player.networkHandler.sendChatMessage(buildJumpMessage("正在定位目标空间坐标"));

                // 3. 启动位置检测延迟
                tickTimer.set(DETECTION_START_DELAY_TICKS);

                phase = 4;

                // 【动态超时】初始化状态
                lastPlayerPos = client.player.getPos();
                stationaryTicks = 0;
                dynamicTimeoutTicks = 0;

                logDebug("Phase 3 -> 4: 启动 GOTO，发送所有消息，设置检测延迟 " + DETECTION_START_DELAY_TICKS + " ticks。");
                break;

            case 4: // 等待回城移动抵达 (在 tick() 中轮询动态检测)
                break;

            case 5: // 抵达，运行 /muse 并等待玩家检测延迟
                logDebug("Phase 5: 内部检测抵达反馈，运行 /muse。");
                String musePosString = String.format("%.0f %.0f %.0f", currentTargetMove.x, currentTargetMove.y, currentTargetMove.z);
                client.player.networkHandler.sendChatCommand(config.interactSettings.museCommandName + " " + musePosString);

                // 启动玩家检测延迟
                tickTimer.set(PLAYER_CHECK_WAIT_TICKS);
                phase = 6;
                logDebug("Phase 5 -> 6: 设置玩家检测延迟 " + PLAYER_CHECK_WAIT_TICKS + " ticks。");
                break;

            case 6: // 延迟结束，执行玩家检测并进入 Phase 7
                logDebug("Phase 6: 玩家检测延迟结束，执行 isPlayerReturned 检测。");
                // 【使用 detectionService】进行玩家检测
                boolean playerFound = detectionService.isPlayerReturned(playerName);

                if (playerFound) {
                    client.player.networkHandler.sendChatMessage("msg " + playerName + " 尊敬的 " + playerName + " , 您已成功跃迁至目标位置，欢迎回家");
                    logDebug("玩家 " + playerName + " 被成功检测到在附近。");
                } else {
                    client.player.networkHandler.sendChatMessage("msg " + playerName + " 尊敬的 " + playerName + " , 您的传送似乎失败, 请联系管理员反馈此问题");
                    logDebug("玩家 " + playerName + " 未在附近被检测到。");
                }

                // 流程成功完成，设置冷却
                cooldownMap.put(playerName, System.currentTimeMillis() + cooldownMs);

                phase = 7;
                handlePhase();
                break;

            case 7: // 流程完成，清理状态
                logDebug("Phase 7: 流程结束，调用 finishProcessing。");
                finishProcessing(playerName);
                break;

            default:
                break;
        }
    }

    // --- 外部事件接口 ---

    /**
     * 【新增方法】提供 JumpDriveMessageParser 实例供客户端类用于私信解析。
     * @return JumpDriveMessageParser 实例或 null。
     */
    public JumpDriveMessageParser getMessageParser() {
        return this.messageParser;
    }

    public void onPrivateMessage(String sender, String message) {
        logDebug("收到私信. Sender: " + sender + ", Content: " + message);
        if (!config.enableJumpDriveModule || client.player == null) return;

        // 使用配置加载的 Pattern 检查指令
        String trimmedMessage = message.trim();
        boolean commandMatches = false;

        if (jumpDriveCommandPattern != null) {
            Matcher matcher = jumpDriveCommandPattern.matcher(trimmedMessage);
            commandMatches = matcher.matches();
        } else if (trimmedMessage.equalsIgnoreCase("回城")) {
            // 兜底方案，如果配置加载失败，仍然检查硬编码的 "回城"
            commandMatches = true;
        }

        if (!commandMatches) {
            logDebug("私信内容不匹配配置的指令正则，忽略。");
            return;
        }

        long currentTime = System.currentTimeMillis();

        if (cooldownMap.containsKey(sender) && cooldownMap.get(sender) > currentTime) {
            logDebug(sender + " 处于冷却中，忽略。");
            return;
        }
        if (ignoredMap.containsKey(sender) && ignoredMap.get(sender) > currentTime) {
            logDebug(sender + " 处于拒绝列表中，忽略。");
            return;
        }

        if (!isPlayerInPearlFile(sender)) {
            ignoredMap.put(sender, currentTime + ignoreMs);
            client.player.networkHandler.sendChatMessage("msg " + sender + " 跃迁引擎初始化失败：您需要初始化回城坐标。");
            logDebug(sender + " 珍珠数据不存在，已拒绝并加入忽略列表。");
            return;
        }

        if (requestQueue.contains(sender) || sender.equals(currentProcessingPlayer)) {
            logDebug(sender + (requestQueue.contains(sender) ? " 已在队列中" : " 正在处理中") + "，忽略重复请求。");
            return;
        }

        if (currentProcessingPlayer != null) {
            client.player.networkHandler.sendChatMessage("msg " + sender + " 跃迁引擎繁忙，请稍候。");
            logDebug(sender + " 加入队列。当前处理者: " + currentProcessingPlayer);
        } else {
            logDebug(sender + " 加入队列。当前无处理者。");
        }
        requestQueue.add(sender);
    }

    public void onChatMessage(String message) {
        // ... 其他聊天处理逻辑（如果存在）
    }


    // --- 启动和收尾逻辑 ---

    private void startJumpDrive(String playerName) {
        logDebug("JumpDrive 启动序列: 玩家 " + playerName);
        currentTargetPearl = findValidPearl(playerName);

        if (currentTargetPearl == null) {
            client.player.networkHandler.sendChatMessage("msg " + playerName + " 跃迁引擎启动失败：未经处理的异常");
            logDebug("启动失败：未找到有效珍珠坐标。");
            finishProcessing(playerName, true);
            return;
        }

        currentProcessingPlayer = playerName;
        phase = 1;
        logDebug("JumpDrive 启动成功，目标: " + currentTargetPearl.position.toString() + "。进入 Phase 1 (初始化移动)。");
        handlePhase();
    }

    /**
     * 流程结束，清理状态和队列。
     * @param playerName 当前处理的玩家名。
     * @param isFailure 是否因故障（超时或死亡）而终止。
     */
    private void finishProcessing(String playerName, boolean isFailure) {
        if (isFailure) {
            logDebug("故障处理完成。玩家 " + playerName + " 状态清理。");
        } else {
            logDebug("流程成功完成。玩家 " + playerName + " 状态清理。");
        }

        currentProcessingPlayer = null;
        currentTargetPearl = null;
        currentTargetMove = null;
        phase = 0;

        // 清理动态运动状态
        lastPlayerPos = null;
        stationaryTicks = 0;
        dynamicTimeoutTicks = 0;

        // 如果是故障终止，立即开始下一个请求，避免长时间停滞
        if (!requestQueue.isEmpty() && isFailure) {
            tickTimer.set(1);
            logDebug("故障终止，立即尝试启动下一个请求。");
        } else if (!requestQueue.isEmpty()) {
            // 正常完成，设置队列延迟
            tickTimer.set(QUEUE_DELAY_TICKS);
            client.player.networkHandler.sendChatMessage("msg " + requestQueue.peek() + " 跃迁引擎已恢复，您的请求正在处理。");
            logDebug("队列中还有请求。设置 " + QUEUE_DELAY_TICKS + " 延迟，下一个处理者: " + requestQueue.peek());
        } else {
            logDebug("队列为空，系统进入 Idle。");
        }
    }

    // 重载方法，默认非故障
    private void finishProcessing(String playerName) {
        finishProcessing(playerName, false);
    }


    // --- 文件和数据逻辑 (保持不变) ---

    private boolean isPlayerInPearlFile(String playerName) {
        if (pearlData == null) return false;
        String trimmedPlayerName = playerName.trim();
        boolean found = pearlData.pearlData.stream()
                .anyMatch(data -> data.playerName.equalsIgnoreCase(trimmedPlayerName));
        return found;
    }

    private PlayerPearlData findValidPearl(String playerName) {
        if (pearlData == null) return null;

        for (PlayerPearlData data : pearlData.pearlData) {
            if (data.playerName.equalsIgnoreCase(playerName)) {
                if (isPosInActivityRange(data.position)) {
                    return data;
                } else {
                    client.player.networkHandler.sendChatMessage("msg " + playerName + String.format(" [JumpDrive] 您的%d号珍珠为非法活动范围，跳过。", data.id));
                }
            }
        }
        return null;
    }

    private boolean isPosInActivityRange(Vec3d pos) {
        if (pearlData.activityMin == null || pearlData.activityMax == null) {
            return true;
        }
        double x = pos.x;
        double y = pos.y;
        double z = pos.z;
        int minX = Math.min(pearlData.activityMin.getX(), pearlData.activityMax.getX());
        int maxX = Math.max(pearlData.activityMin.getX(), pearlData.activityMax.getX());
        int minZ = Math.min(pearlData.activityMin.getZ(), pearlData.activityMax.getZ());
        int maxZ = Math.max(pearlData.activityMin.getZ(), pearlData.activityMax.getZ());
        int minY = Math.min(pearlData.activityMin.getY(), pearlData.activityMax.getY()) - Y_TOLERANCE;
        int maxY = Math.max(pearlData.activityMin.getY(), pearlData.activityMax.getY()) + Y_TOLERANCE;

        boolean inX = x >= minX && x <= maxX;
        boolean inY = y >= minY && y <= maxY;
        boolean inZ = z >= minZ && z <= maxZ;

        return inX && inY && inZ;
    }

    private void loadPearlData() {
        if (pearlFilePath == null) {
            logDebug("警告: 珍珠文件路径未初始化。");
            return;
        }
        try {
            if (!Files.exists(pearlFilePath)) {
                logDebug("珍珠文件不存在。尝试创建默认文件。");
                createDefaultPearlFile();
                return;
            }

            List<String> lines = Files.readAllLines(pearlFilePath);
            PearlPosition newPearlData = new PearlPosition();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // 1. 解析 Activity 范围
                if (line.startsWith("Activity:")) {
                    String[] parts = line.substring(9).trim().split("\\s+");
                    if (parts.length >= 6) {
                        try {
                            newPearlData.activityMin = new BlockPos(
                                    Integer.parseInt(parts[0]),
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2])
                            );
                            newPearlData.activityMax = new BlockPos(
                                    Integer.parseInt(parts[3]),
                                    Integer.parseInt(parts[4]),
                                    Integer.parseInt(parts[5])
                            );
                            logDebug("解析活动范围成功: Min=" + newPearlData.activityMin + ", Max=" + newPearlData.activityMax);
                        } catch (NumberFormatException e) {
                            logDebug("警告: Activity 范围坐标解析失败。");
                        }
                    }
                    continue;
                }

                // 2. 解析初始化位置
                if (line.startsWith("InitPos:")) {
                    String[] parts = line.substring(8).trim().split("\\s+");
                    if (parts.length >= 3) {
                        try {
                            newPearlData.initPosition = new Vec3d(
                                    Double.parseDouble(parts[0].trim()),
                                    Double.parseDouble(parts[1].trim()),
                                    Double.parseDouble(parts[2].trim())
                            );
                            logDebug("解析初始化位置成功: " + newPearlData.initPosition);
                        } catch (NumberFormatException e) {
                            logDebug("警告: InitPos 坐标解析失败。");
                        }
                    }
                    continue;
                }

                // 3. 解析玩家珍珠数据
                String[] parts = line.trim().split("\\s+");

                if (parts.length >= 5) {
                    try {
                        PlayerPearlData data = new PlayerPearlData();
                        data.id = Integer.parseInt(parts[0].trim());

                        data.playerName = parts[1].trim();

                        data.position = new Vec3d(
                                Double.parseDouble(parts[2].trim()),
                                Double.parseDouble(parts[3].trim()),
                                Double.parseDouble(parts[4].trim())
                        );
                        newPearlData.pearlData.add(data);
                    } catch (NumberFormatException e) {
                        logDebug("警告: 玩家珍珠坐标记录解析失败 (数值错误)，跳过行: " + line);
                    }
                } else {
                    logDebug("警告: 玩家珍珠坐标记录解析失败 (部分不足 5 个)，跳过行: " + line);
                }
            }

            this.pearlData = newPearlData;
            sendFeedback(Text.literal("§a[JumpDrive] 珍珠数据加载成功。"));
            logDebug("珍珠数据加载成功。加载了 " + newPearlData.pearlData.size() + " 条玩家记录。");

        } catch (IOException e) {
            sendFeedback(Text.literal("§c[JumpDrive] 珍珠数据加载失败: " + e.getMessage()));
            logDebug("严重错误: 珍珠数据加载失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createDefaultPearlFile() throws IOException {
        String defaultContent =
                "# 这是回城模块的珍珠坐标文件。请确保使用 UTF-8 编码。\n" +
                        "# 第一行：活动范围 (可选)\n" +
                        "# 第二行：初始化位置 (必填)\n" +
                        "# 第三行及以后：玩家数据 (编号 玩家名 X Y Z)\n" +
                        "Activity:1000 64 1000 2000 64 2000\n" +
                        "InitPos:1500 65 1500\n" +
                        "\n" +
                        "# 示例玩家数据\n" +
                        "1 Maple_Bamboo_Team 1500.5 65.0 1500.5\n";

        Files.writeString(pearlFilePath, defaultContent);
        logDebug("已创建默认珍珠文件: " + pearlFilePath.getFileName());
        loadPearlData();
    }

    // --- 辅助方法 (保持不变) ---

    private String generateRandomSuffix() {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(3);
        for (int i = 0; i < 3; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }

    private String buildJumpMessage(String baseMessage) {
        String pureMessage = "[JumpDrive] " + baseMessage;
        return "msg " + currentProcessingPlayer + " " + pureMessage + " " + generateRandomSuffix();
    }

    private void sendFeedback(Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, false);
        }
    }

    @Override
    public void shutdown() {
        logDebug("模块关闭中，清理状态。");
        currentProcessingPlayer = null;
        requestQueue.clear();
        phase = 0;
    }
}