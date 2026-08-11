package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.llm.cloud.OpenAiCompatibleLlmProvider;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM provider 路由器：按请求模型解析实际 provider。
 * <p>
 * 云端（OpenAI 兼容）配置齐全且请求模型命中云模型清单（或未指定模型）时走云端，
 * 其余情况一律回退本地 Ollama。userId 预留用于后续 per-user provider 覆盖，
 * 当前不参与判定。
 */
public class LlmProviderRouter {

    private final LlmProvider local;
    private final OpenAiCompatibleLlmProvider cloud;
    private final Set<String> cloudModels = ConcurrentHashMap.newKeySet();

    public LlmProviderRouter(LlmProvider local,
                             OpenAiCompatibleLlmProvider cloud,
                             List<String> cloudModels) {
        this.local = Objects.requireNonNull(local, "local provider is required");
        this.cloud = cloud;
        if (cloudModels != null) {
            for (String model : cloudModels) {
                registerCloudModel(model);
            }
        }
    }

    public LlmProvider forUser(String userId, String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (cloud != null && cloud.isConfigured()
                && (model.isEmpty() || cloudModels.contains(model))) {
            return cloud;
        }
        return local;
    }

    /** 运行期注册云端模型名（CloudService 激活时调用），使路由立即识别。 */
    public void registerCloudModel(String model) {
        if (model != null && !model.isBlank()) {
            cloudModels.add(model.trim());
        }
    }

    /** 停用云端时清空运行期注册的模型名（静态配置不受影响，但无配置时路由回落本地）。 */
    public void clearCloudModels() {
        cloudModels.clear();
    }
}
