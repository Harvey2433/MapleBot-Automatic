package me.maple_bamboo_team.maplebotAutomatic.module;

import me.maple_bamboo_team.maplebotAutomatic.config.MaplebotConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 自动登录与排队模块 [V2 逻辑修正版 - 完整无删减]
 * 级别：特权模块 (不受业务锁限制，且负责最终解锁)
 * 修正点：如果不满足排队条件，立即解锁业务锁。
 */
public class AutoLoginModule implements IModule {

    private MinecraftClient client;
    private MaplebotConfig config;

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");

    private enum Phase { INIT_WAIT, LOGIN_WAIT, COMPASS_WAIT, LOBBY_NAVIGATE, QUEUEING, COMPLETED }
    private enum QuizPhase { IDLE, WAITING_DETAIL }

    private final Map<String, String> QUESTION_BANK = new HashMap<>();
    private String playerQueuePosition = "";

    private Phase currentPhase = Phase.COMPLETED;
    private QuizPhase quizPhase = QuizPhase.IDLE;
    private int phaseTimer = 0;
    private boolean isCompleting = false;

    @Override
    public String getName() {
        return "AutoLoginModule";
    }

    @Override
    public void initialize(MinecraftClient client, MaplebotConfig config) {
        this.client = client;
        this.config = config;

        // 初始化题库
        this.QUESTION_BANK.put("红石火把", "15");
        this.QUESTION_BANK.put("猪被闪电", "僵尸猪人");
        this.QUESTION_BANK.put("小箱子能", "27");
        this.QUESTION_BANK.put("开服年份", "2020");
        this.QUESTION_BANK.put("定位末地遗迹", "0");
        this.QUESTION_BANK.put("爬行者被闪电", "高压爬行者");
        this.QUESTION_BANK.put("大箱子能", "54");
        this.QUESTION_BANK.put("羊驼会主动", "不会");
        this.QUESTION_BANK.put("无限水", "3");
        this.QUESTION_BANK.put("挖掘速度最快", "金镐");
        this.QUESTION_BANK.put("凋灵死后", "下界之星");
        this.QUESTION_BANK.put("苦力怕的官方", "爬行者");
        this.QUESTION_BANK.put("南瓜的生长", "不需要");
        this.QUESTION_BANK.put("定位末地", "0");

        // 监听进入服务器事件：重置状态机
        ClientPlayConnectionEvents.JOIN.register((handler, sender, clientInstance) -> {
            System.out.println("[AutoLogin] 检测到进入服务器，初始化登录与排队流水线...");
            this.currentPhase = Phase.INIT_WAIT;
            this.quizPhase = QuizPhase.IDLE;
            this.phaseTimer = 0;
            this.isCompleting = false;
            this.playerQueuePosition = "";
        });

        // 监听聊天消息用于答题
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // 【致命修复 1】：绝不能 return overlay，排队数字是在操作栏上的呐！
            if (currentPhase == Phase.COMPLETED) return;
            processMessagePipeline(message.getString());
        });
    }

    @Override
    public void tick() {
        // 【特权说明】：本模块不检查 isBusinessLocked，因为它自己就是开锁的钥匙！

        if (client.player == null || client.world == null || currentPhase == Phase.COMPLETED) return;

        phaseTimer++;

        switch (currentPhase) {
            case INIT_WAIT -> handleInitWait();
            case LOGIN_WAIT -> handleLoginWait();
            case COMPASS_WAIT -> handleCompassWait();
            case LOBBY_NAVIGATE -> handleLobbyNavigate();
            case QUEUEING -> handleQueueing();
        }
    }

    private void handleInitWait() {
        // 进入服务器 1 秒后进行环境判定
        if (phaseTimer > 20) {
            // 【核心修正逻辑】：检查是否处于排队环境
            if (!isAtQueuePos(client.player)) {
                System.out.println("[AutoLogin] 已离开登录环境.");
                completeAndRelease();
                return;
            }

            // 如果在排队环境，继续走自动化流程
            if (config.autoLoginSettings.enableAutoLogin) {
                transitionTo(Phase.LOGIN_WAIT);
            } else {
                System.out.println("[AutoLogin] 本次登录已跳过输入密码");
                transitionTo(Phase.QUEUEING);
            }
        }
    }

    private void handleLoginWait() {
        if (phaseTimer >= config.autoLoginSettings.preLoginDelayTicks) {
            String pwd = config.autoLoginSettings.loginPassword;
            if (pwd != null && !pwd.isEmpty() && !pwd.equals("YourPasswordHere")) {
                // 【极其重要】：使用 sendChatCommand 发送命令，绝不能用 sendChatMessage！
                client.player.networkHandler.sendChatCommand("l " + pwd);
                System.out.println("[AutoLogin] 正在登录");
            } else {
                System.out.println("[AutoLogin] 警告：未配置有效密码！");
            }
            transitionTo(Phase.COMPASS_WAIT);
        }
    }

    private void handleCompassWait() {
        if (phaseTimer >= config.autoLoginSettings.preCompassDelayTicks) {
            transitionTo(Phase.LOBBY_NAVIGATE);
        }
    }

    private void handleLobbyNavigate() {
        // 处理 GUI 中的指南针
        if (client.player.currentScreenHandler instanceof GenericContainerScreenHandler gui) {
            if (phaseTimer % 10 == 0) {
                for (Slot slot : gui.slots) {
                    if (slot.getStack().isOf(Items.COMPASS)) {
                        client.interactionManager.clickSlot(gui.syncId, slot.id, 0, SlotActionType.PICKUP, client.player);
                        System.out.println("[AutoLogin] 正在加入服务器");
                        transitionTo(Phase.QUEUEING);
                        return;
                    }
                }
            }
            return;
        }

        // 处理快捷栏中的指南针
        boolean hasCompass = false;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.COMPASS)) {
                hasCompass = true;
                if (phaseTimer % 10 == 0) {
                    if (client.player.getInventory().selectedSlot != i) {
                        client.player.getInventory().selectedSlot = i;
                        client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                    }
                    client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                }
                break;
            }
        }

        // 兜底：如果没找到指南针，超时后强制进入排队检测，防止卡死
        if (!hasCompass && phaseTimer > 200) {
            System.out.println("[AutoLogin] 未找到指南针");
            transitionTo(Phase.QUEUEING);
        }
    }

    private void handleQueueing() {
        // 【致命修复 3】：防止休眠期间继续判定导致日志疯狂刷屏
        if (this.isCompleting) return;

        if (phaseTimer % 10 == 0) {
            boolean isAtQueue = isAtQueuePos(client.player);

            if (!isAtQueue && client.player.getY() > -64.0d) {
                if (phaseTimer > 40) {
                    System.out.println("[AutoLogin] 已离开登录服务器");
                    completeAndRelease();
                }
            }
        }
    }

    private void processMessagePipeline(String rawMsg) {
        String cleanMsg = COLOR_CODE_PATTERN.matcher(rawMsg).replaceAll("").replace("\n", " ").trim();
        if (cleanMsg.isEmpty()) return;

        String lowerMsg = cleanMsg.toLowerCase();

        // 【完整恢复】解析排队位置的逻辑，一行都没有省略！
        int queueIndex = lowerMsg.indexOf("queue: ");
        if (queueIndex != -1) {
            String currentPos = cleanMsg.substring(queueIndex + "queue: ".length()).trim();
            if (!currentPos.equals(this.playerQueuePosition)) {
                this.playerQueuePosition = currentPos;
                // 【黑子添加的小功能】：同步输出当前排队位置！
                System.out.println("[AutoLogin] 当前排队位置: " + currentPos);
            }
            return;
        }

        if (cleanMsg.contains("接下来问一个问题")) {
            this.quizPhase = QuizPhase.WAITING_DETAIL;
            return;
        }

        if (this.quizPhase == QuizPhase.WAITING_DETAIL && cleanMsg.contains("丨")) {
            resolveQuizAsync(cleanMsg);
        }
    }

    private void resolveQuizAsync(String cleanContent) {
        // 使用新线程进行异步答题，防止阻塞主线程
        new Thread(() -> {
            for (Map.Entry<String, String> entry : QUESTION_BANK.entrySet()) {
                if (cleanContent.contains(entry.getKey())) {
                    String answer = entry.getValue();
                    for (String opt : new String[]{"A", "B", "C"}) {
                        if (cleanContent.contains(opt + "." + answer)) {
                            final String finalCmd = opt.toLowerCase();
                            client.execute(() -> {
                                if (client.player != null) {
                                    client.player.networkHandler.sendChatMessage(finalCmd);
                                    System.out.println("[AutoLogin] 已发送答案: " + finalCmd);
                                }
                            });
                            this.quizPhase = QuizPhase.IDLE;
                            return;
                        }
                    }
                }
            }
        }).start();
    }

    private void transitionTo(Phase nextPhase) {
        this.currentPhase = nextPhase;
        this.phaseTimer = 0;
    }

    /**
     * 动态解析方块 ID 并无视 Y 轴判断
     */
    private boolean isAtQueuePos(ClientPlayerEntity player) {
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());

        int qx = config.autoLoginSettings.queueTargetX;
        int qz = config.autoLoginSettings.queueTargetZ;
        int radius = config.autoLoginSettings.queueRadius;

        boolean isInsideXZ = (px >= qx - radius && px <= qx + radius) &&
                (pz >= qz - radius && pz <= qz + radius);

        if (!isInsideXZ) return false;

        if (client.world != null) {
            // 获取脚下一格的方块
            BlockPos footPos = player.getBlockPos().down();
            BlockState stateUnder = client.world.getBlockState(footPos);

            // 解析配置中的方块 ID (例如 "minecraft:beacon")
            String targetBlockStr = config.autoLoginSettings.queueStandingBlock;
            String[] parts = targetBlockStr.split(":");
            Identifier targetId = parts.length == 2 ? Identifier.of(parts[0], parts[1]) : Identifier.of("minecraft", targetBlockStr);

            Block targetBlock = Registries.BLOCK.get(targetId);
            return stateUnder.isOf(targetBlock);
        }
        return false;
    }

    /**
     * 完成流水线：解除业务锁！
     */
    private void completeAndRelease() {
        if (this.isCompleting) return;
        this.isCompleting = true;

        System.out.println("[AutoLogin] 排队已结束.");

        // 使用新线程稍作延迟，确保玩家彻底在主服加载完毕
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}

            client.execute(() -> {
                if (!this.isCompleting) return;

                // 【最核心的操作】：解除大管家的业务锁！
                ModuleManager.isBusinessLocked = false;

                this.currentPhase = Phase.COMPLETED;
                this.quizPhase = QuizPhase.IDLE;
                this.isCompleting = false;
                System.out.println("[AutoLogin] 已解锁业务模块.");
            });
        }).start();
    }

    @Override
    public void shutdown() {
        this.currentPhase = Phase.COMPLETED;
    }
}