package com.intelligent.agent.web.ai.llm.circuit;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 装饰器：成功/失败计数；熔断打开时快速失败且不再调用底层 provider（流式与非流式一致）。 */
class CircuitBreakerLlmProviderTest {

    static class CountingProvider implements LlmProvider {
        int calls;
        boolean fail;
        final Flux<ModelEvent> streamEvents =
                Flux.just(ModelEvent.token("ok"), ModelEvent.done(Map.of()));

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            calls++;
            return fail ? Flux.error(new LlmProviderException("boom")) : streamEvents;
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            calls++;
            return fail ? Mono.error(new LlmProviderException("boom")) : Mono.just("ok");
        }
    }

    private static final ChatTurn TURN =
            new ChatTurn("u1", "qwen2.5:7b", List.of(ChatMessage.user("hi")), Map.of());

    private static CircuitBreakerRegistry registry(int threshold) {
        return new CircuitBreakerRegistry(new CircuitBreakerConfig(
                true, threshold, Duration.ofSeconds(30), 100));
    }

    @Test
    void completeSuccessRecordsSuccessAndReturnsValue() {
        CountingProvider delegate = new CountingProvider();
        LlmProvider wrapper = registry(3).wrap("qwen2.5:7b", delegate);

        assertThat(wrapper.complete(TURN).block()).isEqualTo("ok");
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void completeFailuresOpenBreakerThenFastFailWithoutCallingDelegate() {
        CountingProvider delegate = new CountingProvider();
        LlmProvider wrapper = registry(2).wrap("qwen2.5:7b", delegate);
        delegate.fail = true;

        StepVerifier.create(wrapper.complete(TURN)).expectError().verify();
        StepVerifier.create(wrapper.complete(TURN)).expectError().verify();

        StepVerifier.create(wrapper.complete(TURN))
                .expectErrorSatisfies(t ->
                        org.assertj.core.api.Assertions.assertThat(t.getMessage())
                                .contains("circuit breaker"))
                .verify();
        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void streamFailureCountsAndOpenRejectsStream() {
        CountingProvider delegate = new CountingProvider();
        LlmProvider wrapper = registry(1).wrap("qwen2.5:7b", delegate);
        delegate.fail = true;

        StepVerifier.create(wrapper.stream(TURN)).expectError().verify();

        StepVerifier.create(wrapper.stream(TURN))
                .expectErrorSatisfies(t ->
                        org.assertj.core.api.Assertions.assertThat(t.getMessage())
                                .contains("circuit breaker"))
                .verify();
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void streamSuccessClosesHalfOpenTrial() {
        CountingProvider delegate = new CountingProvider();
        CircuitBreakerRegistry zeroCooldown = new CircuitBreakerRegistry(
                new CircuitBreakerConfig(true, 1, Duration.ZERO, 100));
        LlmProvider wrapper = zeroCooldown.wrap("qwen2.5:7b", delegate);
        delegate.fail = true;

        StepVerifier.create(wrapper.complete(TURN)).expectError().verify();

        delegate.fail = false;
        StepVerifier.create(wrapper.complete(TURN))
                .expectNext("ok")
                .verifyComplete();
        StepVerifier.create(wrapper.complete(TURN))
                .expectNext("ok")
                .verifyComplete();
        assertThat(delegate.calls).isEqualTo(3);
    }
}
