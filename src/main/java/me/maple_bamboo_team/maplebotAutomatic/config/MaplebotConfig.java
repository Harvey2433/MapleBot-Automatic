package me.maple_bamboo_team.maplebotAutomatic.config;

/**
 * Maplebot Mod 的总配置文件类。
 */
public class MaplebotConfig {

    // --- 全局模块启用开关 ---

    public boolean enableInteractModule = true;
    public boolean enablePlayerFinderModule = true;
    public boolean enableJumpDriveModule = true;


    // --- 模块配置实例 ---

    public InteractSettings interactSettings = new InteractSettings();
    public PlayerFinderSettings finderSettings = new PlayerFinderSettings();
    public JumpDriveSettings jumpDriveSettings = new JumpDriveSettings();

    // ----------------------------------------------------
    // --- 内部类：模块配置数据结构 ---
    // ----------------------------------------------------

    /**
     * InteractModule (交互模块) 的配置
     */
    public static class InteractSettings {
        // 用于触发交互的指令名称，例如 /muse
        public String museCommandName = "muse";

        // 最大交互距离。
        public double maxInteractDistance = 5.0;

        // 瞄准点 Y 轴的微调偏移量。
        public double aimYOffset = 0.05;

        // 交互时使用的旋转速度。
        public float rotationSpeed = 0.35f;
    }

    /**
     * PlayerFinderModule (玩家查找模块) 的配置
     */
    public static class PlayerFinderSettings {
        // 用于查找玩家的指令名称，例如 /findplayer
        public String finderCommandName = "findplayer";

        // 最大查找距离（方块数）
        public double maxSearchDistance = 4.5;
    }

    /**
     * JumpDriveModule (回城模块) 的配置
     */
    public static class JumpDriveSettings {
        // --- 核心配置 ---
        /** 珍珠坐标文件名称 */
        public String pearlFileName = "Pearl.pos";
        /** 触发回城的私聊指令名称 (例如：JumpDrive) */
        public String jumpDriveCommand = "JumpDrive";
        /** 抵达目标位置允许的误差范围 (方块) */
        public double allowedError = 1.0;
        /** 用于解析客户端私聊日志的正则表达式 */
        public String privateMessageLogRegex = "^(?:\\s*<.*?>)?(.+?)(?:悄悄地对你说| whispers to you)[：:]\\s*(.*)";
        /** 用于匹配玩家回城指令的正则表达式 */
        public String jumpDriveMessageRegex = "回城";

        // *** 计时器和动态超时设置 ***
        /** 冷却时间 (毫秒)，请求失败或被取消后进入冷却 */
        public int cooldownMs = 60000;
        /** 忽略时间 (毫秒)，用于无坐标时的提示忽略，防止刷屏 */
        public int ignoreMs = 60000;
        /** 站立不动的最大游戏刻数 (动态超时前) */
        public int stationaryToleranceTicks = 100;
        /** 站立不动后，允许的最大超时刻数 (游戏刻) */
        public int timeoutAfterStationaryTicks = 300;

        // *** 流程延迟配置 (游戏刻) ***
        /** GOTO 抵达目标后，执行 /muse 之前的等待延迟 (游戏刻，默认 14 ticks = 0.7秒) */
        public int preMuseDelayTicks = 14;
        /** /muse 后，等待玩家抵达并执行检测的延迟 (游戏刻，默认 30 ticks = 1.5秒) */
        public int playerCheckWaitTicks = 30;
        /** 队列处理完成后，开始下一个请求的延迟 (游戏刻，默认 100 ticks = 5 秒) */
        public int queueDelayTicks = 100;
        /** GOTO 珍珠位置启动后的等待延迟 (游戏刻，默认 60 ticks = 3 秒) */
        public int detectionStartDelayTicks = 60;

        // *** 容错/消息配置 ***
        /** Activity Range Y 轴容差 (+/- yTolerance) */
        public int yTolerance = 3;
        /** 消息前缀，例如 "[JumpDrive]" */
        public String jumpDriveMessagePrefix = "[JumpDrive]";
        /** 消息后缀随机字符串的长度 */
        public int randomSuffixLength = 3;
        /** 机器人死亡时发送给玩家的取消消息 */
        public String deathCancelMessage = "跃迁引擎故障：未经处理的异常，请求已取消。";
        /** 重试等待超时时发送给玩家的取消消息 */
        public String retryTimeoutMessage = "未收到重试回复，请求已取消。";

        // *** 珍珠重试逻辑设置 ***
        /** 是否启用回城失败后尝试下一个跃迁点 */
        public boolean enableJumpDriveRetry = true;
        /** 等待玩家回复 'Y'/'N' 的超时时间 (毫秒) */
        public int retryWaitTimeoutMs = 25000;
        /** 排除列表的珍珠在多久后复位 (毫秒) */
        public int excludedPearlResetMs = 60000;
    }
}