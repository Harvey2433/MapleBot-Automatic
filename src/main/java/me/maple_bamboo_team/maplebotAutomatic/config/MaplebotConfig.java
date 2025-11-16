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

        // 查找玩家的最大距离 (半径)。
        public double maxSearchDistance = 4.5;
    }

    /**
     * JumpDriveModule (回城模块) 的配置
     */
    public static class JumpDriveSettings {
        // 珍珠坐标文件名称
        public String pearlFileName = "Pearl.pos";
        // 重载/清理指令名称
        public String jumpDriveCommand = "JumpDrive";

        // 允许玩家与目标中心点的误差范围 (半径)。
        public double allowedError = 0.9;

        // 用于匹配私信日志格式的正则表达式。
        public String privateMessageLogRegex = "^(?:\\s*<.*?>)?(.+?)(?:悄悄地对你说| whispers to you)[：:]\\s*(.*)";


        // 用于匹配回城指令的私信正则表达式。
        public String jumpDriveMessageRegex = "回城";

        // *** 配置字段：计时器和动态超时 ***
        /** 玩家回城请求冷却时间 (毫秒) */
        public int cooldownMs = 60000;
        /** 玩家无珍珠数据时，进入忽略列表的时间 (毫秒) */
        public int ignoreMs = 60000;

        /** GOTO过程中，判断为静止的容忍Tick数 (默认 100 ticks = 5 秒) */
        public int stationaryToleranceTicks = 100;
        /** 容忍期结束后，触发超时的额外静止Tick数 (默认 300 ticks = 15 秒) */
        public int timeoutAfterStationaryTicks = 300;
        // ***************************************
    }

    // --- 默认构造函数 ---
    public MaplebotConfig() {
        // 确保所有内部类实例都被初始化
    }
}