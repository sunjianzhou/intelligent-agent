package com.intelligent.agent.web.ai.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** InferenceGate：并发上限排队、释放唤醒、运行期调上限。 */
class InferenceGateTest {

    @Test
    void acquireBlocksBeyondCapacityAndReleaseWakesWaiter() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire();

        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger afterAcquire = new AtomicInteger();
        Thread waiter = new Thread(() -> {
            try {
                gate.acquire();
                entered.countDown();
                afterAcquire.set(gate.active());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        assertThat(entered.await(200, TimeUnit.MILLISECONDS)).isFalse();

        gate.release();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(afterAcquire).hasValue(1);
        waiter.join(2000);
    }

    @Test
    void increasingMaxConcurrencyWakesWaiters() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire();

        CountDownLatch entered = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                gate.acquire();
                entered.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        assertThat(entered.await(200, TimeUnit.MILLISECONDS)).isFalse();

        gate.setMaxConcurrency(2);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.active()).isEqualTo(2);
        waiter.join(2000);
    }

    @Test
    void releaseWithoutAcquireDoesNotGoNegative() {
        InferenceGate gate = new InferenceGate(1);

        gate.release();
        gate.release();

        assertThat(gate.active()).isZero();
        assertThat(gate.maxConcurrency()).isEqualTo(1);
    }

    @Test
    void maxConcurrencyIsAtLeastOne() {
        assertThat(new InferenceGate(0).maxConcurrency()).isEqualTo(1);
        assertThat(new InferenceGate(-3).maxConcurrency()).isEqualTo(1);

        InferenceGate gate = new InferenceGate(1);
        gate.setMaxConcurrency(0);
        assertThat(gate.maxConcurrency()).isEqualTo(1);
    }
}
