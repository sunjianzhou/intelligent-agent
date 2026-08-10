package com.intelligent.agent.web.ai.prompt;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptService 测试：模型覆盖层锚定、工具指令、channel 透传。
 */
class PromptServiceTest {

    private static final ToolExecutor TOOLS = new ToolExecutor(List.of(new AgentTool() {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("calculator", "数学计算", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return 0;
        }
    }));

    private final SoulLoader loader = new SoulLoader(java.nio.file.Path.of("../../soul"));
    private final PromptService service = new PromptService(
            loader, new SystemPromptBuilder(), TOOLS, null,
            List.of("dolphin", "phi2"), "qwen2.5:7b", 8000);

    @Test
    void appendsDolphinAnchorForTextToolModels() {
        AgentRequestContext ctx = AgentRequestContext.of("u1", "你好");
        AgentRequestContext dolphinCtx = new AgentRequestContext(
                "u1", "你好", "dolphin:7b", null, null, null, true, true, "web", Map.of());

        assertThat(service.buildSystemPrompt(ctx)).doesNotContain("IMPORTANT REMINDER");
        assertThat(service.buildSystemPrompt(dolphinCtx)).contains("IMPORTANT REMINDER");
        assertThat(service.effectiveModel(dolphinCtx)).isEqualTo("dolphin:7b");
    }

    @Test
    void toolOverlayInjectedWhenToolsEnabled() {
        AgentRequestContext ctx = AgentRequestContext.of("u1", "你好");
        String prompt = service.buildSystemPrompt(ctx);

        assertThat(prompt).contains("Available tools").contains("calculator");
        assertThat(prompt).contains("<tool_call>");
    }

    @Test
    void toolOverlaySkippedWhenToolsDisabled() {
        AgentRequestContext ctx = new AgentRequestContext(
                "u1", "你好", null, null, null, null, false, true, "web", Map.of());
        assertThat(service.buildSystemPrompt(ctx)).doesNotContain("Available tools");
    }

    @Test
    void effectiveModelFallsBackToDefault() {
        assertThat(service.effectiveModel(AgentRequestContext.of("u1", "hi"))).isEqualTo("qwen2.5:7b");
    }

    @Test
    void textToolPatternMatchingIsPrefixInsensitive() {
        assertThat(service.isTextToolModel("ollama/dolphin:7b")).isTrue();
        assertThat(service.isTextToolModel("qwen2.5:7b")).isFalse();
    }
}
