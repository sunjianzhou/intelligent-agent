package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** R-14：附图片时视觉模型校验——不支持则清晰报错且不调用 LLM，支持则正常流转。 */
class AgentOrchestratorVisionGuardTest {

    private static final String IMAGE = "aGVsbG8=";

    static class CountingProvider implements LlmProvider {
        final AtomicInteger streamCalls = new AtomicInteger();
        final AtomicInteger completeCalls = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            streamCalls.incrementAndGet();
            return Flux.just(ModelEvent.token("ok"), ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            completeCalls.incrementAndGet();
            return Mono.just("ok");
        }

        @Override
        public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
            completeCalls.incrementAndGet();
            return Mono.just(new LlmResponse("ok", List.of()));
        }
    }

    private static AgentOrchestrator guarded(CountingProvider provider, boolean enabled,
                                             List<String> visionModels) {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of()),
                null, null, null, AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS,
                null, null, null, null, null, null, null, null, null,
                enabled, visionModels);
    }

    private static AgentRequestContext withImage(String model) {
        return new AgentRequestContext(
                "u1", "这张图里有什么？", model, null, null, null, true, true,
                "web", Map.of(), IMAGE, null, false);
    }

    @Test
    void streamRejectsImageOnNonVisionModelWithoutCallingProvider() {
        CountingProvider provider = new CountingProvider();
        AgentOrchestrator orchestrator = guarded(provider, true, List.of());

        StepVerifier.create(orchestrator.stream(withImage("qwen2.5:7b")))
                .expectNextMatches(e -> e.type().equals("error")
                        && String.valueOf(e.data()).contains("不支持图片理解"))
                .verifyComplete();
        assertThat(provider.streamCalls).hasValue(0);
        assertThat(provider.completeCalls).hasValue(0);
    }

    @Test
    void completeRejectsImageOnNonVisionModel() {
        CountingProvider provider = new CountingProvider();
        AgentOrchestrator orchestrator = guarded(provider, true, List.of());

        String answer = orchestrator.complete(withImage("qwen2.5:7b")).block();

        assertThat(answer).contains("不支持图片理解");
        assertThat(provider.completeCalls).hasValue(0);
    }

    @Test
    void visionModelPassesThrough() {
        CountingProvider provider = new CountingProvider();
        AgentOrchestrator orchestrator = guarded(provider, true, List.of());

        StepVerifier.create(orchestrator.stream(withImage("qwen2.5-vl:7b")))
                .expectNextMatches(e -> e.type().equals("token"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
        // 无工具路径：非流式首轮复用内容，不发起二次流式
        assertThat(provider.completeCalls).hasValue(1);
    }

    @Test
    void explicitVisionModelListAllowsCustomModel() {
        CountingProvider provider = new CountingProvider();
        AgentOrchestrator orchestrator = guarded(provider, true, List.of("custom-vm"));

        StepVerifier.create(orchestrator.complete(withImage("custom-vm")))
                .expectNext("ok")
                .verifyComplete();
        assertThat(provider.completeCalls).hasValue(1);
    }

    @Test
    void disabledCheckAllowsNonVisionModel() {
        CountingProvider provider = new CountingProvider();
        AgentOrchestrator orchestrator = guarded(provider, false, List.of());

        StepVerifier.create(orchestrator.complete(withImage("qwen2.5:7b")))
                .expectNext("ok")
                .verifyComplete();
        assertThat(provider.completeCalls).hasValue(1);
    }

    @Test
    void imageAbsentSkipsGuard() {
        CountingProvider provider = new CountingProvider();
        AgentOrchestrator orchestrator = guarded(provider, true, List.of());

        StepVerifier.create(orchestrator.complete(
                        AgentRequestContext.of("u1", "你好")))
                .expectNext("ok")
                .verifyComplete();
        assertThat(provider.completeCalls).hasValue(1);
    }

    @Test
    void messageWithoutModelIsRejectedToo() {
        CountingProvider provider = new CountingProvider();
        AgentOrchestrator orchestrator = guarded(provider, true, List.of());

        String answer = orchestrator.complete(withImage(null)).block();
        assertThat(answer).contains("不支持图片理解");
        assertThat(provider.completeCalls).hasValue(0);
    }
}
