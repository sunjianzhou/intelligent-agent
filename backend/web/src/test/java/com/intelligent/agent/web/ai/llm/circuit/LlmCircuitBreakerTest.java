package com.intelligent.agent.web.ai.llm.circuit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 熔断器状态机 + SLO 滚动窗口：CLOSED → OPEN（连续失败达阈值）→ HALF_OPEN（冷却后单次试探）。 */
class LlmCircuitBreakerTest {

    private static LlmCircuitBreaker breaker(int threshold, Duration cooldown, int windowSize) {
        return new LlmCircuitBreaker(threshold, cooldown, windowSize);
    }

    private static LlmCircuitBreaker breaker(int threshold, Duration cooldown,
                                             int windowSize, LongSupplier nowMillis) {
        return new LlmCircuitBreaker(threshold, cooldown, windowSize, nowMillis);
    }

    @Test
    void allowsCallsWhileClosedAndTracksSuccessRate() {
        LlmCircuitBreaker b = breaker(5, Duration.ofSeconds(30), 100);

        for (int i = 0; i < 4; i++) {
            assertThat(b.tryAcquire()).isTrue();
            b.recordSuccess();
        }

        LlmCircuitBreaker.Snapshot s = b.snapshot();
        assertThat(s.state()).isEqualTo("CLOSED");
        assertThat(s.successRate()).isEqualTo(1.0);
        assertThat(s.windowSize()).isEqualTo(4);
    }

    @Test
    void opensAfterConsecutiveFailureThresholdAndRejects() {
        LlmCircuitBreaker b = breaker(3, Duration.ofSeconds(30), 100);

        b.recordFailure();
        b.recordFailure();
        b.recordFailure();

        assertThat(b.snapshot().state()).isEqualTo("OPEN");
        assertThat(b.tryAcquire()).isFalse();
        assertThat(b.snapshot().rejections()).isEqualTo(1);
        assertThat(b.snapshot().consecutiveFailures()).isEqualTo(3);
        assertThat(b.snapshot().successRate()).isZero();
    }

    @Test
    void successResetsConsecutiveFailuresBeforeThreshold() {
        LlmCircuitBreaker b = breaker(3, Duration.ofSeconds(30), 100);

        b.recordFailure();
        b.recordFailure();
        b.recordSuccess();
        b.recordFailure();
        b.recordFailure();

        assertThat(b.snapshot().state()).isEqualTo("CLOSED");
        assertThat(b.snapshot().consecutiveFailures()).isEqualTo(2);
    }

    @Test
    void openTransitionsToHalfOpenAfterCooldownAndTrialSuccessCloses() {
        AtomicLong now = new AtomicLong(1_000_000);
        LlmCircuitBreaker b = breaker(1, Duration.ofSeconds(30), 100, now::get);

        b.recordFailure();
        assertThat(b.snapshot().state()).isEqualTo("OPEN");
        assertThat(b.tryAcquire()).isFalse();

        now.set(1_000_000 + 30_000);
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.snapshot().state()).isEqualTo("HALF_OPEN");

        b.recordSuccess();
        assertThat(b.snapshot().state()).isEqualTo("CLOSED");
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void halfOpenTrialFailureReopens() {
        AtomicLong now = new AtomicLong(1_000_000);
        LlmCircuitBreaker b = breaker(1, Duration.ofSeconds(30), 100, now::get);

        b.recordFailure();
        now.set(1_000_000 + 30_000);
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.snapshot().state()).isEqualTo("HALF_OPEN");

        b.recordFailure();

        assertThat(b.snapshot().state()).isEqualTo("OPEN");
    }

    @Test
    void halfOpenAllowsOnlyOneTrial() {
        AtomicLong now = new AtomicLong(1_000_000);
        LlmCircuitBreaker b = breaker(1, Duration.ofSeconds(30), 100, now::get);

        b.recordFailure();
        now.set(1_000_000 + 30_000);
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isFalse();
        assertThat(b.snapshot().rejections()).isEqualTo(1);
    }

    @Test
    void snapshotTracksRollingWindowSuccessRate() {
        LlmCircuitBreaker b = breaker(5, Duration.ofSeconds(30), 2);

        b.recordSuccess();
        b.recordFailure();
        assertThat(b.snapshot().windowSize()).isEqualTo(2);
        assertThat(b.snapshot().failures()).isEqualTo(1);
        assertThat(b.snapshot().successRate()).isEqualTo(0.5);

        b.recordFailure();
        assertThat(b.snapshot().successRate()).isZero();
        b.recordSuccess();
        assertThat(b.snapshot().successRate()).isEqualTo(0.5);
    }
}
