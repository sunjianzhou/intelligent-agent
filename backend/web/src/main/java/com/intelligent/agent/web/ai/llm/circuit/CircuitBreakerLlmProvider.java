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
            return Flux.error(circuitOpen());
        }
        return delegate.stream(turn)
                .doOnComplete(() -> registry.recordSuccess(modelKey))
                .doOnError(e -> registry.recordFailure(modelKey));
    }

    @Override
    public Mono<String> complete(ChatTurn turn) {
        if (!registry.tryAcquire(modelKey)) {
            return Mono.error(circuitOpen());
        }
        return delegate.complete(turn)
                .doOnSuccess(v -> registry.recordSuccess(modelKey))
                .doOnError(e -> registry.recordFailure(modelKey));
    }

    @Override
    public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
        if (!registry.tryAcquire(modelKey)) {
            return Mono.error(circuitOpen());
        }
        return delegate.completeWithTools(turn, tools)
                .doOnSuccess(v -> registry.recordSuccess(modelKey))
                .doOnError(e -> registry.recordFailure(modelKey));
    }

    private static LlmProviderException circuitOpen() {
        return new LlmProviderException(
                "circuit breaker open, request rejected before calling provider");
    }
}
