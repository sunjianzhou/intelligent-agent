package com.intelligent.agent.web.im;

/**
 * 指数退避重试配置（与 Python RetryConfig 语义一致）。
 */
public class RetryConfig {

    public static final RetryConfig DEFAULT =
            new RetryConfig(3, 1.0, 30.0, 2.0);

    private final int maxRetries;
    private final double baseDelaySec;
    private final double maxDelaySec;
    private final double backoffMultiplier;

    public RetryConfig(int maxRetries, double baseDelaySec,
                       double maxDelaySec, double backoffMultiplier) {
        this.maxRetries = maxRetries;
        this.baseDelaySec = baseDelaySec;
        this.maxDelaySec = maxDelaySec;
        this.backoffMultiplier = backoffMultiplier;
    }

    /** 计算第 attempt 次重试的延迟（1-indexed） */
    public double delayForAttempt(int attempt) {
        double delay = baseDelaySec * Math.pow(backoffMultiplier, attempt - 1);
        return Math.min(delay, maxDelaySec);
    }

    public int maxRetries() { return maxRetries; }
    public double baseDelaySec() { return baseDelaySec; }
    public double maxDelaySec() { return maxDelaySec; }
    public double backoffMultiplier() { return backoffMultiplier; }
}
