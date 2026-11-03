package com.intelligent.agent.web.ai.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TD-10：模型能力注册表覆盖四类能力——text-tool 前缀、视觉、unrestricted 覆盖层、num_ctx。
 */
class ModelCapabilityRegistryTest {

    @Test
    void textToolPatternsUseDefaultsAndConfigOverrides() {
        ModelCapabilityRegistry defaults = ModelCapabilityRegistry.defaults();

        assertThat(defaults.supportsTextTools("ollama/dolphin:7b")).isTrue();
        assertThat(defaults.supportsTextTools("orca-mini:3b")).isTrue();
        assertThat(defaults.supportsTextTools("qwen2.5:7b")).isFalse();

        ModelCapabilityRegistry custom = new ModelCapabilityRegistry(
                List.of("my-text-model"), null, null, null, 0, null);
        assertThat(custom.supportsTextTools("my-text-model:latest")).isTrue();
        assertThat(custom.supportsTextTools("dolphin:7b")).isFalse();
    }

    @Test
    void visionUsesKeywordsAndExplicitModels() {
        ModelCapabilityRegistry registry = new ModelCapabilityRegistry(null, null,
                List.of("custom-vm"), null, 0, null);

        assertThat(registry.supportsVision("qwen2.5-vl:7b")).isTrue();
        assertThat(registry.supportsVision("llava:13b")).isTrue();
        assertThat(registry.supportsVision("gpt-4o")).isTrue();
        assertThat(registry.supportsVision("custom-vm")).isTrue();
        assertThat(registry.supportsVision("qwen2.5:7b")).isFalse();
        // 兼容旧 API：调用方追加的显式模型名同样生效
        assertThat(registry.supportsVision("other-vm", List.of("other-vm"))).isTrue();
    }

    @Test
    void visionKeywordsAreConfigurable() {
        ModelCapabilityRegistry registry = new ModelCapabilityRegistry(
                null, List.of("my-vision"), List.of(), null, 0, null);

        assertThat(registry.supportsVision("my-vision:1b")).isTrue();
        // 覆盖后不再包含内置关键词
        assertThat(registry.supportsVision("llava:13b")).isFalse();
    }

    @Test
    void unrestrictedOverlayDefaultsToDolphin() {
        ModelCapabilityRegistry defaults = ModelCapabilityRegistry.defaults();

        assertThat(defaults.needsUnrestrictedOverlay("dolphin:7b")).isTrue();
        assertThat(defaults.needsUnrestrictedOverlay("qwen2.5:7b")).isFalse();

        ModelCapabilityRegistry custom = new ModelCapabilityRegistry(
                null, null, null, List.of("uncensored"), 0, null);
        assertThat(custom.needsUnrestrictedOverlay("uncensored:7b")).isTrue();
        assertThat(custom.needsUnrestrictedOverlay("dolphin:7b")).isFalse();
    }

    @Test
    void numCtxUsesModelTableThenDefault() {
        ModelCapabilityRegistry registry = new ModelCapabilityRegistry(null, null, null, null,
                4096, Map.of("qwen2.5:7b", 16384));

        assertThat(registry.numCtxFor("qwen2.5:7b")).isEqualTo(16384);
        assertThat(registry.numCtxFor("llama3.1:8b")).isEqualTo(4096);
        assertThat(registry.numCtxFor(null)).isEqualTo(4096);
        assertThat(registry.defaultNumCtx()).isEqualTo(4096);
        assertThat(registry.numCtxByModel()).containsEntry("qwen2.5:7b", 16384);
    }

    @Test
    void blankOverridesFallBackToDefaults() {
        ModelCapabilityRegistry registry = new ModelCapabilityRegistry(
                List.of(""), List.of("  "), List.of(), List.of(), 0, Map.of());

        assertThat(registry.supportsTextTools("dolphin:7b")).isTrue();
        assertThat(registry.supportsVision("llava:13b")).isTrue();
        assertThat(registry.needsUnrestrictedOverlay("dolphin:7b")).isTrue();
        assertThat(registry.defaultNumCtx()).isEqualTo(ModelCapabilityRegistry.DEFAULT_NUM_CTX);
    }
}
