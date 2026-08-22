package com.intelligent.agent.web.ai.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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
                afterAcquire.set(gate.active());
                entered.countDown();
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

    @Test
    void acquireWithTimeoutFailsWhenCapacityHeld() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire();

        assertThat(gate.acquire(Duration.ofMillis(80))).isFalse();
        assertThat(gate.active()).isEqualTo(1);

        gate.release();
    }

    @Test
    void acquireWithTimeoutSucceedsWhenCapacityFrees() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.release();
        });
        releaser.start();

        assertThat(gate.acquire(Duration.ofSeconds(2))).isTrue();
        assertThat(gate.active()).isEqualTo(1);

        releaser.join(2000);
        gate.release();
        assertThat(gate.active()).isZero();
    }

    @Test
    void differentKeysHaveIndependentSlots() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire("model-a");

        assertThat(gate.acquire("model-b", Duration.ofMillis(200))).isTrue();
        assertThat(gate.active()).isEqualTo(2);

        gate.release("model-a");
        gate.release("model-b");
        assertThat(gate.active()).isZero();
    }

    @Test
    void sameKeyBlocksAtCapacity() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        gate.acquire("model-a");

        assertThat(gate.acquire("model-a", Duration.ofMillis(80))).isFalse();
        assertThat(gate.active()).isEqualTo(1);

        gate.release("model-a");
        assertThat(gate.active()).isZero();
    }
}
