package com.intelligent.agent.web.ai.llm;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 推理并发闸门（runtime inference_concurrency 驱动）：超过上限的请求排队等待。
 * <p>
 * 支持按 key（模型名）独立计数：显式指定模型时各模型拥有自己的并发额度，
 * 默认（空 key）走公共槽位；{@code setMaxConcurrency} 对全部 key 生效。
 * 排队可限时（{@link #acquire(String, Duration)}），超时返回 false，避免无限期占用等待线程。
 */
public class InferenceGate {

    private final Object lock = new Object();
    private final Map<String, Integer> activeByKey = new HashMap<>();
    private final Consumer<String> onQueueTimeout;
    private volatile int maxConcurrency;

    public InferenceGate() {
        this(1);
    }

    public InferenceGate(int maxConcurrency) {
        this(maxConcurrency, null);
    }

    /** R-13：{@code onQueueTimeout} 在限时获取超时（推理队列满）时回调一次。 */
    public InferenceGate(int maxConcurrency, Consumer<String> onQueueTimeout) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.onQueueTimeout = onQueueTimeout;
    }

    /** 默认槽位（空 key）无限期获取。 */
    public void acquire() throws InterruptedException {
        acquire("");
    }

    /** 指定 key 无限期获取。 */
    public void acquire(String key) throws InterruptedException {
        acquire(key, null);
    }

    /** 默认槽位限时获取：超时返回 false。 */
    public boolean acquire(Duration timeout) throws InterruptedException {
        return acquire("", timeout);
    }

    /** 指定 key 限时获取：超过 timeout 仍未拿到返回 false（调用方转成"推理繁忙"错误）。 */
    public boolean acquire(String key, Duration timeout) throws InterruptedException {
        String k = key == null ? "" : key;
        boolean timed = timeout != null && !timeout.isZero() && !timeout.isNegative();
        long deadline = timed ? System.nanoTime() + timeout.toNanos() : 0;
        boolean timedOut = false;
        synchronized (lock) {
            while (activeByKey.getOrDefault(k, 0) >= maxConcurrency) {
                if (!timed) {
                    lock.wait();
                    continue;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    timedOut = true;
                    break;
                }
                lock.wait(remainingNanos / 1_000_000L,
                        (int) (remainingNanos % 1_000_000L));
            }
            if (!timedOut) {
                activeByKey.merge(k, 1, Integer::sum);
            }
        }
        if (timedOut) {
            fireQueueTimeout(k);
            return false;
        }
        return true;
    }

    private void fireQueueTimeout(String key) {
        if (onQueueTimeout != null) {
            try {
                onQueueTimeout.accept(key);
            } catch (RuntimeException ignored) {
                // 告警回调失败不影响闸门语义
            }
        }
    }

    /** 释放默认槽位。 */
    public void release() {
        release("");
    }

    /** 释放指定 key 的槽位。 */
    public void release(String key) {
        String k = key == null ? "" : key;
        synchronized (lock) {
            Integer current = activeByKey.get(k);
            if (current != null && current > 0) {
                if (current == 1) {
                    activeByKey.remove(k);
                } else {
                    activeByKey.put(k, current - 1);
                }
                lock.notifyAll();
            }
        }
    }

    /** 全部 key 的活跃推理数之和。 */
    public int active() {
        synchronized (lock) {
            return activeByKey.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    /** 调整并发上限：对默认与全部已存在 key 立即生效。 */
    public void setMaxConcurrency(int max) {
        synchronized (lock) {
            this.maxConcurrency = Math.max(1, max);
            lock.notifyAll();
        }
    }
}
