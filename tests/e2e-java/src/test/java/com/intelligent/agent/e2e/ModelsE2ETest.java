package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：模型 — 列表 / 去重 / 切换（存在与不存在）。 */
class ModelsE2ETest extends E2EBaseTest {

    @Test
    void modelsList() throws Exception {
        Response r = client.get("/api/models");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("available_models")).isInstanceOf(List.class);
    }

    @Test
    void modelsNoDuplicates() throws Exception {
        Map<String, Object> data = client.json(client.get("/api/models"));
        List<?> models = (List<?>) data.get("available_models");
        Set<String> unique = new HashSet<>();
        for (Object m : models) {
            unique.add(String.valueOf(m));
        }
        assertThat(unique.size()).isEqualTo(models.size());
    }

    @Test
    void switchToExistingModel() throws Exception {
        Map<String, Object> data = client.json(client.get("/api/models"));
        List<?> allModels = (List<?>) data.getOrDefault("available_models", List.of());
        String cloudModel = String.valueOf(data.getOrDefault("cloud_model", ""));
        List<String> localModels = allModels.stream()
                .map(String::valueOf)
                .filter(m -> !m.toLowerCase().contains("dolphin") && !m.equals(cloudModel))
                .toList();
        Assumptions.assumeFalse(localModels.isEmpty(), "没有可用本地模型，跳过切换测试");

        String target = localModels.get(0);
        String original = String.valueOf(data.getOrDefault("current_model", ""));
        Response r = client.post("/api/model/switch", Map.of("model", target));
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r).get("success")).isEqualTo(true);
        if (!original.isBlank()) {
            client.post("/api/model/switch", Map.of("model", original));
        }
    }

    @Test
    void switchToNonexistentModelFails() throws Exception {
        Response r = client.post("/api/model/switch", Map.of("model", "nonexistent-model-xyz:999"));
        Map<String, Object> data = client.json(r);
        assertThat(data.get("success")).isNotEqualTo(true);
    }
}
