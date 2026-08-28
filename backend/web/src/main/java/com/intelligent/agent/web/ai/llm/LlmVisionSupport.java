package com.intelligent.agent.web.ai.llm;

import java.util.List;
import java.util.Locale;

/**
 * 视觉能力判定（R-14）：按模型名关键词 + 显式配置列表判断是否支持图片理解。
 * 关键词命中即视为视觉模型；{@code ai.llm.vision-models} 可显式覆盖未收录模型。
 */
public final class LlmVisionSupport {

    private static final List<String> VISION_KEYWORDS = List.of(
            "vl", "vision", "llava", "minicpm", "4o", "gpt-4-vision",
            "gemini", "claude-3", "qwen-vl", "glm-4v", "internvl",
            "bakllava", "moondream", "phi-3-vision", "llama3.2-vision",
            "llama3.2-v", "qwen2.5-vl");

    private LlmVisionSupport() {
    }

    public static boolean isVisionModel(String model, List<String> extraModels) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (extraModels != null) {
            for (String extra : extraModels) {
                if (extra != null && !extra.isBlank()
                        && normalized.equals(extra.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        for (String keyword : VISION_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
