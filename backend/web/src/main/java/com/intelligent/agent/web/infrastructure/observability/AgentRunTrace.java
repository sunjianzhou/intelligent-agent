package com.intelligent.agent.web.infrastructure.observability;

import java.time.Instant;
import java.util.List;

/**
 * 一次 Agent 运行的完整追踪（G4）：
 * requestId 关联 HTTP/WS 请求，spans 记录 llm/tool/rag/memory 各阶段。
 */
public record AgentRunTrace(
        String requestId,
        String userId,
        String sessionId,
        String channel,
        String model,
        Instant startedAt,
        long durationMs,
        String status,
        List<TraceSpan> spans) {

    public AgentRunTrace {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }
}
