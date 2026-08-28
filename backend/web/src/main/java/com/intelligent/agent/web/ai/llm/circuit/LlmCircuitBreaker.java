package com.intelligent.agent.web.ai.llm.circuit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * 按模型的 LLM 熔断器（G6）：CLOSED 正常放行 → 连续失败达阈值 OPEN（快速失败）→
 * 冷却期过后 HALF_OPEN 放行单次试探，成功回 CLOSED、失败回 OPEN。
 * SLO 通过滚动窗口统计近期成功率（success_rate）并随快照暴露。
 */
public class LlmCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Snapshot(String state, double successRate, int windowSize,
                           int failures, long rejections, int consecutiveFailures,
                           long lastOpenedAt) {
    }

    private final int failureThreshold;
    private final long cooldownMillis;
    private final int windowSize;
    private final LongSupplier nowMillis;
    private final Runnable onOpened;

    private State state = State.CLOSED;
    private final Deque<Boolean> window = new ArrayDeque<>();
    private long rejections;
    private int consecutiveFailures;
    private long lastOpenedAt;
    private boolean trialInProgress;

    public LlmCircuitBreaker(int failureThreshold, Duration cooldown, int windowSize) {
        this(failureThreshold, cooldown, windowSize, System::currentTimeMillis);
    }

    LlmCircuitBreaker(int failureThreshold, Duration cooldown, int windowSize,
                      LongSupplier nowMillis) {
        this(failureThreshold, cooldown, windowSize, nowMillis, null);
    }

    /** R-13：{@code onOpened} 在熔断器首次进入 OPEN（CLOSED→OPEN 或 HALF_OPEN→OPEN）时回调一次。 */
    LlmCircuitBreaker(int failureThreshold, Duration cooldown, int windowSize,
                      LongSupplier nowMillis, Runnable onOpened) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(0, cooldown == null
                ? Duration.ofSeconds(30).toMillis() : cooldown.toMillis());
        this.windowSize = Math.max(1, windowSize);
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
        this.onOpened = onOpened;
    }

    /** 放行前检查：OPEN 且未过冷却期、HALF_OPEN 已有试探在飞时拒绝（返回 false）。 */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (nowMillis.getAsLong() - lastOpenedAt >= cooldownMillis) {
                    state = State.HALF_OPEN;
                    trialInProgress = true;
                    return true;
                }
                rejections++;
                return false;
            case HALF_OPEN:
                if (trialInProgress) {
                    rejections++;
                    return false;
                }
                trialInProgress = true;
                return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        window.addLast(true);
        trim();
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            trialInProgress = false;
        }
    }

    public synchronized void recordFailure() {
        window.addLast(false);
        trim();
        consecutiveFailures++;
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            trialInProgress = false;
            lastOpenedAt = nowMillis.getAsLong();
            fireOpened();
        } else if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            lastOpenedAt = nowMillis.getAsLong();
            fireOpened();
        }
    }

    private void fireOpened() {
        if (onOpened != null) {
            try {
                onOpened.run();
            } catch (RuntimeException ignored) {
                // 告警回调失败不影响熔断状态机
            }
        }
    }

    private void trim() {
        while (window.size() > windowSize) {
            window.removeFirst();
        }
    }

    public synchronized Snapshot snapshot() {
        int failures = 0;
        for (boolean ok : window) {
            if (!ok) {
                failures++;
            }
        }
        double rate = window.isEmpty()
                ? 0.0 : (double) (window.size() - failures) / window.size();
        return new Snapshot(state.name(), rate, window.size(), failures,
                rejections, consecutiveFailures, lastOpenedAt);
    }
}
