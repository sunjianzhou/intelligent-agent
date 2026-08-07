package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.llm.cloud.OpenAiCompatibleLlmProvider;
import com.intelligent.agent.web.ai.llm.ollama.OllamaLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmProviderRouter：按请求模型解析本地 Ollama / 云端 OpenAI 兼容 provider。
 * 云端仅在所有凭据/地址配置齐全时参与路由，否则一律回退 Ollama。
 */
class LlmProviderRouterTest {

    private OllamaLlmProvider ollama;
    private OpenAiCompatibleLlmProvider cloud;
    private LlmProviderRouter router;

    @BeforeEach
    void setUp() {
        ollama = new OllamaLlmProvider("http://localhost:11434", "qwen2.5:7b",
                OllamaOptions.defaults(), Duration.ofSeconds(10));
        cloud = new OpenAiCompatibleLlmProvider("http://localhost:9000", "sk-test-123",
                "deepseek-chat", Duration.ofSeconds(10));
        router = new LlmProviderRouter(ollama, cloud, List.of("deepseek-chat", "deepseek-reasoner"));
    }

    @Test
    void resolvesCloudProviderForConfiguredCloudModel() {
        assertThat(router.forUser("u1", "deepseek-chat")).isSameAs(cloud);
    }

    @Test
    void resolvesCloudProviderForAnyConfiguredCloudModel() {
        assertThat(router.forUser("u1", "deepseek-reasoner")).isSameAs(cloud);
    }

    @Test
    void resolvesOllamaProviderForLocalModel() {
        assertThat(router.forUser("u1", "qwen2.5:7b")).isSameAs(ollama);
    }

    @Test
    void emptyModelUsesCloudWhenConfigured() {
        assertThat(router.forUser("u1", "")).isSameAs(cloud);
    }

    @Test
    void fallsBackToOllamaWhenCloudNotConfigured() {
        OpenAiCompatibleLlmProvider unconfigured =
                new OpenAiCompatibleLlmProvider("", "", "", Duration.ofSeconds(10));
        LlmProviderRouter noCloud = new LlmProviderRouter(ollama, unconfigured, List.of("deepseek-chat"));
        assertThat(noCloud.forUser("u1", "deepseek-chat")).isSameAs(ollama);
        assertThat(noCloud.forUser("u1", "")).isSameAs(ollama);
    }
}
