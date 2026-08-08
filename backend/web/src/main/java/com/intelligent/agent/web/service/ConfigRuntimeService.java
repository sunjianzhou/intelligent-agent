package com.intelligent.agent.web.service;

import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时配置 API 本地化（TODO-110 Task 2）：
 * GET/PATCH /api/config/runtime。配置值持久化到 data/runtime_config.json，
 * 边界校验对齐 Python _RUNTIME_LIMITS 子集。
 */
@Slf4j
@Service
public class ConfigRuntimeService {

    private static final Map<String, double[]> LIMITS = Map.ofEntries(
            Map.entry("inference_concurrency", new double[]{1, 20}),
            Map.entry("response_cache_max_size", new double[]{10, 10000}),
            Map.entry("response_cache_ttl_secs", new double[]{60, 86400}),
            Map.entry("semantic_cache_threshold", new double[]{0.5, 1.0}),
            Map.entry("semantic_cache_max_entries", new double[]{100, 20000}),
            Map.entry("short_term_max_size", new double[]{10, 2000}),
            Map.entry("short_term_ttl_hours", new double[]{1, 720}),
            Map.entry("chat_timeout", new double[]{10, 600}),
            Map.entry("tool_result_max_chars", new double[]{200, 50000}),
            Map.entry("ollama_max_tokens", new double[]{128, 32768}),
            Map.entry("ollama_temperature", new double[]{0.0, 2.0}),
            Map.entry("ollama_num_ctx", new double[]{512, 131072}));

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    private final MemoryRepository memoryRepository;
    private final ConversationMemoryService conversationMemoryService;
    private final SemanticResponseCache semanticResponseCache;
    private final Map<String, Object> runtimeConfig = new ConcurrentHashMap<>();

    public ConfigRuntimeService(MemoryRepository memoryRepository,
                                ConversationMemoryService conversationMemoryService,
                                SemanticResponseCache semanticResponseCache) {
        this.memoryRepository = memoryRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.semanticResponseCache = semanticResponseCache;
    }

    public Map<String, Object> get() {
        Map<String, Object> config = new LinkedHashMap<>(defaults());
        config.putAll(persisted());
        config.putAll(runtimeConfig);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("active_inferences", 0);
        usage.put("concurrency_slots", config.get("inference_concurrency"));
        usage.put("l1_cache_entries", 0);
        usage.put("l2_cache_entries", semanticResponseCache.entries());
        usage.put("short_term_entries", conversationMemoryService.shortTermCount("default"));
        usage.put("long_term_entries", memoryRepository.count(
                MemorySearchQuery.builder("default", "", 100000).build()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", config);
        result.put("usage", usage);
        return result;
    }

    public Map<String, Object> patch(Map<String, Object> body) {
        Map<String, Object> updated = new LinkedHashMap<>();
        if (body != null) {
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                double[] limit = LIMITS.get(entry.getKey());
                if (limit == null) {
                    continue;
                }
                double value = entry.getValue() instanceof Number
                        ? ((Number) entry.getValue()).doubleValue() : 0;
                double clamped = Math.max(limit[0], Math.min(limit[1], value));
                boolean integer = value == Math.rint(value);
                updated.put(entry.getKey(), integer ? (long) clamped : clamped);
            }
        }
        runtimeConfig.putAll(updated);
        persist(updated);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("message", "运行时配置已更新");
        return result;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("inference_concurrency", 1);
        defaults.put("response_cache_max_size", 1000);
        defaults.put("response_cache_ttl_secs", 300);
        defaults.put("semantic_cache_threshold", 0.8);
        defaults.put("semantic_cache_max_entries", 5000);
        defaults.put("short_term_max_size", 100);
        defaults.put("short_term_ttl_hours", 24);
        defaults.put("chat_timeout", 600);
        defaults.put("tool_result_max_chars", 5000);
        defaults.put("ollama_max_tokens", 2048);
        defaults.put("ollama_temperature", 0.7);
        defaults.put("ollama_num_ctx", 4096);
        return defaults;
    }

    private Map<String, Object> persisted() {
        Map<String, Object> config = new JsonFileStore(Path.of(dataDir)).read("runtime_config.json");
        return config == null ? Map.of() : config;
    }

    private void persist(Map<String, Object> updated) {
        try {
            Map<String, Object> config = new LinkedHashMap<>(persisted());
            config.putAll(updated);
            new JsonFileStore(Path.of(dataDir)).write(new String[]{"runtime_config.json"}, config);
        } catch (Exception e) {
            log.warn("运行时配置持久化失败: {}", e.getMessage());
        }
    }
}
