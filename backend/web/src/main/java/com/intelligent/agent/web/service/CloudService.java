package com.intelligent.agent.web.service;

import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 云端 LLM 服务商管理本地化（TODO-110 Task 2）：
 * CRUD + 激活/停用，持久化 data/cloud_providers.json；激活时通知 ModelService 切换。
 */
@Slf4j
@Service
public class CloudService {

    public static final Map<String, String> KNOWN_PROVIDER_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "zhipu", "https://open.bigmodel.cn/api/paas/v4",
            "moonshot", "https://api.moonshot.cn/v1",
            "baidu", "https://qianfan.baidubce.com/v2",
            "siliconflow", "https://api.siliconflow.cn/v1");

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    private final ModelService modelService;

    public CloudService(ModelService modelService) {
        this.modelService = modelService;
    }

    public Map<String, Object> listProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Map<String, Object> provider : all()) {
            providers.add(publicView(provider));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providers", providers);
        return result;
    }

    public Map<String, Object> presets() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("presets", KNOWN_PROVIDER_URLS);
        return result;
    }

    public Map<String, Object> createProvider(Map<String, Object> body) {
        Map<String, Object> provider = new LinkedHashMap<>(body == null ? Map.of() : body);
        if (provider.get("id") == null || String.valueOf(provider.get("id")).isBlank()) {
            provider.put("id", "p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        }
        provider.put("active", false);
        List<Map<String, Object>> providers = all();
        providers.add(provider);
        save(providers);
        return Map.of("success", true, "provider", publicView(provider));
    }

    public Map<String, Object> updateProvider(String providerId, Map<String, Object> body) {
        for (Map<String, Object> provider : all()) {
            if (providerId.equals(provider.get("id"))) {
                if (body != null) {
                    for (Map.Entry<String, Object> entry : body.entrySet()) {
                        if (!"id".equals(entry.getKey())
                                && !"api_key".equals(entry.getKey())
                                || entry.getKey().equals("api_key")
                                && entry.getValue() != null
                                && !String.valueOf(entry.getValue()).isBlank()) {
                            provider.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                save(all());
                return Map.of("success", true, "provider", publicView(provider));
            }
        }
        return Map.of("success", false, "message", "服务商不存在");
    }

    public Map<String, Object> deleteProvider(String providerId) {
        List<Map<String, Object>> providers = all();
        boolean removed = providers.removeIf(p -> providerId.equals(p.get("id")));
        if (removed) {
            save(providers);
        }
        return Map.of("success", removed, "message", removed ? "已删除" : "服务商不存在");
    }

    public Map<String, Object> activate(String providerId) {
        for (Map<String, Object> provider : all()) {
            if (providerId.equals(provider.get("id"))) {
                for (Map<String, Object> other : all()) {
                    other.put("active", false);
                }
                provider.put("active", true);
                save(all());
                modelService.activateCloud(
                        String.valueOf(provider.getOrDefault("provider", "custom")),
                        String.valueOf(provider.getOrDefault("base_url", "")),
                        String.valueOf(provider.getOrDefault("api_key", "")),
                        String.valueOf(provider.getOrDefault("model", "")));
                return Map.of("success", true, "provider_id", providerId);
            }
        }
        return Map.of("success", false, "message", "服务商不存在");
    }

    public Map<String, Object> deactivate() {
        for (Map<String, Object> provider : all()) {
            provider.put("active", false);
        }
        save(all());
        modelService.deactivateCloud();
        return Map.of("success", true);
    }

    private List<Map<String, Object>> all() {
        Map<String, Object> data = new JsonFileStore(Path.of(dataDir)).read("cloud_providers.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = data == null ? new ArrayList<>()
                : (List<Map<String, Object>>) data.getOrDefault("providers", new ArrayList<>());
        return new ArrayList<>(providers);
    }

    private void save(List<Map<String, Object>> providers) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providers", providers);
        new JsonFileStore(Path.of(dataDir)).write(new String[]{"cloud_providers.json"}, data);
    }

    private static Map<String, Object> publicView(Map<String, Object> provider) {
        Map<String, Object> view = new LinkedHashMap<>(provider);
        view.put("api_key", mask(String.valueOf(provider.getOrDefault("api_key", ""))));
        return view;
    }

    private static String mask(String key) {
        if (key == null || key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
