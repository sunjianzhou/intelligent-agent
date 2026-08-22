package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.skill.SkillMatcher;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.builtin.CalculatorTool;
import com.intelligent.agent.web.ai.tool.builtin.TimeTool;
import com.intelligent.agent.web.domain.skill.SkillService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 技能命中后：系统消息注入 [SKILL]，completeWithTools 收到过滤后的工具集。 */
class AgentOrchestratorSkillTest {

    @TempDir
    Path tempDir;

    @Test
    void matchedSkillInjectsPromptAndFiltersTools() {
        RecordingProvider provider = new RecordingProvider();
        ToolExecutor tools = new ToolExecutor(List.of(new CalculatorTool(), new TimeTool()));
        SkillService skills = new SkillService(tempDir);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "skill_math");
        body.put("name", "数学计算");
        body.put("trigger_keywords", List.of("计算", "算一下"));
        body.put("forced_tools", List.of("CalculatorTool"));
        body.put("overall_strategy", "必须调用工具精确计算，不能心算。");
        body.put("steps", List.of());
        skills.createSkill(body);
        SkillMatcher matcher = new SkillMatcher(skills,
                new LlmProviderRouter(provider, null, List.of()),
                true, Duration.ofSeconds(2));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                tools, null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS,
                null, null, null, null, null, matcher);

        orchestrator.stream(AgentRequestContext.of("u1", "帮我计算 11*11"))
                .blockLast(Duration.ofSeconds(5));

        assertThat(provider.turns).isNotEmpty();
        ChatTurn first = provider.turns.get(0);
        assertThat(first.messages()).anyMatch(m -> m.role().equals("system")
                && m.content() != null
                && m.content().startsWith("[SKILL: 数学计算]")
                && m.content().contains("必须调用工具精确计算"));

        assertThat(provider.toolDefsSeen).isNotEmpty();
        assertThat(provider.toolDefsSeen.get(0))
                .extracting(ToolDefinition::name)
                .containsExactly("calculator");
    }

    @Test
    void unmatchedSkillLeavesFullToolset() {
        RecordingProvider provider = new RecordingProvider();
        ToolExecutor tools = new ToolExecutor(List.of(new CalculatorTool(), new TimeTool()));
        SkillService skills = new SkillService(tempDir);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "skill_math");
        body.put("name", "数学计算");
        body.put("trigger_keywords", List.of("完全不相关的词"));
        body.put("steps", List.of());
        skills.createSkill(body);
        SkillMatcher matcher = new SkillMatcher(skills,
                new LlmProviderRouter(provider, null, List.of()),
                true, Duration.ofSeconds(2));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                tools, null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS,
                null, null, null, null, null, matcher);

        orchestrator.stream(AgentRequestContext.of("u1", "你好呀"))
                .blockLast(Duration.ofSeconds(5));

        assertThat(provider.turns).isNotEmpty();
        assertThat(provider.turns.get(0).messages())
                .noneMatch(m -> m.role().equals("system")
                        && m.content() != null && m.content().startsWith("[SKILL:"));
        assertThat(provider.toolDefsSeen).isNotEmpty();
        assertThat(provider.toolDefsSeen.get(0))
                .extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("calculator", "time_tool");
    }

    /** 记录 completeWithTools 收到的消息与工具集；首轮返回空内容走最终流式。 */
    private static final class RecordingProvider implements LlmProvider {
        final List<ChatTurn> turns = new ArrayList<>();
        final List<List<ToolDefinition>> toolDefsSeen = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.just(ModelEvent.token("好的"), ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            turns.add(turn);
            return Mono.just("好的");
        }

        @Override
        public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
            turns.add(turn);
            toolDefsSeen.add(tools);
            return Mono.just(new LlmResponse("", List.of()));
        }
    }
}
