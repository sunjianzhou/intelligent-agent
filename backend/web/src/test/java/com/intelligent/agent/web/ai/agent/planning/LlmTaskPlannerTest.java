package com.intelligent.agent.web.ai.agent.planning;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 计划器测试：JSON/文本解析、失败降级、简单消息跳过、禁用开关。
 */
class LlmTaskPlannerTest {

    /** 按脚本回复的 provider：记录每次调用，可注入错误。 */
    static class ScriptedProvider implements LlmProvider {
        final List<Mono<String>> replies;
        final List<ChatTurn> turns = new ArrayList<>();
        final int[] calls = {0};

        ScriptedProvider(List<Mono<String>> replies) {
            this.replies = replies;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.empty();
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            turns.add(turn);
            int idx = Math.min(calls[0]++, replies.size() - 1);
            return replies.get(idx);
        }
    }

    private static final String COMPLEX_MESSAGE =
            "帮我查一下明天的天气，然后计算一下适合穿什么，最后提醒我出门带伞";

    private static final String JSON_PLAN =
            "{\"steps\":[{\"title\":\"查天气\",\"detail\":\"搜索明日天气数据\"},"
                    + "{\"title\":\"计算着装\"},{\"title\":\"设置提醒\"}]}";

    private LlmTaskPlanner planner(ScriptedProvider provider) {
        return new LlmTaskPlanner(new LlmProviderRouter(provider, null, List.of()));
    }

    private AgentRequestContext complexCtx() {
        return AgentRequestContext.of("u1", COMPLEX_MESSAGE);
    }

    @Test
    void parsesJsonPlanFromLlmReply() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(JSON_PLAN)));
        Optional<ExecutionPlan> plan = planner(provider).plan(complexCtx());

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(3);
        assertThat(plan.get().steps().get(0).title()).isEqualTo("查天气");
        assertThat(plan.get().steps().get(0).detail()).isEqualTo("搜索明日天气数据");
        assertThat(plan.get().steps().get(1).detail()).isEmpty();
    }

    @Test
    void fallsBackToLineParsingWhenJsonInvalid() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(
                "- 第一步：查天气\n- 第二步：计算着装\n- 第三步：设置提醒")));
        Optional<ExecutionPlan> plan = planner(provider).plan(complexCtx());

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(3);
        assertThat(plan.get().steps().get(0).title()).contains("查天气");
    }

    @Test
    void returnsEmptyWhenLlmFails() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.error(
                new RuntimeException("model down"))));
        assertThat(planner(provider).plan(complexCtx())).isEmpty();
    }

    @Test
    void returnsEmptyWhenReplyIsBlank() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just("   ")));
        assertThat(planner(provider).plan(complexCtx())).isEmpty();
    }

    @Test
    void skipsPlanningForSimpleMessageWithoutLlmCall() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(JSON_PLAN)));
        Optional<ExecutionPlan> plan = planner(provider).plan(AgentRequestContext.of("u1", "你好"));

        assertThat(plan).isEmpty();
        assertThat(provider.calls[0]).isZero();
    }

    @Test
    void skipsPlanningWhenDisabled() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(JSON_PLAN)));
        LlmTaskPlanner disabled = new LlmTaskPlanner(
                new LlmProviderRouter(provider, null, List.of()),
                new PlanningComplexityDetector(), false, Duration.ofSeconds(30), 6);

        assertThat(disabled.plan(complexCtx())).isEmpty();
        assertThat(provider.calls[0]).isZero();
    }

    @Test
    void capsStepCount() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(
                "{\"steps\":[" + String.join(",", java.util.Collections.nCopies(10,
                        "{\"title\":\"s\"}")) + "]}")));
        LlmTaskPlanner capped = new LlmTaskPlanner(
                new LlmProviderRouter(provider, null, List.of()),
                new PlanningComplexityDetector(), true, Duration.ofSeconds(30), 3);

        Optional<ExecutionPlan> plan = capped.plan(complexCtx());
        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(3);
    }

    @Test
    void filtersBlankTitles() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(
                "{\"steps\":[{\"title\":\"  \"},{\"title\":\"有效步骤\"}]}")));
        Optional<ExecutionPlan> plan = planner(provider).plan(complexCtx());

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(1);
        assertThat(plan.get().steps().get(0).title()).isEqualTo("有效步骤");
    }

    @Test
    void parsesParallelGroupField() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(
                "{\"steps\":[{\"title\":\"查天气\",\"group\":1},"
                        + "{\"title\":\"算着装\",\"group\":1},{\"title\":\"汇总\",\"group\":2}]}")));
        Optional<ExecutionPlan> plan = planner(provider).plan(complexCtx());

        assertThat(plan).isPresent();
        List<PlanStep> steps = plan.get().steps();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).group()).isEqualTo(1);
        assertThat(steps.get(1).group()).isEqualTo(1);
        assertThat(steps.get(2).group()).isEqualTo(2);
    }

    @Test
    void groupDefaultsToSerialWhenAbsent() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(
                "{\"steps\":[{\"title\":\"A\"},{\"title\":\"B\"}]}")));
        Optional<ExecutionPlan> plan = planner(provider).plan(complexCtx());

        assertThat(plan).isPresent();
        assertThat(plan.get().steps())
                .allMatch(step -> step.group() == 0);
    }

    @Test
    void parallelGroupsKeepOrderAndGroupIndependentSteps() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                new PlanStep("A", "", 1),
                new PlanStep("B", "", 0),
                new PlanStep("C", "", 1),
                new PlanStep("D", "", 2)));

        List<List<PlanStep>> groups = plan.parallelGroups();
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0)).extracting(PlanStep::title).containsExactly("A", "C");
        assertThat(groups.get(1)).extracting(PlanStep::title).containsExactly("B");
        assertThat(groups.get(2)).extracting(PlanStep::title).containsExactly("D");
    }

    @Test
    void planningCallUsesLowTemperatureAndUserMessage() {
        ScriptedProvider provider = new ScriptedProvider(List.of(Mono.just(JSON_PLAN)));
        planner(provider).plan(complexCtx());

        assertThat(provider.turns).hasSize(1);
        ChatTurn turn = provider.turns.get(0);
        assertThat(turn.options()).containsEntry("temperature", 0.2);
        assertThat(turn.messages().get(0).role()).isEqualTo("system");
        assertThat(turn.messages().get(1).role()).isEqualTo("user");
        assertThat(turn.messages().get(1).content()).isEqualTo(COMPLEX_MESSAGE);
    }
}
