package com.intelligent.agent.web.ai.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** R-14：视觉模型能力判定（关键词 + 显式列表）。 */
class LlmVisionSupportTest {

    @Test
    void detectsVisionModelsByKeyword() {
        assertThat(LlmVisionSupport.isVisionModel("qwen2.5-vl:7b", List.of())).isTrue();
        assertThat(LlmVisionSupport.isVisionModel("llava:13b", List.of())).isTrue();
        assertThat(LlmVisionSupport.isVisionModel("gpt-4o", List.of())).isTrue();
        assertThat(LlmVisionSupport.isVisionModel("claude-3-5-sonnet", List.of())).isTrue();
        assertThat(LlmVisionSupport.isVisionModel("gemini-2.0-flash", List.of())).isTrue();
        assertThat(LlmVisionSupport.isVisionModel("qwen2.5:7b", List.of())).isFalse();
        assertThat(LlmVisionSupport.isVisionModel("deepseek-chat", List.of())).isFalse();
    }

    @Test
    void explicitListOverridesUnknownModel() {
        assertThat(LlmVisionSupport.isVisionModel("custom-vm", List.of("custom-vm"))).isTrue();
        assertThat(LlmVisionSupport.isVisionModel("custom-vm", List.of("other"))).isFalse();
        assertThat(LlmVisionSupport.isVisionModel(null, List.of("custom-vm"))).isFalse();
        assertThat(LlmVisionSupport.isVisionModel("", List.of())).isFalse();
    }
}
