package me.maple_bamboo_team.maplebotAutomatic.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JumpDrive 模块使用的消息解析器。
 * 针对日志格式: [CHAT] FengLiMeng_悄悄地对你说：1234
 */
public class JumpDriveMessageParser {

    /**
     * 正则表达式匹配私信格式，捕获发送者和消息内容。
     * 匹配: [CHAT] <Sender>悄悄地对你说：<Message>
     * 群组 1: Sender (发送者)
     * 群组 2: Message (消息内容)
     * 注意：这里假设方括号 [] 和冒号 : 是日志前缀，实际聊天框内显示的文本不包含它们。
     */
    private static final Pattern PRIVATE_MESSAGE_PATTERN =
            Pattern.compile("^(?:\\s*<.*?>)?(.+?)悄悄地对你说[：:]\\s*(.*)", Pattern.CASE_INSENSITIVE);

    /**
     * 尝试解析日志中的私信内容。
     *
     * @param fullMessage 完整的聊天文本 (例如: "FengLiMeng_悄悄地对你说：1234")
     * @return 包含发送者和消息内容的 MessageData，如果不是私信则返回 null。
     */
    public static MessageData parsePrivateMessage(String fullMessage) {
        if (!fullMessage.contains("悄悄地对你说")) {
            return null;
        }

        // 移除可能的日志前缀 [CHAT] 或 [Render thread/INFO]: [CHAT]
        String cleanMessage = fullMessage.replaceAll("^\\[(?:.*)\\]:\\s*\\[CHAT\\]\\s*", "");

        Matcher matcher = PRIVATE_MESSAGE_PATTERN.matcher(cleanMessage);

        if (matcher.find() && matcher.groupCount() >= 2) {
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