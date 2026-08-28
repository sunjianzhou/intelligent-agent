package com.intelligent.agent.web.ai.llm.circuit;

import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import com.intelligent.agent.web.infrastructure.monitoring.MetricsRegistry;

/** LLM provider 熔断装饰器（G6）：请求前 tryAcquire，熔断打开时快速失败；按结果记成功/失败。 */
public class CircuitBreakerLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final String modelKey;
    private final CircuitBreakerRegistry registry;

    CircuitBreakerLlmProvider(LlmProvider delegate, String modelKey,
                              CircuitBreakerRegistry registry) {
        this.delegate = delegate;
        this.modelKey = modelKey;
        this.registry = registry;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Flux<ModelEvent> stream(ChatTurn turn) {
        if (!registry.tryAcquire(modelKey)) {
            recordRejection();
            return Flux.error(circuitOpen());
        }
        metrics().ifPresent(m -> m.increment("llm_calls"));
        long[] start = {0};
        return delegate.stream(turn)
                .doOnSubscribe(s -> start[0] = System.currentTimeMillis())
                .doOnComplete(() -> recordOk(start[0]))
                .doOnError(e -> recordFail(start[0]));
    }

    @Override
    public Mono<String> complete(ChatTurn turn) {
        if (!registry.tryAcquire(modelKey)) {
            recordRejection();
            return Mono.error(circuitOpen());
        }
        metrics().ifPresent(m -> m.increment("llm_calls"));
        long[] start = {0};
        return delegate.complete(turn)
                .doOnSubscribe(s -> start[0] = System.currentTimeMillis())
                .doOnSuccess(v -> recordOk(start[0]))
                .doOnError(e -> recordFail(start[0]));
    }

    @Override
    public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
        if (!registry.tryAcquire(modelKey)) {
            recordRejection();
            return Mono.error(circuitOpen());
        }
        metrics().ifPresent(m -> m.increment("llm_calls"));
        long[] start = {0};
        return delegate.completeWithTools(turn, tools)
                .doOnSubscribe(s -> start[0] = System.currentTimeMillis())
                .doOnSuccess(v -> recordOk(start[0]))
                .doOnError(e -> recordFail(start[0]));
    }

    private void recordOk(long startNanos) {
        registry.recordSuccess(modelKey);
        metrics().ifPresent(m -> {
            m.increment("llm_successes");
            m.record("llm_latency_ms", (System.nanoTime() - startNanos) / 1_000_000);
        });
    }

    private void recordFail(long startNanos) {
        registry.recordFailure(modelKey);
        metrics().ifPresent(m -> {
            m.increment("llm_failures");
            m.record("llm_latency_ms", (System.nanoTime() - startNanos) / 1_000_000);
        });
    }

    private void recordRejection() {
        metrics().ifPresent(m -> m.increment("llm_rejections"));
    }

    private Optional<MetricsRegistry> metrics() {
        return Optional.ofNullable(registry.metrics());
    }

    private static LlmProviderException circuitOpen() {
        return new LlmProviderException(
                "circuit breaker open, request rejected before calling provider");
    }
}
