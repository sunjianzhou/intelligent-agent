package com.intelligent.agent.web.service;

import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.OllamaOptions;
import com.intelligent.agent.web.ai.llm.cloud.OpenAiCompatibleLlmProvider;
import com.intelligent.agent.web.ai.llm.ollama.OllamaLlmProvider;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 云端激活必须真实联动 LLM provider 与路由（Fix P0-1），
 * 而不仅是更新 ModelService 的展示状态。
 */
class CloudServiceTest {

    @TempDir
    Path dataDir;

    @Test
    void activateReconfiguresProviderAndRouter() {
        OllamaLlmProvider ollama = new OllamaLlmProvider("http://localhost:11434", "qwen2.5:7b",
                OllamaOptions.defaults(), Duration.ofSeconds(10));
        OpenAiCompatibleLlmProvider cloud = new OpenAiCompatibleLlmProvider(
                "", "", "", Duration.ofSeconds(10));
        LlmProviderRouter router = new LlmProviderRouter(ollama, cloud, List.of());
        ModelService modelService = new ModelService();
        CloudService cloudService = new CloudService(modelService, cloud, router);
        ReflectionTestUtils.setField(cloudService, "dataDir", dataDir.toString());

        Map<String, Object> created = cloudService.createProvider(Map.of(
                "provider", "custom",
                "base_url", "http://localhost:9000/v1",
                "api_key", "sk-runtime",
                "model", "deepseek-chat"));
        String providerId = String.valueOf(created.get("provider"));
        String id = (String) ((Map<?, ?>) created.get("provider")).get("id");

        cloudService.activate(id);

        assertThat(cloud.isConfigured()).isTrue();
        assertThat(router.forUser("u1", "deepseek-chat")).isSameAs(cloud);
        assertThat(router.forUser("u1", "qwen2.5:7b")).isSameAs(ollama);

        cloudService.deactivate();

        assertThat(cloud.isConfigured()).isFalse();
        assertThat(router.forUser("u1", "deepseek-chat")).isSameAs(ollama);
        assertThat(providerId).isNotBlank();
    }

    @Test
    void apiKeyIsEncryptedAtRestAndMaskedInResponses() throws Exception {
        CloudService cloudService = new CloudService(
                new ModelService(), null, null, new SecretCrypto("test-secret-0123456789"));
        ReflectionTestUtils.setField(cloudService, "dataDir", dataDir.toString());

        cloudService.createProvider(Map.of(
                "provider", "custom",
                "base_url", "http://localhost:9000/v1",
                "api_key", "sk-plaintext-secret",
                "model", "deepseek-chat"));

        String raw = Files.readString(
                dataDir.resolve("cloud_providers.json"));
        assertThat(raw).contains("enc:");
        assertThat(raw).doesNotContain("sk-plaintext-secret");

        Map<String, Object> listed = cloudService.listProviders();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers =
                (List<Map<String, Object>>) listed.get("providers");
        assertThat(providers).hasSize(1);
        assertThat(String.valueOf(providers.get(0).get("api_key")))
                .doesNotContain("sk-plaintext-secret")
                .contains("*");
    }
}
