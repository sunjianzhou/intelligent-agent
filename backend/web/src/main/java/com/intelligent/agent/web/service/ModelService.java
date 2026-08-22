package com.intelligent.agent.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型列表与 per-user 切换（TODO-110 Task 2 本地化）：
 * 本地模型来自 Ollama /api/tags；云端模型来自 ai.llm.cloud 配置；
 * per-user 选择持久化到 data/user_model_prefs.json。
 */
@Slf4j
@Service
public class ModelService {

    private static final List<String> KNOWN_CLOUD_PROVIDERS = List.of("openai", "deepseek", "custom");

    @Value("${ai.llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ai.llm.ollama.model:qwen2.5:7b}")
    private String defaultModel;

    @Value("${ai.llm.cloud.provider:}")
    private String cloudProvider;

    @Value("${ai.llm.cloud.api-key:}")
    private String cloudApiKey;

    @Value("${ai.llm.cloud.base-url:}")
    private String cloudBaseUrl;

    @Value("${ai.llm.cloud.model:}")
    private String cloudModel;

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> runtimeModels = new ConcurrentHashMap<>();
    private volatile Map<String, String> cloudOverride;
    /** 用户模型偏好内存缓存：首次读取后常驻，切换时同步更新，避免每次聊天都读磁盘。*/
    private volatile Map<String, Object> prefsCache;
    /** Ollama /api/tags 结果缓存（30s TTL）：WS 建连/系统信息/模型列表不再每次都打 Ollama。*/
    private static final long OLLAMA_TAGS_TTL_MS = 30_000;
    private volatile List<String> cachedOllamaTags;
    private volatile long ollamaTagsFetchedAt;

    public ModelService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 云端服务商激活（CloudService 调用）：运行时覆盖 @Value 静态配置。 */
    public void activateCloud(String provider, String baseUrl, String apiKey, String model) {
        Map<String, String> override = new java.util.concurrent.ConcurrentHashMap<>();
        override.put("provider", provider);
        override.put("base_url", baseUrl);
        override.put("api_key", apiKey);
        override.put("model", model);
        this.cloudOverride = override;
    }

    public void deactivateCloud() {
        this.cloudOverride = null;
    }

    public Map<String, Object> getModels(String userId) {
        List<String> localModels = ollamaTags();
        Map<String, String> cloud = cloudConfig();
        boolean configuredCloud = cloudConfigured(cloud);
        String cloudModelName = configuredCloud ? cloud.get("model") : "";

        Set<String> all = new LinkedHashSet<>(localModels);
        if (notBlank(cloudModelName)) {
            all.add(cloudModelName);
        }
        String current = currentModel(userId, configuredCloud, cloudModelName);
        boolean userIsCloud = configuredCloud && cloudModelName.equals(current);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available_models", new ArrayList<>(all));
        result.put("current_model", current);
        result.put("ollama_available", !localModels.isEmpty());
        result.put("cloud_mode", userIsCloud);
        result.put("cloud_model", userIsCloud ? cloudModelName : "");
        result.put("cloud_provider", configuredCloud ? cloud.get("provider") : "");
        result.put("known_cloud_providers", KNOWN_CLOUD_PROVIDERS);
        return result;
    }

    /**
     * 解析某用户当前生效的推理模型（不触发网络请求）：
     * 运行时切换 → 持久化偏好 → 已配置云端模型 → 默认模型。
     * 由 {@code LocalChatService} 在每次聊天时调用，让 per-user 模型切换真正生效。
     */
    public String resolveModel(String userId) {
        String key = effective(userId);
        String runtime = runtimeModels.get(key);
        if (runtime != null) {
            return runtime;
        }
        Map<String, Object> prefs = prefs();
        Object saved = prefs.get(key);
        if (saved != null) {
            return String.valueOf(saved);
        }
        Map<String, String> cloud = cloudConfig();
        return cloudConfigured(cloud) ? cloud.get("model") : defaultModel;
    }

    /** Ollama 是否可达（本地模型列表非空）。 */
    public boolean ollamaAvailable() {
        return !ollamaTags().isEmpty();
    }

    /** 当前生效的云端配置（含运行时激活覆盖）；未配置时返回全空 map。 */
    public Map<String, String> activeCloudConfig() {
        return cloudConfig();
    }

    public Map<String, Object> switchModel(String userId, String modelName) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (modelName == null || modelName.isBlank()) {
            result.put("success", false);
            result.put("message", "model 参数不能为空");
            return result;
        }
        Map<String, Object> models = getModels(userId);
        @SuppressWarnings("unchecked")
        List<String> available = (List<String>) models.getOrDefault("available_models", List.of());
        if (!available.contains(modelName)) {
            result.put("success", false);
            result.put("message", "模型不可用: " + modelName);
            return result;
        }
        runtimeModels.put(effective(userId), modelName);
        persistPreference(userId, modelName);
        result.put("success", true);
        result.put("current_model", modelName);
        return result;
    }

    private String currentModel(String userId, boolean configuredCloud, String cloudModelName) {
        String key = effective(userId);
        if (runtimeModels.containsKey(key)) {
            return runtimeModels.get(key);
        }
        Map<String, Object> prefs = prefs();
        Object saved = prefs.get(key);
        if (saved != null) {
            return String.valueOf(saved);
        }
        return configuredCloud ? cloudModelName : defaultModel;
    }

    private Map<String, String> cloudConfig() {
        if (cloudOverride != null) {
            return cloudOverride;
        }
        Map<String, String> cloud = new LinkedHashMap<>();
        cloud.put("provider", cloudProvider == null ? "" : cloudProvider);
        cloud.put("base_url", cloudBaseUrl == null ? "" : cloudBaseUrl);
        cloud.put("api_key", cloudApiKey == null ? "" : cloudApiKey);
        cloud.put("model", cloudModel == null ? "" : cloudModel);
        return cloud;
    }

    private static boolean cloudConfigured(Map<String, String> cloud) {
        return notBlank(cloud.get("provider"))
                && notBlank(cloud.get("api_key")) && notBlank(cloud.get("model"));
    }

    private List<String> ollamaTags() {
        long now = System.currentTimeMillis();
        List<String> cached = cachedOllamaTags;
        if (cached != null && now - ollamaTagsFetchedAt < OLLAMA_TAGS_TTL_MS) {
            return cached;
        }
        synchronized (this) {
            cached = cachedOllamaTags;
            if (cached != null
                    && System.currentTimeMillis() - ollamaTagsFetchedAt < OLLAMA_TAGS_TTL_MS) {
                return cached;
            }
            List<String> tags = fetchOllamaTags();
            cachedOllamaTags = tags;
            ollamaTagsFetchedAt = System.currentTimeMillis();
            return tags;
        }
    }

    private List<String> fetchOllamaTags() {
        try {
            String url = ollamaBaseUrl.endsWith("/") ? ollamaBaseUrl : ollamaBaseUrl + "/";
            ResponseEntity<String> response = restTemplate.getForEntity(url + "api/tags", String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return List.of();
            }
            Map<String, Object> parsed = objectMapper.readValue(
                    response.getBody(), new TypeReference<>() {});
            Object models = parsed.get("models");
            if (!(models instanceof List)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (Object model : (List<?>) models) {
                if (model instanceof Map) {
                    Object name = ((Map<?, ?>) model).get("name");
                    if (name != null) {
                        names.add(String.valueOf(name));
                    }
                }
            }
            return names;
        } catch (Exception e) {
            log.debug("Ollama /api/tags 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void persistPreference(String userId, String modelName) {
        try {
            Map<String, Object> prefs = new LinkedHashMap<>(prefs());
            prefs.put(effective(userId), modelName);
            new JsonFileStore(Path.of(dataDir)).write(new String[]{"user_model_prefs.json"}, prefs);
            prefsCache = prefs;
        } catch (Exception e) {
            log.warn("模型偏好持久化失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> prefs() {
        Map<String, Object> cached = prefsCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = prefsCache;
            if (cached == null) {
                Map<String, Object> prefs =
                        new JsonFileStore(Path.of(dataDir)).read("user_model_prefs.json");
                cached = prefs == null ? new LinkedHashMap<>() : prefs;
                prefsCache = cached;
            }
        }
        return cached;
    }

    private static String effective(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
