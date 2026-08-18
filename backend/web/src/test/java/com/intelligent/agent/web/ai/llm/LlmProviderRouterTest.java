package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.llm.cloud.OpenAiCompatibleLlmProvider;
import com.intelligent.agent.web.ai.llm.ollama.OllamaLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void registersRuntimeCloudModelAndRoutesToCloud() {
        router.clearCloudModels();
        assertThat(router.forUser("u1", "deepseek-chat")).isSameAs(ollama);

        router.registerCloudModel("deepseek-chat");

        assertThat(router.forUser("u1", "deepseek-chat")).isSameAs(cloud);
    }

    @Test
    void clearCloudModelsFallsBackToLocal() {
        router.clearCloudModels();

        assertThat(router.forUser("u1", "deepseek-chat")).isSameAs(ollama);
        assertThat(router.forUser("u1", "deepseek-reasoner")).isSameAs(ollama);
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

    @Test
    void forUserGatesCallsWhenInferenceGateProvided() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        AtomicInteger calls = new AtomicInteger();
        LlmProvider fake = new LlmProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Flux<ModelEvent> stream(ChatTurn turn) {
                return Flux.just(new ModelEvent("content", "ok"));
            }

            @Override
            public Mono<String> complete(ChatTurn turn) {
                calls.incrementAndGet();
                return Mono.just("ok");
            }
        };
        LlmProviderRouter gated = new LlmProviderRouter(fake, null, List.of(), null, gate);

        gate.acquire();
        CompletableFuture<String> result = new CompletableFuture<>();
        gated.forUser("u1", "").complete(ChatTurn.of("fake", List.of()))
                .subscribe(result::complete, result::completeExceptionally);

        Thread.sleep(200);
        assertThat(calls).hasValue(0);
        assertThat(gate.active()).isEqualTo(1);

        gate.release();
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(gate.active()).isZero();
    }
}
