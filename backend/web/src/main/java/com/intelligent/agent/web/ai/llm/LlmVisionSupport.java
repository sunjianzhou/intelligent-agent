package com.intelligent.agent.web.ai.llm;

import java.util.List;

/**
 * 视觉能力判定（R-14）：委托 {@link ModelCapabilityRegistry}，关键词与显式模型列表
 * 的唯一来源在注册表（TD-10）。
 */
public final class LlmVisionSupport {

    private LlmVisionSupport() {
    }

    public static boolean isVisionModel(String model, List<String> extraModels) {
        return ModelCapabilityRegistry.defaults().supportsVision(model, extraModels);
    }
}
