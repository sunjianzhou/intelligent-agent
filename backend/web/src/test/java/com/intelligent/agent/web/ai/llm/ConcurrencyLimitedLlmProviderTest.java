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
        awaitActiveZero(gate);
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
        awaitActiveZero(gate);
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

        awaitActiveZero(gate);
    }

    @Test
    void completeFailsWhenQueueTimesOut() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire();
        ConcurrencyLimitedLlmProvider provider = new ConcurrencyLimitedLlmProvider(
                new CountingProvider(), gate, Duration.ofMillis(100));

        StepVerifier.create(provider.complete(ChatTurn.of("fake", List.of())))
                .expectErrorMatches(e -> e instanceof LlmProviderException
                        && e.getMessage() != null
                        && e.getMessage().contains("推理队列繁忙"))
                .verify(Duration.ofSeconds(5));

        gate.release();
        assertThat(gate.active()).isZero();
    }

    @Test
    void completeAcquiresSlotWithinTimeoutWhenCapacityFrees() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire();
        CountingProvider delegate = new CountingProvider();
        ConcurrencyLimitedLlmProvider provider = new ConcurrencyLimitedLlmProvider(
                delegate, gate, Duration.ofSeconds(2));
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.release();
        });
        releaser.start();

        String result = provider.complete(ChatTurn.of("fake", List.of()))
                .block(Duration.ofSeconds(5));

        assertThat(result).isEqualTo("pong");
        releaser.join(2000);
        awaitActiveZero(gate);
        assertThat(delegate.calls).hasValue(1);
    }

    /** doFinally 的释放发生在 boundedElastic 线程，主线程需有界等待，避免时序竞态。 */
    private static void awaitActiveZero(InferenceGate gate) {
        long deadline = System.currentTimeMillis() + 5000;
        while (gate.active() != 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
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
