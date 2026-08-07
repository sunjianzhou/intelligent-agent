package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.llm.cloud.OpenAiCompatibleLlmProvider;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final Set<String> cloudModels;

    public LlmProviderRouter(LlmProvider local,
                             OpenAiCompatibleLlmProvider cloud,
                             List<String> cloudModels) {
        this.local = Objects.requireNonNull(local, "local provider is required");
        this.cloud = cloud;
        this.cloudModels = cloudModels == null ? Set.of() : cloudModels.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    public LlmProvider forUser(String userId, String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (cloud != null && cloud.isConfigured()
                && (model.isEmpty() || cloudModels.contains(model))) {
            return cloud;
        }
        return local;
    }
}
