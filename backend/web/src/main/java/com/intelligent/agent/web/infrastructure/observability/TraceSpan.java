package com.intelligent.agent.web.infrastructure.observability;

import java.util.Map;

/**
 * 单次 Agent 运行的观测跨度（G4）：
 * name 取 llm_call / tool_call / rag / memory / cache 之一。
 */
public record TraceSpan(
        String name,
        long startedAt,
        long durationMs,
        String status,
        Map<String, Object> details) {

    public TraceSpan {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static TraceSpan ok(String name, long startedAt, long durationMs,
                               Map<String, Object> details) {
        return new TraceSpan(name, startedAt, durationMs, "ok", details);
    }

    public static TraceSpan error(String name, long startedAt, long durationMs,
                                  Map<String, Object> details) {
        return new TraceSpan(name, startedAt, durationMs, "error", details);
    }
}
