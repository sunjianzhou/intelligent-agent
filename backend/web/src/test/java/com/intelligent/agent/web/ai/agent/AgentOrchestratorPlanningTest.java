package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.agent.planning.ExecutionPlan;
import com.intelligent.agent.web.ai.agent.planning.LlmTaskPlanner;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6 planning 前置编排集成测试：plan 事件顺序、计划注入执行上下文、
 * 非复杂消息/失败降级时不产生 plan 事件。
 */
class AgentOrchestratorPlanningTest {

    /** 记录每次 complete/completeWithTools 的 turn，按脚本回复。 */
    static class CapturingProvider implements LlmProvider {
        final List<ChatTurn> turns = new ArrayList<>();
        final List<Mono<String>> replies;
        final Flux<ModelEvent> streamEvents;
        final int[] calls = {0};

        CapturingProvider(List<Mono<String>> replies, Flux<ModelEvent> streamEvents) {
            this.replies = replies;
            this.streamEvents = streamEvents;
        }

        @Override
        public String name() {
            return "capturing";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return streamEvents;
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            turns.add(turn);
            int idx = Math.min(calls[0]++, replies.size() - 1);
            return replies.get(idx);
        }
    }

    static class EchoTool implements AgentTool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("echo", "回显", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return "echo:" + arguments.getOrDefault("text", "");
        }
    }

    private static final String COMPLEX_MESSAGE =
            "帮我查一下明天的天气，然后计算一下适合穿什么，最后提醒我出门带伞";

    private static final String JSON_PLAN =
            "{\"steps\":[{\"title\":\"查天气\"},{\"title\":\"计算着装\"},{\"title\":\"设置提醒\"}]}";

    private LlmTaskPlanner planner(CapturingProvider provider) {
        return new LlmTaskPlanner(new LlmProviderRouter(provider, null, List.of()));
    }

    private AgentRequestContext ctx(String message) {
        return AgentRequestContext.of("u1", message);
    }

    private AgentOrchestrator orchestrator(CapturingProvider provider) {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(new EchoTool())),
                null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS, null, null,
                planner(provider));
    }

    @Test
    void streamEmitsPlanEventBeforeAnswerAndInjectsPlanIntoExecution() {
        CapturingProvider provider = new CapturingProvider(
                List.of(Mono.just(JSON_PLAN), Mono.just("好的，已按计划执行完毕")),
                Flux.just(ModelEvent.token("好的，已按计划执行完毕"), ModelEvent.done(Map.of())));

        StepVerifier.create(orchestrator(provider).stream(ctx(COMPLEX_MESSAGE)))
                .expectNextMatches(e -> e.type().equals("plan")
                        && e.data() instanceof ExecutionPlan plan
                        && plan.steps().size() == 3)
                .expectNextMatches(e -> e.type().equals("token")
                        && e.data().equals("好的，已按计划执行完毕"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        // 第 1 次调用是规划（无 [PLAN]）；第 2 次调用（执行轮）必须带 [PLAN]
        assertThat(provider.turns).hasSize(2);
        List<ChatMessage> execMessages = provider.turns.get(1).messages();
        ChatMessage planMsg = execMessages.stream()
                .filter(m -> m.role().equals("system") && m.content().contains("[PLAN]"))
                .findFirst().orElseThrow();
        assertThat(planMsg.content()).contains("查天气").contains("设置提醒");
        // [PLAN] 消息位于用户消息之前
        int planIdx = execMessages.indexOf(planMsg);
        assertThat(execMessages.get(planIdx + 1).role()).isEqualTo("user");
        assertThat(execMessages.get(planIdx + 1).content()).isEqualTo(COMPLEX_MESSAGE);
    }

    @Test
    void noPlanEventForSimpleMessage() {
        CapturingProvider provider = new CapturingProvider(
                List.of(Mono.just("你好")),
                Flux.just(ModelEvent.token("你好"), ModelEvent.done(Map.of())));

        StepVerifier.create(orchestrator(provider).stream(ctx("你好")))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("你好"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        // 无规划调用；执行轮也不含 [PLAN]
        assertThat(provider.turns).hasSize(1);
        assertThat(provider.turns.get(0).messages())
                .noneMatch(m -> m.content() != null && m.content().contains("[PLAN]"));
    }

    @Test
    void noPlanEventWhenPlannerDegradesToEmpty() {
        // 规划调用失败 → planner 返回 empty；执行正常继续，无 plan 事件
        CapturingProvider provider = new CapturingProvider(
                List.of(Mono.error(new RuntimeException("model down")), Mono.just("最终答案")),
                Flux.just(ModelEvent.token("最终答案"), ModelEvent.done(Map.of())));

        StepVerifier.create(orchestrator(provider).stream(ctx(COMPLEX_MESSAGE)))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("最终答案"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void completeInjectsPlanIntoExecution() {
        CapturingProvider provider = new CapturingProvider(
                List.of(Mono.just(JSON_PLAN), Mono.just("好的，已按计划执行完毕")),
                Flux.empty());

        String answer = orchestrator(provider).complete(ctx(COMPLEX_MESSAGE)).block(Duration.ofSeconds(5));
        assertThat(answer).isEqualTo("好的，已按计划执行完毕");

        assertThat(provider.turns).hasSize(2);
        assertThat(provider.turns.get(1).messages())
                .anyMatch(m -> m.role().equals("system") && m.content().contains("[PLAN]"));
    }

    @Test
    void planningSkippedWhenToolsDisabled() {
        CapturingProvider provider = new CapturingProvider(
                List.of(Mono.just(JSON_PLAN), Mono.just("好的")),
                Flux.just(ModelEvent.token("好的"), ModelEvent.done(Map.of())));

        AgentRequestContext noTools = new AgentRequestContext(
                "u1", COMPLEX_MESSAGE, null, null, null, null,
                false, true, null, Map.of());
        StepVerifier.create(orchestrator(provider).stream(noTools))
                .expectNextMatches(e -> e.type().equals("token"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        // 规划未发生（useTools=false 直接跳过，第 1 次调用是执行轮）
        assertThat(provider.turns).hasSize(1);
    }
}
