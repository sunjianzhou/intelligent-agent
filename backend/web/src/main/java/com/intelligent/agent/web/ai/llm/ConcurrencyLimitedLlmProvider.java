package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.tool.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

/**
 * Decorator that gates LLM calls behind a shared {@link InferenceGate}
 * (wires runtime inference_concurrency). The slot is held for the whole stream
 * and released on complete / error / cancel; excess requests queue instead of
 * being rejected.
 */
public class ConcurrencyLimitedLlmProvider implements LlmProvider {

    public static final Duration DEFAULT_QUEUE_TIMEOUT = Duration.ofSeconds(120);

    private final LlmProvider delegate;
    private final InferenceGate gate;
    private final Duration queueTimeout;
    private final String gateKey;

    ConcurrencyLimitedLlmProvider(LlmProvider delegate, InferenceGate gate) {
        this(delegate, gate, DEFAULT_QUEUE_TIMEOUT);
    }

    ConcurrencyLimitedLlmProvider(LlmProvider delegate, InferenceGate gate,
                                  Duration queueTimeout) {
        this(delegate, gate, queueTimeout, "");
    }

    ConcurrencyLimitedLlmProvider(LlmProvider delegate, InferenceGate gate,
                                  Duration queueTimeout, String gateKey) {
        this.delegate = delegate;
        this.gate = gate;
        this.queueTimeout = queueTimeout == null ? DEFAULT_QUEUE_TIMEOUT : queueTimeout;
        this.gateKey = gateKey == null ? "" : gateKey;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Flux<ModelEvent> stream(ChatTurn turn) {
        AtomicBoolean acquired = new AtomicBoolean();
        return Mono.fromCallable(() -> {
                    if (!gate.acquire(gateKey, queueTimeout)) {
                        throw new LlmProviderException(
                                "推理队列繁忙，排队超过 " + queueTimeout.toSeconds() + "s，请稍后再试");
                    }
                    acquired.set(true);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ignored -> delegate.stream(turn))
                .doFinally(signal -> {
                    if (acquired.get()) {
                        gate.release(gateKey);
                    }
                });
    }

    @Override
    public Mono<String> complete(ChatTurn turn) {
        AtomicBoolean acquired = new AtomicBoolean();
        return Mono.fromCallable(() -> {
                    if (!gate.acquire(gateKey, queueTimeout)) {
                        throw new LlmProviderException(
                                "推理队列繁忙，排队超过 " + queueTimeout.toSeconds() + "s，请稍后再试");
                    }
                    acquired.set(true);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ignored -> delegate.complete(turn))
                .doFinally(signal -> {
                    if (acquired.get()) {
                        gate.release(gateKey);
                    }
                });
    }

    @Override
    public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
        AtomicBoolean acquired = new AtomicBoolean();
        return Mono.fromCallable(() -> {
                    if (!gate.acquire(gateKey, queueTimeout)) {
                        throw new LlmProviderException(
                                "推理队列繁忙，排队超过 " + queueTimeout.toSeconds() + "s，请稍后再试");
                    }
                    acquired.set(true);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ignored -> delegate.completeWithTools(turn, tools))
                .doFinally(signal -> {
                    if (acquired.get()) {
                        gate.release(gateKey);
                    }
                });
    }
}
