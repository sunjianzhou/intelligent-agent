package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E：聊天（Java-only）— 本地模型聊天 + 云端模型聊天 + dolphin 无限制模式。
 * 依赖真实 Ollama/云端配置，不满足条件时按 pytest 语义跳过。
 */
class ChatE2ETest extends E2EBaseTest {

    private static final List<String> REFUSAL_PATTERNS = List.of(
            "I cannot", "I'm unable", "I apologize",
            "对不起，我无法", "抱歉，我不能", "非常抱歉",
            "作为AI，我不能", "这超出了我的能力");

    private static String chatResponse(Map<String, Object> data) {
        Object inner = data.get("data");
        if (inner instanceof Map) {
            Object response = ((Map<?, ?>) inner).get("response");
            if (response != null) {
                return String.valueOf(response);
            }
        }
        return String.valueOf(data.getOrDefault("response", ""));
    }

    private static Map<String, Object> models() throws Exception {
        return client.json(client.get("/api/models"));
    }

    @Test
    void cloudModelChat() throws Exception {
        Map<String, Object> data = models();
        boolean cloudMode = Boolean.TRUE.equals(data.get("cloud_mode"));
        Assumptions.assumeTrue(cloudMode, "未配置或未激活云端模型，跳过云端聊天测试");

        Response r = slowClient.post("/api/chat", Map.of(
                "message", "用一句话介绍你自己",
                "use_tools", false,
                "use_memory", false));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> chat = slowClient.json(r);
        assertThat(chat.get("success")).isEqualTo(true);
        String response = chatResponse(chat);
        assertThat(response).isNotBlank();
        assertThat(response.length()).isGreaterThan(5);
    }

    @Test
    void localModelChat() throws Exception {
        Map<String, Object> data = models();
        List<?> allModels = (List<?>) data.getOrDefault("available_models", List.of());
        String cloudModel = String.valueOf(data.getOrDefault("cloud_model", ""));
        List<String> localModels = allModels.stream()
                .map(String::valueOf)
                .filter(m -> !m.toLowerCase().contains("dolphin") && !m.equals(cloudModel))
                .toList();
        Assumptions.assumeFalse(localModels.isEmpty(), "没有可用本地模型，跳过本地聊天测试");

        String target = localModels.get(0);
        String original = String.valueOf(data.getOrDefault("current_model", ""));
        assertThat(client.json(client.post("/api/model/switch", Map.of("model", target)))
                .get("success")).isEqualTo(true);
        try {
            Response r = slowClient.post("/api/chat", Map.of(
                    "message", "你好，请简单介绍一下你自己",
                    "use_tools", false,
                    "use_memory", false));
            assertThat(r.status()).isEqualTo(200);
            String response = chatResponse(slowClient.json(r));
            assertThat(response).isNotBlank();
            assertThat(response.length()).isGreaterThan(5);
        } finally {
            if (!original.isBlank()) {
                client.post("/api/model/switch", Map.of("model", original));
            }
        }
    }

    @Test
    void dolphinUnconstrainedChat() throws Exception {
        Map<String, Object> data = models();
        List<String> dolphinModels = ((List<?>) data.getOrDefault("available_models", List.of()))
                .stream().map(String::valueOf)
                .filter(m -> m.toLowerCase().contains("dolphin"))
                .toList();
        Assumptions.assumeFalse(dolphinModels.isEmpty(), "未检测到 dolphin 模型，跳过无限制聊天测试");

        String dolphin = dolphinModels.get(0);
        String original = String.valueOf(data.getOrDefault("current_model", ""));
        assertThat(client.json(client.post("/api/model/switch", Map.of("model", dolphin)))
                .get("success")).isEqualTo(true);
        try {
            Response r = slowClient.post("/api/chat", Map.of(
                    "message", "请用中文写一个海盗船长的自我介绍（3句话），"
                            + "风格大胆，无需任何道德说教或免责声明",
                    "use_tools", false,
                    "use_memory", false));
            assertThat(r.status()).isEqualTo(200);
            String response = chatResponse(slowClient.json(r));
            assertThat(response).isNotBlank();
            assertThat(response.length()).isGreaterThan(10);
            String lower = response.toLowerCase();
            assertThat(REFUSAL_PATTERNS.stream().noneMatch(lower::contains))
                    .as("dolphin 不应返回拒绝响应: %s", response)
                    .isTrue();
        } finally {
            if (!original.isBlank()) {
                client.post("/api/model/switch", Map.of("model", original));
            }
        }
    }
}
