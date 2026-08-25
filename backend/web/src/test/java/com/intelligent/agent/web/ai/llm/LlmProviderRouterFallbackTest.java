package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerConfig;
import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerRegistry;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-02 fallback 链测试：熔断 OPEN 直接切换（不消耗日额度）、仅超时/5xx/429 消耗额度、
 * 流式降级事件、已发 token 不降级、无链保持原行为、降级不写语义缓存。
 */
class LlmProviderRouterFallbackTest {

    /** 按 turn.model() 决定失败/成功的 fake provider。 */
    static class FailByModelProvider implements LlmProvider {
        final Set<String> failing;

        FailByModelProvider(String... failingModels) {
            this.failing = Set.of(failingModels);
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            if (failing.contains(turn.model())) {
                return Flux.error(new LlmProviderException("provider returned HTTP 500"));
            }
            return Flux.just(ModelEvent.token("ok-" + turn.model()),
                    ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            if (failing.contains(turn.model())) {
                return Mono.error(new LlmProviderException("provider returned HTTP 500"));
            }
            return Mono.just("answer-" + turn.model());
        }
    }

    /** 先发一个 token 再失败的 provider（流式已发 token 后不得降级）。 */
    static class FlakyStreamProvider extends FailByModelProvider {
        FlakyStreamProvider(String... failingModels) {
            super(failingModels);
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            if (failing.contains(turn.model())) {
                return Flux.concat(
                        Flux.just(ModelEvent.token("partial")),
                        Flux.error(new LlmProviderException("provider returned HTTP 500")));
            }
            return super.stream(turn);
        }
    }

    private static ChatTurn turn(String model) {
        return new ChatTurn("u1", model, List.of(ChatMessage.user("hi")), Map.of());
    }

    private static LlmProviderRouter router(FailByModelProvider local, Map<String, List<String>> chains,
                                            CircuitBreakerRegistry breaker,
                                            FallbackRateLimiter limiter) {
        return new LlmProviderRouter(local, null, List.of(), breaker, null,
                Duration.ofSeconds(5), chains, Duration.ofSeconds(60), limiter);
    }

    @Test
    void fallsBackOn5xxAndReportsEffectiveModel() {
        FallbackRateLimiter limiter = new FallbackRateLimiter(10);
        LlmProviderRouter router = router(
                new FailByModelProvider("modelA"),
                Map.of("default", List.of("modelA", "modelB")), null, limiter);
        LlmProviderRouter.FallbackTracker tracker =
                new LlmProviderRouter.FallbackTracker("modelA");

        LlmProviderRouter.FallbackResult result =
                router.completeWithFallback("u1", "modelA", turn("modelA"), null, tracker)
                        .block();

        assertThat(result.effectiveModel()).isEqualTo("modelB");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.response().content()).isEqualTo("answer-modelB");
        assertThat(tracker.used()).isTrue();
        assertThat(tracker.effectiveModel()).isEqualTo("modelB");
        assertThat(tracker.reason()).isEqualTo("http_5xx");
        assertThat(limiter.remainingToday()).isEqualTo(9); // 5xx 消耗一次额度
    }

    @Test
    void circuitOpenSwitchesWithoutConsumingDailyBudget() {
        FallbackRateLimiter limiter = new FallbackRateLimiter(10);
        CircuitBreakerRegistry breaker = new CircuitBreakerRegistry(
                new CircuitBreakerConfig(true, 1, Duration.ofMinutes(5), 100));
        LlmProviderRouter router = router(
                new FailByModelProvider("modelA"),
                Map.of("default", List.of("modelA", "modelB")), breaker, limiter);

        // 预触发 modelA 熔断（threshold=1，一次失败即 OPEN）
        assertThatThrownBy(() -> router.forUser("u1", "modelA").complete(turn("modelA")).block())
                .isInstanceOf(LlmProviderException.class);

        LlmProviderRouter.FallbackTracker tracker =
                new LlmProviderRouter.FallbackTracker("modelA");
        LlmProviderRouter.FallbackResult result =
                router.completeWithFallback("u1", "modelA", turn("modelA"), null, tracker)
                        .block();

        assertThat(result.effectiveModel()).isEqualTo("modelB");
        assertThat(tracker.reason()).isEqualTo("circuit_open");
        // 熔断 OPEN 直切不消耗日额度
        assertThat(limiter.remainingToday()).isEqualTo(10);
    }

    @Test
    void transientFailuresExhaustDailyBudgetThenErrorPropagates() {
        FallbackRateLimiter limiter = new FallbackRateLimiter(1);
        LlmProviderRouter router = router(
                new FailByModelProvider("modelA"),
                Map.of("default", List.of("modelA", "modelB")), null, limiter);

        LlmProviderRouter.FallbackTracker tracker =
                new LlmProviderRouter.FallbackTracker("modelA");
        LlmProviderRouter.FallbackResult first =
                router.completeWithFallback("u1", "modelA", turn("modelA"), null, tracker)
                        .block();
        assertThat(first.effectiveModel()).isEqualTo("modelB");
        assertThat(limiter.remainingToday()).isZero();

        // 日额度耗尽：第二次降级请求直接失败（防云端成本失控）
        assertThatThrownBy(() -> router.completeWithFallback(
                "u1", "modelA", turn("modelA"), null, tracker).block())
                .isInstanceOf(LlmProviderException.class);
    }

    @Test
    void nonRetryableErrorDoesNotFallBack() {
        FailByModelProvider local = new FailByModelProvider() {
            @Override
            public Mono<String> complete(ChatTurn t) {
                return Mono.error(new LlmProviderException("invalid request: bad args"));
            }
        };
        LlmProviderRouter router = router(
                local, Map.of("default", List.of("modelA", "modelB")), null,
                new FallbackRateLimiter(10));

        assertThatThrownBy(() -> router.completeWithFallback(
                "u1", "modelA", turn("modelA"), null,
                new LlmProviderRouter.FallbackTracker("modelA")).block())
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("invalid request");
    }

    @Test
    void noChainKeepsSingleProviderBehavior() {
        LlmProviderRouter router = router(
                new FailByModelProvider("modelB"),
                Map.of(), null, new FallbackRateLimiter(10));

        LlmProviderRouter.FallbackResult result =
                router.completeWithFallback("u1", "modelA", turn("modelA"), null,
                        new LlmProviderRouter.FallbackTracker("modelA")).block();

        assertThat(result.effectiveModel()).isEqualTo("modelA");
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.response().content()).isEqualTo("answer-modelA");
    }

    @Test
    void defaultChainPrependsRequestedModelNotInChain() {
        LlmProviderRouter router = router(
                new FailByModelProvider("modelX"),
                Map.of("default", List.of("modelB")), null, new FallbackRateLimiter(10));

        LlmProviderRouter.FallbackResult result =
                router.completeWithFallback("u1", "modelX", turn("modelX"), null,
                        new LlmProviderRouter.FallbackTracker("modelX")).block();

        // 先试用户指定 modelX，失败后再按 default 链降级到 modelB
        assertThat(result.effectiveModel()).isEqualTo("modelB");
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    void streamFallsBackAndEmitsModelFallbackEvent() {
        LlmProviderRouter router = router(
                new FailByModelProvider("modelA"),
                Map.of("default", List.of("modelA", "modelB")), null,
                new FallbackRateLimiter(10));
        LlmProviderRouter.FallbackTracker tracker =
                new LlmProviderRouter.FallbackTracker("modelA");

        List<ModelEvent> events = router.streamWithFallback(
                        "u1", "modelA", turn("modelA"), tracker)
                .collectList().block();

        assertThat(events).extracting(ModelEvent::type)
                .containsExactly("model_fallback", "token", "done");
        assertThat(events.get(1).data()).isEqualTo("ok-modelB");
        assertThat(tracker.used()).isTrue();
        assertThat(tracker.effectiveModel()).isEqualTo("modelB");
    }

    @Test
    void streamDoesNotFallBackAfterTokensEmitted() {
        LlmProviderRouter router = router(
                new FlakyStreamProvider("modelA"),
                Map.of("default", List.of("modelA", "modelB")), null,
                new FallbackRateLimiter(10));
        LlmProviderRouter.FallbackTracker tracker =
                new LlmProviderRouter.FallbackTracker("modelA");

        List<ModelEvent> events = router.streamWithFallback(
                        "u1", "modelA", turn("modelA"), tracker)
                .onErrorResume(e -> Flux.just(ModelEvent.error(e.getMessage())))
                .collectList().block();

        // 已发出 partial token 后失败 → 不降级，直接 error
        assertThat(events).extracting(ModelEvent::type)
                .containsExactly("token", "error");
        assertThat(tracker.used()).isFalse();
    }

    @Test
    void recordTurnSkipsSemanticCacheWhenFallbackUsed() {
        VectorMemoryRepository repository = new VectorMemoryRepository();
        SemanticResponseCache cache = new SemanticResponseCache();
        ConversationMemoryService service = new ConversationMemoryService(
                repository, cache, new MemoryDistillationService(), task -> { });
        AgentRequestContext ctx = new AgentRequestContext(
                "u1", "我的问题", null, null, null, null, true, true, null, Map.of());

        service.recordTurn(ctx, "降级答案", true);
        assertThat(cache.get("u1", null, null, "我的问题")).isEmpty();

        service.recordTurn(ctx, "正常答案", false);
        assertThat(cache.get("u1", null, null, "我的问题")).contains("正常答案");
    }
}
