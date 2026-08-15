package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：云端服务商 — 预设 / 列表 / CRUD / 停用。 */
class CloudE2ETest extends E2EBaseTest {

    @Test
    void cloudPresets() throws Exception {
        Response r = client.get("/api/cloud/presets");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).isInstanceOf(Map.class);
    }

    @Test
    void listCloudProviders() throws Exception {
        Response r = client.get("/api/cloud/providers");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("providers")).isInstanceOf(List.class);
    }

    @Test
    void cloudProviderCrud() throws Exception {
        Map<String, Object> payload = Map.of(
                "name", "E2E测试服务商",
                "provider", "openai",
                "base_url", "https://api.openai.com/v1",
                "api_key", "sk-e2e-test-key",
                "model", "gpt-4o-mini");
        Response r = client.post("/api/cloud/providers", payload);
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        Object providerId = data.get("id");
        if (providerId == null) {
            providerId = data.get("provider_id");
        }
        if (providerId == null && data.get("provider") instanceof Map) {
            providerId = ((Map<?, ?>) data.get("provider")).get("id");
        }
        assertThat(providerId).as("创建未返回 id: %s", data).isNotNull();

        Map<String, Object> providers = client.json(client.get("/api/cloud/providers"));
        List<?> list = (List<?>) providers.get("providers");
        List<Object> ids = new java.util.ArrayList<>();
        for (Object p : list) {
            ids.add(((Map<?, ?>) p).get("id"));
        }
        assertThat(ids)
                .contains(providerId);

        Response r3 = client.put("/api/cloud/providers/" + providerId,
                new java.util.LinkedHashMap<>() {{
                    putAll(payload);
                    put("model", "gpt-4o");
                }});
        assertThat(r3.status()).isEqualTo(200);

        Response r4 = client.delete("/api/cloud/providers/" + providerId);
        assertThat(r4.status()).isEqualTo(200);
        assertThat(client.json(r4).get("success")).isNotEqualTo(false);
    }

    @Test
    void deactivateCloud() throws Exception {
        Response r = client.post("/api/cloud/deactivate", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).isInstanceOf(Map.class);
    }
}
