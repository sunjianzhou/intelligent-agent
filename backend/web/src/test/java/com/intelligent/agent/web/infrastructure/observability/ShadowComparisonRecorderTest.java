package com.intelligent.agent.web.infrastructure.observability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shadow 对比记录器测试（Plan 3 / Task 5）：
 * 输出必须脱敏 —— 不含 Authorization/Bearer、不含私密 prompt 内容。
 */
class ShadowComparisonRecorderTest {

    @Test
    void recordRedactsBearerTokensAndPrivatePrompt() {
        ShadowComparisonRecorder recorder = new ShadowComparisonRecorder();

        ShadowComparison result = recorder.record(new ShadowComparison(
                "{\"Authorization\":\"Bearer eyJ.abc\",\"message\":\"private prompt内容\",\"response\":\"hi\"}",
                "{\"response\":\"hi\"}",
                "tool-trace-hash",
                List.of("r1", "r2"),
                42L));

        assertThat(result.toJson())
                .doesNotContain("Bearer ")
                .doesNotContain("private prompt");
    }

    @Test
    void recordKeepsWireShapeAndLatency() {
        ShadowComparisonRecorder recorder = new ShadowComparisonRecorder();

        ShadowComparison result = recorder.record(new ShadowComparison(
                "{\"type\":\"token\"}", "{\"type\":\"token\"}",
                "abc", List.of(), 12L));

        assertThat(result.toJson())
                .contains("\"tool_trace_hash\":\"abc\"")
                .contains("\"latency_ms\":12")
                .contains("token");
    }

    @Test
    void historyReturnsRecentComparisons() {
        ShadowComparisonRecorder recorder = new ShadowComparisonRecorder(5);
        for (int i = 0; i < 8; i++) {
            recorder.record(new ShadowComparison(
                    "{\"i\":" + i + "}", "{\"i\":" + i + "}", "h" + i, List.of(), i));
        }

        assertThat(recorder.history()).hasSize(5);
        assertThat(recorder.history().get(0).latencyMs()).isEqualTo(7);
    }
}
