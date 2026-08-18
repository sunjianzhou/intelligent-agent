package com.intelligent.agent.web.ai.llm.circuit;

import java.time.Duration;

/** 熔断器全局配置（ai.llm.circuit-breaker.*）。 */
public record CircuitBreakerConfig(boolean enabled, int failureThreshold,
                                   Duration cooldown, int windowSize) {

    public CircuitBreakerConfig {
        failureThreshold = Math.max(1, failureThreshold);
        cooldown = cooldown == null ? Duration.ofSeconds(30) : cooldown;
        windowSize = Math.max(1, windowSize);
    }
}
