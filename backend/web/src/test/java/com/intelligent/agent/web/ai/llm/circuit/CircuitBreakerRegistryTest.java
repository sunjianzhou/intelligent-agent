package com.intelligent.agent.web.ai.llm.circuit;

import com.intelligent.agent.web.ai.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 注册表：按模型缓存装饰器；disabled 时直接返回原始 provider；status() 输出全部模型快照。 */
class CircuitBreakerRegistryTest {

    private final LlmProvider delegate = mock(LlmProvider.class);

    private static CircuitBreakerRegistry registry(boolean enabled) {
        return new CircuitBreakerRegistry(new CircuitBreakerConfig(
                enabled, 3, Duration.ofSeconds(30), 100));
    }

    @Test
    void wrapReturnsSameWrapperPerModelKey() {
        CircuitBreakerRegistry registry = registry(true);

        LlmProvider m1 = registry.wrap("qwen2.5:7b", delegate);
        assertThat(registry.wrap("qwen2.5:7b", delegate)).isSameAs(m1);
        assertThat(registry.wrap("deepseek-chat", delegate)).isNotSameAs(m1);
    }

    @Test
    void disabledRegistryReturnsDelegateUnwrapped() {
        CircuitBreakerRegistry registry = registry(false);

        assertThat(registry.wrap("qwen2.5:7b", delegate)).isSameAs(delegate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusContainsPerModelSnapshots() {
        CircuitBreakerRegistry registry = registry(true);
        registry.wrap("qwen2.5:7b", delegate);
        registry.wrap("deepseek-chat", delegate);

        Map<String, Object> status = registry.status();
        assertThat(status.get("enabled")).isEqualTo(true);
        assertThat(status.get("window_size")).isEqualTo(100);
        List<Map<String, Object>> breakers = (List<Map<String, Object>>) status.get("breakers");
        assertThat(breakers).hasSize(2);
        assertThat(breakers).extracting(b -> b.get("model"))
                .containsExactlyInAnyOrder("qwen2.5:7b", "deepseek-chat");
        assertThat(breakers).allSatisfy(b -> {
            assertThat(b.get("state")).isEqualTo("CLOSED");
            assertThat(b.get("success_rate")).isEqualTo(0.0);
        });
    }
}
