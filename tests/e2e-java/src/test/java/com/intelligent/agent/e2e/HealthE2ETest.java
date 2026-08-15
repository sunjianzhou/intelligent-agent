package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：健康检查 — Java / python 兼容端点 / 系统信息 / 系统资源。 */
class HealthE2ETest extends E2EBaseTest {

    @Test
    void javaHealth() throws Exception {
        Response r = client.get("/api/health");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("status")).isEqualTo("UP");
        assertThat(data).containsKey("service");
        assertThat(data).containsKey("timestamp");
    }

    @Test
    void pythonHealthCompatEndpoint() throws Exception {
        Response r = client.get("/api/python/health");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r).get("status")).isEqualTo("java-only");
    }

    @Test
    void systemInfo() throws Exception {
        Response r = client.get("/api/system/info");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.containsKey("agent_model") || data.containsKey("ollama_available"))
                .isTrue();
    }

    @Test
    void systemResources() throws Exception {
        Response r = client.get("/api/system/resources");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).isInstanceOf(Map.class);
    }
}
