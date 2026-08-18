package com.intelligent.agent.web.ai.llm;

/**
 * Global gate limiting concurrent LLM inference (wires runtime inference_concurrency).
 * Semantics match the runtime config description: requests beyond the limit queue up
 * and wait for a slot. The limit can be adjusted at runtime.
 */
public class InferenceGate {

    private final Object lock = new Object();
    private volatile int maxConcurrency;
    private int active;

    public InferenceGate() {
        this(1);
    }

    public InferenceGate(int maxConcurrency) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /** Acquire one inference slot; blocks while at capacity until a slot is released. */
    public void acquire() throws InterruptedException {
        synchronized (lock) {
            while (active >= maxConcurrency) {
                lock.wait();
            }
            active++;
        }
    }

    /** Release one inference slot and wake waiters. */
    public void release() {
        synchronized (lock) {
            if (active > 0) {
                active--;
            }
            lock.notifyAll();
        }
    }

    public int active() {
        synchronized (lock) {
            return active;
        }
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int max) {
        synchronized (lock) {
            this.maxConcurrency = Math.max(1, max);
            lock.notifyAll();
        }
    }
}
