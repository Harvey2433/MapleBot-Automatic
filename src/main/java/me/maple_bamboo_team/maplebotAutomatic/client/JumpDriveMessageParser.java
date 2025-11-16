package me.maple_bamboo_team.maplebotAutomatic.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JumpDrive 模块使用的消息解析器。
 * 针对日志格式: [CHAT] FengLiMeng_悄悄地对你说：1234
 * **修改：已移除硬编码正则，现在接受外部传入的 Pattern 对象，以支持配置文件。**
 */
public class JumpDriveMessageParser {

    private final Pattern privateMessagePattern;

    /**
     * 构造函数。接收外部编译好的 Pattern。
     * @param privateMessagePattern 外部传入的已编译 Pattern，用于匹配私信格式。
     */
    public JumpDriveMessageParser(Pattern privateMessagePattern) {
        if (privateMessagePattern == null) {
            throw new IllegalArgumentException("Private Message Pattern cannot be null.");
        }
        this.privateMessagePattern = privateMessagePattern;
    }

    /**
     * 尝试解析日志中的私信内容。
     *
     * @param fullMessage 完整的聊天文本 (例如: "[CHAT] FengLiMeng_悄悄地对你说：1234")
     * @return 包含发送者和消息内容的 MessageData，如果不是私信则返回 null。
     */
    public MessageData parsePrivateMessage(String fullMessage) {
        // 使用外部传入的配置 Pattern 匹配整个消息字符串
        Matcher matcher = privateMessagePattern.matcher(fullMessage);

        if (matcher.find() && matcher.groupCount() >= 2) {
            // 假设外部传入的 Pattern 遵循 Group 1 = Sender, Group 2 = Content 的约定
            String sender = matcher.group(1).trim();
            String content = matcher.group(2).trim();

            if (!sender.isEmpty() && !content.isEmpty()) {
                return new MessageData(sender, content);
            }
        }
        return null;
    }

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
}