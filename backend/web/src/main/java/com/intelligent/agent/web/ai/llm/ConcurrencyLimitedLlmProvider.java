package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.tool.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decorator that gates LLM calls behind a shared {@link InferenceGate}
 * (wires runtime inference_concurrency). The slot is held for the whole stream
 * and released on complete / error / cancel; excess requests queue instead of
 * being rejected.
 */
public class ConcurrencyLimitedLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final InferenceGate gate;

    ConcurrencyLimitedLlmProvider(LlmProvider delegate, InferenceGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Flux<ModelEvent> stream(ChatTurn turn) {
        AtomicBoolean acquired = new AtomicBoolean();
        return Mono.fromCallable(() -> {
                    gate.acquire();
                    acquired.set(true);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ignored -> delegate.stream(turn))
                .doFinally(signal -> {
                    if (acquired.get()) {
                        gate.release();
                    }
                });
    }

    @Override
    public Mono<String> complete(ChatTurn turn) {
        AtomicBoolean acquired = new AtomicBoolean();
        return Mono.fromCallable(() -> {
                    gate.acquire();
                    acquired.set(true);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ignored -> delegate.complete(turn))
                .doFinally(signal -> {
                    if (acquired.get()) {
                        gate.release();
                    }
                });
    }

    @Override
    public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
        AtomicBoolean acquired = new AtomicBoolean();
        return Mono.fromCallable(() -> {
                    gate.acquire();
                    acquired.set(true);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ignored -> delegate.completeWithTools(turn, tools))
                .doFinally(signal -> {
                    if (acquired.get()) {
                        gate.release();
                    }
                });
    }
}
