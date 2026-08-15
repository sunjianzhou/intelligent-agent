package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：会话历史 — 列表 / 不存在会话 / 元数据。 */
class ConversationsE2ETest extends E2EBaseTest {

    @Test
    void listConversations() throws Exception {
        Response r = client.get("/api/conversations");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).containsKey("sessions");
    }

    @Test
    void getConversationNotFound() throws Exception {
        Response r = client.get("/api/conversations/nonexistent-id-xyz");
        // Java 契约：guarded() 对不存在会话返回 404
        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    void deleteConversationNotFound() throws Exception {
        Response r = client.delete("/api/conversations/nonexistent-id-xyz");
        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    void listReturnsMetadata() throws Exception {
        Map<String, Object> data = client.json(client.get("/api/conversations"));
        List<?> sessions = (List<?>) data.getOrDefault("sessions", List.of());
        for (Object session : sessions) {
            assertThat(session).isInstanceOf(Map.class);
            Map<?, ?> s = (Map<?, ?>) session;
            assertThat(s.containsKey("session_id") || s.containsKey("id")).isTrue();
            assertThat(s.containsKey("updated_at") || s.containsKey("created_at")).isTrue();
        }
    }
}
