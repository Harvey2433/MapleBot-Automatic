package me.maple_bamboo_team.maplebotAutomatic.config;

/**
 * Maplebot Mod 的总配置文件类。
 */
public class MaplebotConfig {

    // --- 全局模块启用开关 ---

    public boolean enableInteractModule = true;
    // 已删除: public boolean enableMovementMonitorModule = true;
    public boolean enablePlayerFinderModule = true;
    public boolean enableJumpDriveModule = true;


    // --- 模块配置实例 ---

    public InteractSettings interactSettings = new InteractSettings();

    // 已删除: public MovementMonitorSettings monitorSettings = new MovementMonitorSettings();

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

        // 【新增】：允许玩家与目标中心点的误差范围 (半径)。
        // 原属于 MovementMonitorSettings，现移至此作为 GOTO 抵达阈值。
        public double allowedError = 0.9;

        /**
         * 【新增】：用于匹配私信日志格式的正则表达式。
         * 匹配格式示例: [CHAT] FengLiMeng_悄悄地对你说：回城
         * Group 1: Sender (发送者), Group 2: Content (消息内容)
         */
        public String privateMessageLogRegex = "^(?:\\s*<.*?>)?(.+?)(?:悄悄地对你说| whispers to you)[：:]\\s*(.*)";


        /**
         * 用于匹配回城指令的私信正则表达式。
         * 例如: "回城" 将匹配 "回城"。
         * 模块内部将使用 Pattern.CASE_INSENSITIVE 忽略大小写。
         */
        public String jumpDriveMessageRegex = "回城";
    }

    // --- 默认构造函数（可用于加载时的默认配置） ---
    public MaplebotConfig() {
        // 确保所有内部类实例都被初始化
    }
}