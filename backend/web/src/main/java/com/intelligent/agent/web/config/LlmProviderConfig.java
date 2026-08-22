package com.intelligent.agent.web.config;

import com.intelligent.agent.web.ai.llm.InferenceGate;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.OllamaOptions;
import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerConfig;
import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerRegistry;
import com.intelligent.agent.web.ai.llm.cloud.OpenAiCompatibleLlmProvider;
import com.intelligent.agent.web.ai.llm.ollama.OllamaLlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM provider 与路由器的 Spring 装配。
 * 云端 provider 始终注册，但未配置齐全时 isConfigured()=false，不会参与路由。
 */
@Configuration
public class LlmProviderConfig {

    @Bean
    public OllamaLlmProvider ollamaLlmProvider(
            @Value("${ai.llm.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ai.llm.ollama.model:qwen2.5:7b}") String model,
            @Value("${ai.llm.ollama.temperature:0.7}") double temperature,
            @Value("${ai.llm.ollama.max-tokens:2048}") int maxTokens,
            @Value("${ai.llm.ollama.top-p:0.9}") double topP,
            @Value("${ai.llm.ollama.top-k:40}") int topK,
            @Value("${ai.llm.ollama.repeat-penalty:1.1}") double repeatPenalty,
            @Value("${ai.llm.ollama.num-ctx:4096}") int numCtx,
            @Value("${ai.llm.ollama.num-gpu:-1}") int numGpu,
            @Value("${ai.llm.ollama.keep-alive:-1}") String keepAlive,
            @Value("${ai.llm.ollama.cache-prompt:true}") boolean cachePrompt,
            @Value("#{${ai.llm.ollama.num-ctx-by-model:{}}}") Map<String, Integer> numCtxByModel,
            @Value("${ai.llm.ollama.timeout:600s}") Duration timeout) {
        return new OllamaLlmProvider(baseUrl, model,
                new OllamaOptions(temperature, maxTokens, topP, topK,
                        repeatPenalty, numCtx, numGpu, keepAlive),
                timeout, cachePrompt, numCtxByModel);
    }

    @Bean
    public OpenAiCompatibleLlmProvider cloudLlmProvider(
            @Value("${ai.llm.cloud.api-key:}") String apiKey,
            @Value("${ai.llm.cloud.base-url:}") String baseUrl,
            @Value("${ai.llm.cloud.model:}") String model,
            @Value("${ai.llm.cloud.timeout:120s}") Duration timeout) {
        return new OpenAiCompatibleLlmProvider(baseUrl, apiKey, model, timeout);
    }

    @Bean
    public LlmProviderRouter llmProviderRouter(
            OllamaLlmProvider ollamaLlmProvider,
            OpenAiCompatibleLlmProvider cloudLlmProvider,
            CircuitBreakerRegistry circuitBreakerRegistry,
            InferenceGate inferenceGate,
            @Value("${ai.llm.cloud.models:}") List<String> cloudModels,
            @Value("${ai.llm.cloud.model:}") String cloudModel,
            @Value("${ai.llm.inference-queue-timeout:120s}") Duration queueTimeout) {
        List<String> models = new ArrayList<>();
        if (cloudModel != null && !cloudModel.isBlank()) {
            models.add(cloudModel.trim());
        }
        if (cloudModels != null) {
            for (String m : cloudModels) {
                if (m != null && !m.isBlank()) {
                    models.add(m.trim());
                }
            }
        }
        return new LlmProviderRouter(ollamaLlmProvider, cloudLlmProvider, models,
                circuitBreakerRegistry, inferenceGate, queueTimeout);
    }

    /** 全局并发推理闸门：上限由 runtime 配置 inference_concurrency 驱动（ConfigRuntimeService 注入）。 */
    @Bean
    public InferenceGate inferenceGate() {
        return new InferenceGate(1);
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            @Value("${ai.llm.circuit-breaker.enabled:true}") boolean enabled,
            @Value("${ai.llm.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${ai.llm.circuit-breaker.cooldown:30s}") Duration cooldown,
            @Value("${ai.llm.circuit-breaker.window-size:100}") int windowSize) {
        return new CircuitBreakerRegistry(
                new CircuitBreakerConfig(enabled, failureThreshold, cooldown, windowSize));
    }
}
