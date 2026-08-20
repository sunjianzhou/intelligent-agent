package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.im.ChannelAdapter;
import com.intelligent.agent.web.im.ChannelAdapterManager;
import com.intelligent.agent.web.im.ChannelType;
import com.intelligent.agent.web.im.SendResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一 IM 消息 AgentTool（2026-08-15 补齐，对齐 Python ChannelMessageTool）：
 * 通过 {@link ChannelAdapterManager} 路由到 feishu/wecom/telegram/web。
 * 未指定 channel 时向所有已启用通道广播；目标通道未启用时给出明确错误。
 */
public class ChannelMessageTool implements AgentTool {

    private final ChannelAdapterManager channelAdapterManager;

    public ChannelMessageTool(ChannelAdapterManager channelAdapterManager) {
        this.channelAdapterManager = channelAdapterManager;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "channel_message", "向 IM 渠道发送消息。参数: message(消息内容,必填),"
                        + " channel(可选,feishu_im/wecom/telegram/web,不填则广播到所有已启用渠道),"
                        + " receiver_id(可选,接收者 ID,默认 default), chat_type(可选,p2p/group,默认 p2p)。",
                false, null, Duration.ofSeconds(60),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "message", Map.of("type", "string", "description", "消息内容"),
                                "channel", Map.of("type", "string",
                                        "description", "feishu_im/wecom/telegram/web"),
                                "receiver_id", Map.of("type", "string", "description", "接收者 ID"),
                                "chat_type", Map.of("type", "string", "description", "p2p/group")),
                        "required", List.of("message")))
                .requireApproval();
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String message = str(arguments.get("message"));
        if (message.isBlank()) {
            return "发送失败: message 不能为空";
        }
        String channel = str(arguments.get("channel"));
        String receiverId = str(arguments.get("receiver_id"));
        if (receiverId.isBlank()) {
            receiverId = "default";
        }
        String chatType = str(arguments.get("chat_type"));
        if (chatType.isBlank()) {
            chatType = "p2p";
        }

        if (channel.isBlank()) {
            return broadcastAll(message, receiverId, chatType);
        }
        ChannelType type;
        try {
            type = ChannelType.fromValue(channel);
        } catch (IllegalArgumentException e) {
            return "发送失败: 未知渠道 " + channel + "，可用: feishu_im/wecom/telegram/web";
        }
        Optional<ChannelAdapter> adapter = channelAdapterManager.get(type);
        if (adapter.isEmpty() || !adapter.get().isEnabled()) {
            return "发送失败: 渠道 " + channel + " 未启用（已启用: "
                    + enabledChannels() + "）";
        }
        SendResult result = adapter.get().sendText(receiverId, message, chatType);
        return formatResult(type.getValue(), result);
    }

    private String broadcastAll(String message, String receiverId, String chatType) {
        List<ChannelAdapter> enabled = channelAdapterManager.listEnabled();
        if (enabled.isEmpty()) {
            return "发送失败: 没有已启用的 IM 渠道";
        }
        Map<ChannelType, String> receivers = new LinkedHashMap<>();
        for (ChannelAdapter adapter : enabled) {
            receivers.put(adapter.channelType(), receiverId);
        }
        Map<ChannelType, SendResult> results = channelAdapterManager.broadcastText(
                message, receivers, chatType);
        if (results.isEmpty()) {
            return "广播失败: 没有渠道成功发送";
        }
        StringBuilder sb = new StringBuilder("广播结果:\n");
        results.forEach((type, result) -> sb.append("- ")
                .append(type.getValue()).append(": ").append(formatResult(type.getValue(), result))
                .append('\n'));
        return sb.toString().stripTrailing();
    }

    private static String formatResult(String channel, SendResult result) {
        return (result.isSuccess() ? "已发送" : "发送失败")
                + (result.getError() != null && !result.getError().isBlank()
                ? " (" + result.getError() + ")" : "")
                + (result.getMessageId() != null ? " msg=" + result.getMessageId() : "");
    }

    private String enabledChannels() {
        StringBuilder sb = new StringBuilder();
        for (ChannelAdapter adapter : channelAdapterManager.listEnabled()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(adapter.channelType().getValue());
        }
        return sb.isEmpty() ? "无" : sb.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
