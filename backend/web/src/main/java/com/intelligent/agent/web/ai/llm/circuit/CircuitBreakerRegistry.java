package com.intelligent.agent.web.ai.llm.circuit;

import com.intelligent.agent.web.ai.llm.LlmProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按模型名持有熔断器与装饰器缓存；{@code wrap()} 在 enabled 时返回熔断装饰器。 */
public class CircuitBreakerRegistry {

    private final CircuitBreakerConfig config;
    private final Map<String, LlmCircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreakerLlmProvider> wrappers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(CircuitBreakerConfig config) {
        this.config = config == null
                ? new CircuitBreakerConfig(true, 5, Duration.ofSeconds(30), 100)
                : config;
    }

    /** enabled=false 时原样返回 delegate（零开销旁路）。 */
    public LlmProvider wrap(String modelKey, LlmProvider delegate) {
        if (!config.enabled()) {
            return delegate;
        }
        return wrappers.computeIfAbsent(modelKey, k -> {
            breakers.put(k, new LlmCircuitBreaker(
                    config.failureThreshold(), config.cooldown(), config.windowSize()));
            return new CircuitBreakerLlmProvider(delegate, k, this);
        });
    }

    boolean tryAcquire(String modelKey) {
        return breaker(modelKey).tryAcquire();
    }

    void recordSuccess(String modelKey) {
        breaker(modelKey).recordSuccess();
    }

    void recordFailure(String modelKey) {
        breaker(modelKey).recordFailure();
    }

    private LlmCircuitBreaker breaker(String modelKey) {
        return breakers.computeIfAbsent(modelKey, k -> new LlmCircuitBreaker(
                config.failureThreshold(), config.cooldown(), config.windowSize()));
    }

    /** SLO/状态快照：GET /api/llm/status 的载荷来源。 */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.enabled());
        status.put("failure_threshold", config.failureThreshold());
        status.put("cooldown_ms", config.cooldown().toMillis());
        status.put("window_size", config.windowSize());

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, LlmCircuitBreaker> entry : breakers.entrySet()) {
            LlmCircuitBreaker.Snapshot s = entry.getValue().snapshot();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", entry.getKey());
            item.put("state", s.state());
            item.put("success_rate", s.successRate());
            item.put("window_size", s.windowSize());
            item.put("failures", s.failures());
            item.put("rejections", s.rejections());
            item.put("consecutive_failures", s.consecutiveFailures());
            item.put("last_opened_at", s.lastOpenedAt());
            list.add(item);
        }
        status.put("breakers", list);
        return status;
    }
}
