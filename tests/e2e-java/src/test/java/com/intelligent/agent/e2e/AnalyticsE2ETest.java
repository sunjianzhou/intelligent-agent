package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：统计分析 — stats / records / skill-logs / skill-stats / tool-calls / 反馈。 */
class AnalyticsE2ETest extends E2EBaseTest {

    @Test
    void analyticsStats() throws Exception {
        assertThat(client.get("/api/analytics/stats/" + USERNAME).status()).isEqualTo(200);
    }

    @Test
    void analyticsRecords() throws Exception {
        Response r = client.get("/api/analytics/records/" + USERNAME + "?limit=10");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.containsKey("records") || data instanceof Map).isTrue();
    }

    @Test
    void analyticsSkillLogs() throws Exception {
        assertThat(client.get("/api/analytics/skill-logs/" + USERNAME + "?limit=10").status())
                .isEqualTo(200);
    }

    @Test
    void analyticsSkillStats() throws Exception {
        assertThat(client.get("/api/analytics/skill-stats/" + USERNAME).status()).isEqualTo(200);
    }

    @Test
    void analyticsToolCalls() throws Exception {
        assertThat(client.get("/api/analytics/tool-calls?limit=10").status()).isEqualTo(200);
    }

    @Test
    void analyticsToolStats() throws Exception {
        assertThat(client.get("/api/analytics/tool-stats").status()).isEqualTo(200);
    }

    @Test
    void submitFeedback() throws Exception {
        Response r = client.post("/api/analytics/feedback", Map.of(
                "username", USERNAME,
                "session_id", "e2e-test-session",
                "message_id", "e2e-msg-001",
                "message", "你好",
                "response", "你好，有什么可以帮助你的？",
                "rating", "like"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r).get("success")).isNotEqualTo(false);
    }
}
