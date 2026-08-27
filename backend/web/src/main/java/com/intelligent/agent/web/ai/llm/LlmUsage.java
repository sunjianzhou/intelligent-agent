package com.intelligent.agent.web.ai.llm;

import java.util.Map;

/**
 * R-10：单次 LLM 调用的 token 用量（Ollama prompt_eval_count/eval_count、
 * OpenAI 兼容 usage.prompt_tokens/completion_tokens；缺失时为 0）。
 */
public record LlmUsage(long inputTokens, long outputTokens) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean hasTokens() {
        return inputTokens > 0 || outputTokens > 0;
    }

    /** 从事件 data（流式 done 事件携带）解析；无 token 字段返回 null。 */
    public static LlmUsage fromMap(Map<?, ?> data) {
        if (data == null) {
            return null;
        }
        long in = number(data.get("input_tokens"));
        long out = number(data.get("output_tokens"));
        if (in <= 0 && out <= 0) {
            return null;
        }
        return new LlmUsage(in, out);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
