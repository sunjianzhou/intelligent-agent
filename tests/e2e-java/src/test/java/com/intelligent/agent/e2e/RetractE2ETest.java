package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：消息撤回 — 边界用例（无需 LLM）+ 真实聊天撤回链路（需 LLM）。 */
class RetractE2ETest extends E2EBaseTest {

    private static String fakeSession() {
        return "e2e-retract-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void retractNonexistentSession() throws Exception {
        Response r = client.post("/api/conversations/" + fakeSession() + "/retract",
                Map.of("message_ids", List.of("fake-id-1")));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("success")).isEqualTo(true);
        assertThat(((Number) data.get("requested")).intValue()).isEqualTo(1);
        assertThat(((Number) data.get("deleted")).intValue()).isEqualTo(0);
        assertThat(data.get("deleted_ids")).isEqualTo(List.of());
    }

    @Test
    void retractNonexistentSessionAgainIsIdempotent() throws Exception {
        Response r = client.post("/api/conversations/" + fakeSession() + "/retract",
                Map.of("message_ids", List.of("fake-id-1")));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("success")).isEqualTo(true);
        assertThat(((Number) data.get("deleted")).intValue()).isEqualTo(0);
    }

    @Test
    void retractEmptyMessageIdsIsNoop() throws Exception {
        Response r = client.post("/api/conversations/" + fakeSession() + "/retract",
                Map.of("message_ids", List.of()));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("success")).isEqualTo(true);
        assertThat(((Number) data.get("requested")).intValue()).isEqualTo(0);
        assertThat(((Number) data.get("deleted")).intValue()).isEqualTo(0);
        assertThat(data.get("deleted_ids")).isEqualTo(List.of());
    }

    @Test
    void retractOverBatchLimitReturns400() throws Exception {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("mid-" + i);
        }
        Response r = client.post("/api/conversations/" + fakeSession() + "/retract",
                Map.of("message_ids", tooMany));
        assertThat(r.status()).isEqualTo(400);
        assertThat(client.json(r).get("success")).isEqualTo(false);
    }

    @Test
    void retractRemovesRealMessageEndToEnd() throws Exception {
        String sessionId = "e2e-retract-real-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        Response rChat = slowClient.post("/api/chat", Map.of(
                "message", "请用一句话回复：测试消息撤回功能",
                "use_tools", false,
                "use_memory", true,
                "session_id", sessionId));
        assertThat(rChat.status()).isEqualTo(200);
        Map<String, Object> chat = slowClient.json(rChat);
        assertThat(String.valueOf(chat.get("response"))).as("聊天未返回内容").isNotBlank();

        Object userMsgId = chat.get("user_message_id");
        Object assistantMsgId = chat.get("assistant_message_id");
        assertThat(userMsgId).as("缺少 user_message_id").isNotNull();
        assertThat(assistantMsgId).as("缺少 assistant_message_id").isNotNull();

        Set<String> before = messageIds(slowClient.get("/api/conversations/" + sessionId));
        assertThat(before).contains(String.valueOf(userMsgId), String.valueOf(assistantMsgId));

        Response rRetract = slowClient.post("/api/conversations/" + sessionId + "/retract",
                Map.of("message_ids", List.of(String.valueOf(userMsgId),
                        String.valueOf(assistantMsgId))));
        assertThat(rRetract.status()).isEqualTo(200);
        Map<String, Object> retract = slowClient.json(rRetract);
        assertThat(retract.get("success")).isEqualTo(true);
        assertThat(((Number) retract.get("requested")).intValue()).isEqualTo(2);
        assertThat(((Number) retract.get("deleted")).intValue()).isEqualTo(2);

        Set<String> after = messageIds(slowClient.get("/api/conversations/" + sessionId));
        assertThat(after).doesNotContain(String.valueOf(userMsgId),
                String.valueOf(assistantMsgId));

        slowClient.delete("/api/conversations/" + sessionId);
    }

    private static Set<String> messageIds(Response r) throws Exception {
        Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(r.body(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        Object session = data.get("session");
        List<?> messages;
        if (session instanceof Map) {
            Object raw = ((Map<?, ?>) session).get("messages");
            if (raw == null) {
                raw = List.of();
            }
            if (raw instanceof List<?> rawList) {
                messages = rawList;
            } else {
                messages = List.of();
            }
        } else {
            messages = List.of();
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Object m : messages) {
            if (m instanceof Map) {
                ids.add(String.valueOf(((Map<?, ?>) m).get("id")));
            }
        }
        return ids;
    }
}
