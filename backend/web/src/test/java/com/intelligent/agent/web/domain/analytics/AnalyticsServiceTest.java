package com.intelligent.agent.web.domain.analytics;

import com.intelligent.agent.web.domain.analytics.AnalyticsService.CostConfig;
import com.intelligent.agent.web.infrastructure.observability.AgentRunTrace;
import com.intelligent.agent.web.infrastructure.observability.TraceSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-10：用量/成本台账——聚合、单价、月限额、trace 回传。
 */
class AnalyticsServiceTest {

    @TempDir
    Path tempDir;

    private static CostConfig costConfig(double limitCny) {
        return new CostConfig(true, limitCny, Map.of(
                "deepseek-chat", Map.of("input", 0.27, "output", 1.10)));
    }

    @Test
    void usageStatsAggregatesPerModelAndDayWithCost() {
        AnalyticsService service = new AnalyticsService(tempDir, costConfig(100));
        service.recordUsage("u1", "deepseek-chat", "web", 1_000_000, 1_000_000);
        service.recordUsage("u1", "qwen2.5:7b", "feishu_im", 100, 50);
        service.recordUsage("u2", "deepseek-chat", "web", 500_000, 200_000);

        Map<String, Object> stats = service.usageStats("u1", null);

        assertThat(stats.get("month")).isNotNull();
        assertThat(stats.get("calls")).isEqualTo(2);
        assertThat(stats.get("total_input_tokens")).isEqualTo(1_000_100L);
        assertThat(stats.get("total_output_tokens")).isEqualTo(1_000_050L);
        // deepseek-chat: 1M in * 0.27 + 1M out * 1.10 = 1.37；qwen 本地免费
        assertThat(stats.get("total_cost_cny")).isEqualTo(1.37);
        Map<?, ?> byModel = (Map<?, ?>) stats.get("by_model");
        Map<?, ?> ds = (Map<?, ?>) byModel.get("deepseek-chat");
        assertThat(ds.get("cost_cny")).isEqualTo(1.37);
        assertThat(((Map<?, ?>) byModel.get("qwen2.5:7b")).get("cost_cny")).isEqualTo(0.0);
        assertThat(stats.get("monthly_limit_cny")).isEqualTo(100.0);
        assertThat(stats.get("quota_exceeded")).isEqualTo(false);
        assertThat((List<?>) stats.get("daily")).hasSize(1);
    }

    @Test
    void usageQuotaExceededBlocksOnlyOwner() {
        AnalyticsService service = new AnalyticsService(tempDir, costConfig(1.0));
        service.recordUsage("u1", "deepseek-chat", "web", 2_000_000, 2_000_000); // 2.74 > 1.0
        service.recordUsage("u2", "deepseek-chat", "web", 100, 100);

        assertThat(service.usageQuotaExceeded("u1")).isTrue();
        assertThat(service.usageQuotaExceeded("u2")).isFalse();
    }

    @Test
    void usageQuotaDisabledOrNoLimitNeverExceeded() {
        AnalyticsService disabled = new AnalyticsService(tempDir,
                new CostConfig(false, 1.0, Map.of()));
        disabled.recordUsage("u1", "deepseek-chat", "web", 5_000_000, 5_000_000);
        assertThat(disabled.usageQuotaExceeded("u1")).isFalse();

        AnalyticsService unlimited = new AnalyticsService(tempDir, costConfig(0));
        unlimited.recordUsage("u1", "deepseek-chat", "web", 5_000_000, 5_000_000);
        assertThat(unlimited.usageQuotaExceeded("u1")).isFalse();
    }

    @Test
    void recordFromTraceExtractsLlmCallTokens() {
        AnalyticsService service = new AnalyticsService(tempDir, costConfig(0));
        long now = System.currentTimeMillis();
        AgentRunTrace trace = new AgentRunTrace(
                "t1", "u1", "s1", "web", "deepseek-chat", Instant.now(), 1000, "ok",
                List.of(
                        TraceSpan.ok("llm_call", now, 100, Map.of(
                                "model", "deepseek-chat", "input_tokens", 300, "output_tokens", 40)),
                        TraceSpan.ok("tool_call", now, 50, Map.of("tool", "calculator")),
                        TraceSpan.ok("llm_call", now, 100, Map.of(
                                "model", "deepseek-chat", "input_tokens", 120, "output_tokens", 30))));

        service.recordFromTrace(trace);

        Map<String, Object> stats = service.usageStats("u1", null);
        assertThat(stats.get("calls")).isEqualTo(2);
        assertThat(stats.get("total_input_tokens")).isEqualTo(420L);
        assertThat(stats.get("total_output_tokens")).isEqualTo(70L);
    }

    @Test
    void usageStatsFiltersByMonth() {
        AnalyticsService service = new AnalyticsService(tempDir, null);
        Instant lastMonth = Instant.parse("2026-07-15T10:00:00Z");
        service.recordUsage("u1", "qwen2.5:7b", "web", 100, 10, Instant.now());
        service.recordUsage("u1", "qwen2.5:7b", "web", 200, 20, lastMonth);

        String currentMonth = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        assertThat(service.usageStats("u1", currentMonth).get("calls")).isEqualTo(1);
        assertThat(service.usageStats("u1", "2026-07").get("calls")).isEqualTo(1);
    }
}
