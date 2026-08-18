package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerConfig;
import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/llm/status：返回熔断器注册表状态；registry 缺失时降级 enabled=false。 */
class HealthControllerCircuitBreakerContractTest {

    @Test
    void llmStatusReturnsBreakerStatus() throws Exception {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(new CircuitBreakerConfig(
                true, 3, Duration.ofSeconds(30), 100));
        registry.wrap("qwen2.5:7b", mock(com.intelligent.agent.web.ai.llm.LlmProvider.class));

        HealthController controller = new HealthController();
        ReflectionTestUtils.setField(controller, "circuitBreakerRegistry", registry);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/llm/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.breakers[0].model").value("qwen2.5:7b"))
                .andExpect(jsonPath("$.breakers[0].state").value("CLOSED"));
    }

    @Test
    void llmStatusDegradesWhenRegistryMissing() throws Exception {
        HealthController controller = new HealthController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/llm/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
