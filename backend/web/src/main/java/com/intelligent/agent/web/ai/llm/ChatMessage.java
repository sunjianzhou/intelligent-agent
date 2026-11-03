package com.intelligent.agent.web.ai.llm;

import java.util.ArrayList;
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

    /**
     * 把多条 system 消息合并为一条（保留在首条 system 的位置，内容按原顺序拼接）。
     *
     * <p>Ollama 的 chat 模板只会渲染"第一条 system 消息"（模板变量 {@code .System}），
     * 其余 system 消息落在 {@code .Messages} 里，而 qwen2.5 / llama3 等模板的循环分支
     * 只处理 user / assistant / tool —— 多出来的 system 消息会被**静默丢弃**。
     * 2026-11-02 实测：memory-003 的 {@code [SEMANTIC MEMORY]} 已构造进消息列表
     * （trace recall=3），模型仍回答"没有记录"，根因即在此。</p>
     *
     * <p>因此所有发往模型的请求都应先经过本方法，把记忆 / 项目上下文 / 计划 /
     * 收尾指令等 system 块合并进唯一一条 system 消息。</p>
     */
    public static List<ChatMessage> mergeSystemMessages(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) {
            return messages;
        }
        int firstSystem = -1;
        int systemCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).role())) {
                if (firstSystem < 0) {
                    firstSystem = i;
                }
                systemCount++;
            }
        }
        if (systemCount <= 1) {
            return messages;
        }
        StringBuilder merged = new StringBuilder();
        for (ChatMessage message : messages) {
            if (!"system".equals(message.role())) {
                continue;
            }
            if (merged.length() > 0 && !message.content().isBlank()) {
                merged.append("\n\n");
            }
            merged.append(message.content());
        }
        List<ChatMessage> out = new ArrayList<>(messages.size() - systemCount + 1);
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (i == firstSystem) {
                out.add(ChatMessage.system(merged.toString()));
            } else if (!"system".equals(message.role())) {
                out.add(message);
            }
        }
        return out;
    }
}
