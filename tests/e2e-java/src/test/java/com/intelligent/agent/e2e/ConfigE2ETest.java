package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：运行时配置 — 读取 / 修改（改完恢复）。 */
class ConfigE2ETest extends E2EBaseTest {

    @Test
    void getRuntimeConfig() throws Exception {
        Response r = client.get("/api/config/runtime");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        // Java 契约：config 嵌套在 "config" 字段（Python 代理时代为扁平结构）
        assertThat(data.get("config")).isInstanceOf(Map.class);
    }

    @Test
    void patchRuntimeConfigAndRestore() throws Exception {
        Map<String, Object> original = client.json(client.get("/api/config/runtime"));
        @SuppressWarnings("unchecked")
        Map<String, Object> originalConfig = (Map<String, Object>) original.get("config");
        Object originalTemp = originalConfig.get("ollama_temperature");
        try {
            Response r2 = client.patch("/api/config/runtime",
                    Map.of("ollama_temperature", 0.75));
            assertThat(r2.status()).isEqualTo(200);
            Map<String, Object> after = client.json(client.get("/api/config/runtime"));
            @SuppressWarnings("unchecked")
            Map<String, Object> afterConfig = (Map<String, Object>) after.get("config");
            assertThat(afterConfig.get("ollama_temperature")).isEqualTo(0.75);
        } finally {
            if (originalTemp != null) {
                client.patch("/api/config/runtime",
                        Map.of("ollama_temperature", originalTemp));
            }
        }
    }
}
