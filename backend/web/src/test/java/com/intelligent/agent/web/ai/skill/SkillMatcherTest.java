package com.intelligent.agent.web.ai.skill;

import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.domain.skill.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 技能运行时匹配/注入：关键词命中、LLM 裁决、禁用跳过、提示词格式、工具过滤。 */
class SkillMatcherTest {

    @TempDir
    Path tempDir;

    private SkillService skillService;
    private String llmAnswer = "none";

    @BeforeEach
    void setUp() {
        skillService = new SkillService(tempDir);
    }

    @Test
    void keywordSingleHitReturnsSkill() {
        createSkill("s1", "数学计算", List.of("计算", "算一下"));

        assertThat(matcher().findSkill("u1", "帮我计算 11*11")).isPresent();
        assertThat(matcher().findSkill("u1", "随便聊聊")).isEmpty();
    }

    @Test
    void disabledSkillIsNotMatched() {
        createSkill("s1", "数学计算", List.of("计算"));
        skillService.updateSkill("s1", Map.of("enabled", false));

        assertThat(matcher().findSkill("u1", "帮我计算")).isEmpty();
    }

    @Test
    void multiKeywordHitUsesLlmAdjudication() {
        createSkill("s1", "数学计算", List.of("算一下"));
        createSkill("s2", "单位换算", List.of("算一下"));
        llmAnswer = "skill_s2";

        assertThat(matcher().findSkill("u1", "帮我算一下"))
                .hasValueSatisfying(s -> assertThat(s.get("id")).isEqualTo("s2"));
    }

    @Test
    void llmAnswerNoneReturnsEmpty() {
        createSkill("s1", "数学计算", List.of("完全不相关的词"));
        llmAnswer = "none";

        assertThat(matcher().findSkill("u1", "你好呀")).isEmpty();
    }

    @Test
    void disabledMatcherReturnsEmpty() {
        createSkill("s1", "数学计算", List.of("计算"));
        SkillMatcher disabled = new SkillMatcher(skillService,
                new LlmProviderRouter(noopProvider("x"), null, List.of()),
                false, Duration.ofSeconds(2));

        assertThat(disabled.findSkill("u1", "帮我计算")).isEmpty();
    }

    @Test
    void injectionPromptContainsStrategyAndSteps() {
        Map<String, Object> skill = Map.of(
                "overall_strategy", "必须调用工具精确计算",
                "steps", List.of(Map.of(
                        "name", "执行计算",
                        "description", "调用计算工具",
                        "forced_tools", List.of("CalculatorTool"),
                        "strategy_prompt", "expression 参数填表达式")));

        String prompt = SkillMatcher.buildInjectionPrompt(skill);

        assertThat(prompt).contains("【整体目标】必须调用工具精确计算")
                .contains("【执行步骤】")
                .contains("第1步【执行计算】")
                .contains("必须调用工具：CalculatorTool")
                .contains("expression 参数填表达式");
    }

    @Test
    void filterToolsNormalizesLegacyNames() {
        List<ToolDefinition> all = List.of(
                new ToolDefinition("calculator", "计算", true, null, null),
                new ToolDefinition("time_tool", "时间", true, null, null),
                new ToolDefinition("file", "文件", true, null, null));
        Map<String, Object> skill = Map.of(
                "forced_tools", List.of("CalculatorTool"),
                "steps", List.of());

        List<ToolDefinition> filtered = matcher().filterTools(all, skill);

        assertThat(filtered).extracting(ToolDefinition::name).containsExactly("calculator");
    }

    @Test
    void filterToolsFallsBackToAllWhenNothingMatches() {
        List<ToolDefinition> all = List.of(
                new ToolDefinition("calculator", "计算", true, null, null));

        List<ToolDefinition> filtered = matcher().filterTools(all,
                Map.of("forced_tools", List.of("no_such_tool"), "steps", List.of()));

        assertThat(filtered).isSameAs(all);
    }

    private SkillMatcher matcher() {
        return new SkillMatcher(skillService,
                new LlmProviderRouter(noopProvider(llmAnswer), null, List.of()),
                true, Duration.ofSeconds(2));
    }

    private Map<String, Object> createSkill(String id, String name, List<String> keywords) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("description", name + " 描述");
        body.put("trigger_keywords", keywords);
        body.put("scenario_tags", List.of());
        body.put("overall_strategy", "策略");
        body.put("steps", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> created =
                (Map<String, Object>) skillService.createSkill(body).get("skill");
        return created;
    }

    private static LlmProvider noopProvider(String answer) {
        return new LlmProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Flux<ModelEvent> stream(ChatTurn turn) {
                return Flux.just(new ModelEvent("content", "ok"));
            }

            @Override
            public Mono<String> complete(ChatTurn turn) {
                return Mono.just(answer);
            }

            @Override
            public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
                return Mono.just(new LlmResponse("", List.of()));
            }
        };
    }
}
