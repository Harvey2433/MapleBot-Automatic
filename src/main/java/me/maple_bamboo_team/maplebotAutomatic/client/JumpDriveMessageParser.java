package me.maple_bamboo_team.maplebotAutomatic.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JumpDrive 模块使用的消息解析器。
 * 针对日志格式: [CHAT] FengLiMeng_悄悄地对你说：1234
 */
public class JumpDriveMessageParser {

    private final Pattern privateMessageLogPattern;

    /**
     * 消息数据结构。
     */
    public static class MessageData {
        public final String sender;
        public final String content;

        public MessageData(String sender, String content) {
            this.sender = sender;
            this.content = content;
        }
    }

    /**
     * 构造函数，接受一个用于匹配私信日志格式的正则表达式 Pattern。
     * @param privateMessageLogPattern 从配置文件加载的 Pattern
     */
    public JumpDriveMessageParser(Pattern privateMessageLogPattern) {
        this.privateMessageLogPattern = privateMessageLogPattern;
    }

    /**
     * 尝试解析聊天信息中的私信内容。
     *
     * @param fullMessage 完整的聊天文本
     * @return 包含发送者和消息内容的 MessageData，如果不是匹配的私信格式则返回 null。
     */
    public MessageData parsePrivateMessage(String fullMessage) {
        if (privateMessageLogPattern == null) {
            return null; // 配置错误，解析器不可用
        }

        Matcher matcher = privateMessageLogPattern.matcher(fullMessage);

        // Group 1: Sender (发送者), Group 2: Content (消息内容)
        if (matcher.find() && matcher.groupCount() >= 2) {
            String sender = matcher.group(1).trim();
            String content = matcher.group(2).trim();

            if (!sender.isEmpty() && !content.isEmpty()) {
                return new MessageData(sender, content);
            }
        }
        return null;
    }
}