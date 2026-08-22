package com.intelligent.agent.web.service;

import com.intelligent.agent.web.ai.llm.InferenceGate;
import com.intelligent.agent.web.ai.agent.ActiveChatLimiter;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
            Map.entry("stream_concurrency", new double[]{1, 100}),
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

    /** runtime 配置中会注入到 LLM 请求的键 → {@code AgentRequestContext.options} 键映射。
     *  只注入已持久化的值（保存后生效），未保存前保持模型配置表 / application.yml 默认值不变。 */
    private static final Map<String, String> LLM_OPTION_KEYS = Map.of(
            "ollama_temperature", "temperature",
            "ollama_max_tokens", "max_tokens",
            "ollama_num_ctx", "num_ctx",
            "chat_timeout", "chat_timeout");

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    private final MemoryRepository memoryRepository;
    private final ConversationMemoryService conversationMemoryService;
    private final SemanticResponseCache semanticResponseCache;
    private final InferenceGate inferenceGate;
    private final ActiveChatLimiter activeChatLimiter;
    private final Map<String, Object> runtimeConfig = new ConcurrentHashMap<>();
    /** 持久化配置的内存缓存：首次读取后常驻，patch 时同步更新，避免每次聊天都读磁盘。*/
    private volatile Map<String, Object> persistedCache;

    public ConfigRuntimeService(MemoryRepository memoryRepository,
                                ConversationMemoryService conversationMemoryService,
                                SemanticResponseCache semanticResponseCache,
                                InferenceGate inferenceGate) {
        this(memoryRepository, conversationMemoryService, semanticResponseCache,
                inferenceGate, null);
    }

    @Autowired
    public ConfigRuntimeService(MemoryRepository memoryRepository,
                                ConversationMemoryService conversationMemoryService,
                                SemanticResponseCache semanticResponseCache,
                                InferenceGate inferenceGate,
                                ActiveChatLimiter activeChatLimiter) {
        this.memoryRepository = memoryRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.semanticResponseCache = semanticResponseCache;
        this.inferenceGate = inferenceGate;
        this.activeChatLimiter = activeChatLimiter;
    }

    /** 启动时把持久化（或默认）的 inference_concurrency / stream_concurrency 应用到闸门与限流器。 */
    @PostConstruct
    public void applyInferenceConcurrency() {
        inferenceGate.setMaxConcurrency(inferenceConcurrency());
        if (activeChatLimiter != null) {
            activeChatLimiter.setMaxConcurrency(streamConcurrency());
        }
    }

    public Map<String, Object> get() {
        Map<String, Object> config = new LinkedHashMap<>(defaults());
        config.putAll(persisted());
        config.putAll(runtimeConfig);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("active_inferences", inferenceGate.active());
        usage.put("concurrency_slots", config.get("inference_concurrency"));
        usage.put("active_streams", activeChatLimiter == null ? 0 : activeChatLimiter.active());
        usage.put("stream_slots", config.get("stream_concurrency"));
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
        Object concurrency = updated.get("inference_concurrency");
        if (concurrency instanceof Number n) {
            inferenceGate.setMaxConcurrency(n.intValue());
        }
        Object streams = updated.get("stream_concurrency");
        if (streams instanceof Number n && activeChatLimiter != null) {
            activeChatLimiter.setMaxConcurrency(n.intValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("message", "运行时配置已更新");
        return result;
    }

    /** 返回应注入本次 LLM 请求的模型参数（仅持久化的键，未保存的键不注入）。
     *  键名与 {@code ChatTurn.options} / provider 读取的参数名一致。 */
    public Map<String, Object> llmRequestOptions() {
        Map<String, Object> persisted = persisted();
        Map<String, Object> options = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : LLM_OPTION_KEYS.entrySet()) {
            Object value = persisted.get(entry.getKey());
            if (value != null) {
                options.put(entry.getValue(), value);
            }
        }
        return options;
    }

    /** 工具结果回传 LLM 前的最大字符数（默认 5000；runtime 配置覆盖）。 */
    public int toolResultMaxChars() {
        Object v = runtimeConfig.get("tool_result_max_chars");
        if (v == null) {
            v = persisted().get("tool_result_max_chars");
        }
        if (v == null) {
            v = defaults().get("tool_result_max_chars");
        }
        return v instanceof Number n ? n.intValue() : 5000;
    }

    /** 并发推理上限（默认 1，runtime 配置覆盖），驱动全局 {@link InferenceGate}。 */
    public int inferenceConcurrency() {
        Object v = runtimeConfig.get("inference_concurrency");
        if (v == null) {
            v = persisted().get("inference_concurrency");
        }
        if (v == null) {
            v = defaults().get("inference_concurrency");
        }
        return v instanceof Number n ? n.intValue() : 1;
    }

    /** 流式对话并发上限（默认 32，runtime 配置 stream_concurrency 覆盖）。 */
    public int streamConcurrency() {
        Object v = runtimeConfig.get("stream_concurrency");
        if (v == null) {
            v = persisted().get("stream_concurrency");
        }
        if (v == null) {
            v = defaults().get("stream_concurrency");
        }
        return v instanceof Number n ? n.intValue() : 32;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("inference_concurrency", 1);
        defaults.put("stream_concurrency", 32);
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
        Map<String, Object> cached = persistedCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = persistedCache;
            if (cached == null) {
                Map<String, Object> config =
                        new JsonFileStore(Path.of(dataDir)).read("runtime_config.json");
                cached = config == null ? Map.of() : config;
                persistedCache = cached;
            }
        }
        return cached;
    }

    private void persist(Map<String, Object> updated) {
        try {
            Map<String, Object> config = new LinkedHashMap<>(persisted());
            config.putAll(updated);
            new JsonFileStore(Path.of(dataDir)).write(new String[]{"runtime_config.json"}, config);
            persistedCache = config;
        } catch (Exception e) {
            log.warn("运行时配置持久化失败: {}", e.getMessage());
        }
    }
}
