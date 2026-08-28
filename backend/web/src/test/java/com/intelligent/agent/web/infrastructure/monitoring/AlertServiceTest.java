package com.intelligent.agent.web.infrastructure.monitoring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R-13：告警限频 / 开关 / 载荷形状。 */
class AlertServiceTest {

    @Test
    void deliversAlertWithTypeAndData() {
        List<Map<String, Object>> received = new ArrayList<>();
        AlertService service = new AlertService(true, received::add, 1000);

        boolean sent = service.alert("circuit_breaker_opened",
                "模型熔断器打开: qwen2.5:7b", Map.of("model", "qwen2.5:7b"));

        assertThat(sent).isTrue();
        assertThat(received).hasSize(1);
        Map<String, Object> entry = received.get(0);
        assertThat(entry.get("type")).isEqualTo("alert");
        assertThat(entry.get("alert_type")).isEqualTo("circuit_breaker_opened");
        assertThat(String.valueOf(entry.get("message"))).contains("qwen2.5:7b");
        assertThat(entry.get("timestamp")).isNotNull();
        assertThat(entry.get("data")).isEqualTo(Map.of("model", "qwen2.5:7b"));
    }

    @Test
    void rateLimitsSameTypeWithinInterval() throws Exception {
        List<Map<String, Object>> received = new ArrayList<>();
        AlertService service = new AlertService(true, received::add, 60_000);

        assertThat(service.alert("inference_queue_full", "a", null)).isTrue();
        assertThat(service.alert("inference_queue_full", "b", null)).isFalse();
        // 不同类型不受限
        assertThat(service.alert("circuit_breaker_opened", "c", null)).isTrue();
        assertThat(received).hasSize(2);
    }

    @Test
    void disabledServiceDropsAlerts() {
        List<Map<String, Object>> received = new ArrayList<>();
        AlertService service = new AlertService(false, received::add, 1000);

        assertThat(service.alert("circuit_breaker_opened", "x", null)).isFalse();
        assertThat(received).isEmpty();
    }
}
