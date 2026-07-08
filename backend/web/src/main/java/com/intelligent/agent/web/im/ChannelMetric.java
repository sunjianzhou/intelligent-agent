package com.intelligent.agent.web.im;

/**
 * 单 channel 发送指标（可观测性，与 Python ChannelMetric 语义一致）。
 */
public class ChannelMetric {

    private final ChannelType channel;
    private long totalAttempts;
    private long totalSuccesses;
    private long totalFailures;
    private long totalRetries;
    private long rateLimitHits;
    private double totalLatencyMs;

    public ChannelMetric(ChannelType channel) {
        this.channel = channel;
    }

    public synchronized void recordAttempt() { totalAttempts++; }
    public synchronized void recordSuccess(double latencyMs) {
        totalSuccesses++;
        totalLatencyMs += latencyMs;
    }
    public synchronized void recordFailure() { totalFailures++; }
    public synchronized void recordRetry() { totalRetries++; }
    public synchronized void recordRateLimitHit() { rateLimitHits++; }

    public double successRate() {
        if (totalAttempts == 0) return 1.0;
        return (double) totalSuccesses / totalAttempts;
    }

    public double avgLatencyMs() {
        if (totalAttempts == 0) return 0.0;
        return totalLatencyMs / totalAttempts;
    }

    // ── getters ──────────────────────────────────────────
    public ChannelType channel() { return channel; }
    public long totalAttempts() { return totalAttempts; }
    public long totalSuccesses() { return totalSuccesses; }
    public long totalFailures() { return totalFailures; }
    public long totalRetries() { return totalRetries; }
    public long rateLimitHits() { return rateLimitHits; }
    public double totalLatencyMs() { return totalLatencyMs; }
}
