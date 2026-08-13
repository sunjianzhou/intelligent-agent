package com.intelligent.agent.web.ai.llm;

import java.util.List;
import java.util.Map;

/**
 * 模型无关的单条对话消息。
 *
 * @param role        消息角色：system / user / assistant / tool
 * @param content     消息内容
 * @param toolCalls   原生工具调用（仅 assistant 消息；归一化结构
 *                     {@code [{"id":..,"function":{"name":..,"arguments":{..}}}]}，可为 null）
 * @param toolCallId  工具结果关联 ID（仅 tool 消息，可为 null）
 */
public record ChatMessage(String role, String content,
                          List<Map<String, Object>> toolCalls, String toolCallId) {

    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    public ChatMessage {
        role = role == null ? "" : role;
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        toolCallId = toolCallId == null ? null : toolCallId;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** 带原生工具调用的 assistant 消息（tool_calls 归一化结构）。 */
    public static ChatMessage assistant(String content, List<Map<String, Object>> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null);
    }

    /** 工具执行结果消息（role=tool，供模型上下文关联）。 */
    public static ChatMessage tool(String content, String toolCallId) {
        return new ChatMessage("tool", content, null, toolCallId);
    }
}
