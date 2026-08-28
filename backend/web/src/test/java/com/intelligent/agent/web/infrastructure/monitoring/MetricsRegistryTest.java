package com.intelligent.agent.web.infrastructure.monitoring;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R-13：计数器与直方图快照（分位数）。 */
class MetricsRegistryTest {

    @Test
    void countsIncrementsAndSnapshots() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.increment("llm_calls");
        registry.increment("llm_calls");
        registry.incrementBy("llm_failures", 3);

        Map<String, Object> snapshot = registry.snapshot();
        Map<?, ?> counters = (Map<?, ?>) snapshot.get("counters");
        assertThat(counters.get("llm_calls")).isEqualTo(2L);
        assertThat(counters.get("llm_failures")).isEqualTo(3L);
        assertThat(registry.counter("llm_calls")).isEqualTo(2);
    }

    @Test
    void histogramComputesPercentiles() {
        MetricsRegistry registry = new MetricsRegistry(128);

        for (long i = 1; i <= 100; i++) {
            registry.record("llm_latency_ms", i * 10);
        }

        Map<String, Object> snapshot = registry.snapshot();
        Map<?, ?> histograms = (Map<?, ?>) snapshot.get("histograms");
        Map<?, ?> hist = (Map<?, ?>) histograms.get("llm_latency_ms");
        assertThat(hist.get("count")).isEqualTo(100);
        // 1..1000ms 的分位数：p50≈500、p90≈900、p99≈990、max=1000（线性插值有 ±1 误差）
        assertThat(((Number) hist.get("p50")).longValue()).isBetween(495L, 505L);
        assertThat(((Number) hist.get("p90")).longValue()).isBetween(895L, 905L);
        assertThat(((Number) hist.get("p99")).longValue()).isBetween(985L, 995L);
        assertThat(((Number) hist.get("max")).longValue()).isEqualTo(1000);
    }

    @Test
    void emptyHistogramSnapshotsCountZero() {
        Map<String, Object> snapshot = new MetricsRegistry().snapshot();
        assertThat(((Map<?, ?>) snapshot.get("histograms"))).isEmpty();
    }
}
