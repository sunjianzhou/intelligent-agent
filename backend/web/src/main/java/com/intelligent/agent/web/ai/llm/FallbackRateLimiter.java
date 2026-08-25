package com.intelligent.agent.web.ai.llm;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R-02 fallback 降级次数日限流：防止本地模型故障时云端 fallback 成本失控。
 * 按自然日重置；熔断 OPEN 直切不消耗额度，仅超时 / 5xx / 429 消耗。
 */
public class FallbackRateLimiter {

    public static final int DEFAULT_DAILY_LIMIT = 50;

    private final int dailyLimit;
    private final AtomicInteger used = new AtomicInteger();
    private volatile LocalDate day = LocalDate.now();

    public FallbackRateLimiter(int dailyLimit) {
        this.dailyLimit = dailyLimit > 0 ? dailyLimit : DEFAULT_DAILY_LIMIT;
    }

    /** 消耗一次降级额度；跨日自动重置。返回 true 表示允许本次降级。 */
    public synchronized boolean tryAcquire() {
        LocalDate today = LocalDate.now();
        if (!day.equals(today)) {
            day = today;
            used.set(0);
        }
        return used.incrementAndGet() <= dailyLimit;
    }

    /** 今日剩余额度（测试 / 状态展示用）。 */
    public synchronized int remainingToday() {
        LocalDate today = LocalDate.now();
        if (!day.equals(today)) {
            day = today;
            used.set(0);
        }
        return Math.max(0, dailyLimit - used.get());
    }

    public int dailyLimit() {
        return dailyLimit;
    }
}
