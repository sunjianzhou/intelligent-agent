package com.intelligent.agent.web.im;

/**
 * 令牌桶限流器（线程安全，与 Python TokenBucket 语义一致）。
 */
public class TokenBucket {

    private final double rate;
    private final double capacity;
    private double tokens;
    private long lastRefill;
    private long rejectedCount;

    /**
     * @param rate  每秒填充令牌数
     * @param burst 突发容量（桶容量 = rate + burst）
     */
    public TokenBucket(double rate, int burst) {
        if (rate < 0) {
            throw new IllegalArgumentException("rate must be >= 0");
        }
        this.rate = rate;
        this.capacity = rate + burst;
        this.tokens = capacity;
        this.lastRefill = System.nanoTime();
    }

    /** 尝试获取一个令牌。返回 true = 放行。 */
    public synchronized boolean acquire() {
        if (rate <= 0) {
            return true;  // 不限流
        }
        long now = System.nanoTime();
        double elapsed = (now - lastRefill) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsed * rate);
        lastRefill = now;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        rejectedCount++;
        return false;
    }

    public long rejectedCount() { return rejectedCount; }
}
