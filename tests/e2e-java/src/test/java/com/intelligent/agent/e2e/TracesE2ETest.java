package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E：Agent 运行追踪（G4）— 带 request_id 发一次真实聊天，之后能按 id 取到
 * 包含 llm_call span 的完整 trace；无本地模型时跳过。
 */
class TracesE2ETest extends E2EBaseTest {

    @Test
    void chatProducesFetchableTrace() throws Exception {
        Map<String, Object> models = client.json(client.get("/api/models"));
        List<?> allModels = (List<?>) models.getOrDefault("available_models", List.of());
        Assumptions.assumeFalse(allModels.isEmpty(), "没有可用模型，跳过追踪链路测试");

        String requestId = "e2e-trace-" + Long.toHexString(System.nanoTime());
        Response rChat = slowClient.post("/api/chat", Map.of(
                "message", "请用一句话介绍你自己",
                "use_tools", false,
                "use_memory", false,
                "request_id", requestId));
        assertThat(rChat.status()).isEqualTo(200);

        Response rTrace = client.get("/api/traces/" + requestId);
        assertThat(rTrace.status()).isEqualTo(200);
        Map<String, Object> trace = client.json(rTrace);
        assertThat(trace.get("request_id")).isEqualTo(requestId);
        assertThat(trace.get("status")).isEqualTo("ok");
        List<?> spans = (List<?>) trace.get("spans");
        assertThat(spans).isNotEmpty();
        List<String> names = spans.stream()
                .map(s -> String.valueOf(((Map<?, ?>) s).get("name")))
                .toList();
        assertThat(names).contains("llm_call");

        // 清理
        client.delete("/api/traces/" + requestId);
        assertThat(client.get("/api/traces/" + requestId).status()).isEqualTo(404);
    }
}
