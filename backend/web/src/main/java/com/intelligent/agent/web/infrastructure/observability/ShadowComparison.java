package com.intelligent.agent.web.infrastructure.observability;

import java.util.List;

/**
 * 一次 shadow 对比：Python 与 Java 的线上形状、工具轨迹哈希、召回 id 与延迟。
 * toJson() 输出经过脱敏，不含凭据与私密 prompt。
 */
public record ShadowComparison(
        String pythonWire,
        String javaWire,
        String toolTraceHash,
        List<String> retrievalIds,
        long latencyMs) {

    public String toJson() {
        return ShadowComparisonRecorder.toJson(this);
    }
}
