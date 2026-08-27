package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.tool.ToolCall;

import java.util.List;

/**
 * 非流式 LLM 回复（原生工具调用增强）。
 *
 * @param content   回复文本（无工具调用时使用）
 * @param toolCalls 原生工具调用列表（协议层解析结果；空 = 未触发原生调用）
 * @param usage     R-10 token 用量（可为 null）
 */
public record LlmResponse(String content, List<ToolCall> toolCalls, LlmUsage usage) {

    public LlmResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public LlmResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null);
    }

    public boolean hasNativeToolCalls() {
        return !toolCalls.isEmpty();
    }
}
