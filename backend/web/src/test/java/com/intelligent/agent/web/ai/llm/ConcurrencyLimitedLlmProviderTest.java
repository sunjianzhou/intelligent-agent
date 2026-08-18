package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ConcurrencyLimitedLlmProvider：complete/stream 期间持有槽位，结束后释放。 */
class ConcurrencyLimitedLlmProviderTest {

    @Test
    void completeAcquiresSlotAndReleasesAfterCompletion() {
        InferenceGate gate = new InferenceGate(1);
        CountingProvider delegate = new CountingProvider();
        ConcurrencyLimitedLlmProvider provider =
                new ConcurrencyLimitedLlmProvider(delegate, gate);

        String result = provider.complete(ChatTurn.of("fake", List.of())).block(Duration.ofSeconds(5));

        assertThat(result).isEqualTo("pong");
        assertThat(gate.active()).isZero();
        assertThat(delegate.calls).hasValue(1);
    }

    @Test
    void streamHoldsSlotWhileStreaming() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        ConcurrencyLimitedLlmProvider provider = new ConcurrencyLimitedLlmProvider(
                new LlmProvider() {
                    @Override
                    public String name() {
                        return "fake";
                    }

                    @Override
                    public Flux<ModelEvent> stream(ChatTurn turn) {
                        return Flux.just(new ModelEvent("content", "hello"))
                                .delayElements(Duration.ofMillis(100));
                    }

                    @Override
                    public Mono<String> complete(ChatTurn turn) {
                        return Mono.just("ok");
                    }
                }, gate);

        CountDownLatch during = new CountDownLatch(1);
        provider.stream(ChatTurn.of("fake", List.of()))
                .doOnNext(e -> {
                    if (gate.active() == 1) {
                        during.countDown();
                    }
                })
                .blockLast(Duration.ofSeconds(5));

        assertThat(during.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.active()).isZero();
    }

    @Test
    void completeWithToolsReleasesOnError() {
        InferenceGate gate = new InferenceGate(1);
        ConcurrencyLimitedLlmProvider provider = new ConcurrencyLimitedLlmProvider(
                new LlmProvider() {
                    @Override
                    public String name() {
                        return "fake";
                    }

                    @Override
                    public Flux<ModelEvent> stream(ChatTurn turn) {
                        return Flux.error(new RuntimeException("boom"));
                    }

                    @Override
                    public Mono<String> complete(ChatTurn turn) {
                        return Mono.error(new RuntimeException("boom"));
                    }
                }, gate);

        StepVerifier.create(provider.completeWithTools(ChatTurn.of("fake", List.of()),
                        List.of(new ToolDefinition("t", "desc", false, null, null))))
                .expectError(RuntimeException.class)
                .verify(Duration.ofSeconds(5));

        assertThat(gate.active()).isZero();
    }

    private static final class CountingProvider implements LlmProvider {
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.just(new ModelEvent("content", "ok"));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            calls.incrementAndGet();
            return Mono.just("pong");
        }
    }
}
