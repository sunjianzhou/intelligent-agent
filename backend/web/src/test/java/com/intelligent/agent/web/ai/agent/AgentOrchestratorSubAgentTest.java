package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.agent.planning.ExecutionPlan;
import com.intelligent.agent.web.ai.agent.planning.LlmTaskPlanner;
import com.intelligent.agent.web.ai.agent.subagent.SubAgentExecutor;
import com.intelligent.agent.web.ai.agent.subagent.SubAgentResult;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-07 编排器集成测试：计划分组 → 子代理并行执行 → 结果按序合并 → 主 agent 最终作答；
 * 子代理禁用/执行失败时降级为原 [PLAN] 注入路径。
 */
class AgentOrchestratorSubAgentTest {

    /** 按全消息内容标记脚本化回复（并发安全）。 */
    static class ScriptedProvider implements LlmProvider {
        final List<ChatTurn> completeTurns = Collections.synchronizedList(new ArrayList<>());
        final List<ChatTurn> streamTurns = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> repliesByMarker;
        final String defaultReply;
        final Flux<ModelEvent> streamEvents;

        ScriptedProvider(Map<String, String> repliesByMarker, String defaultReply,
                         Flux<ModelEvent> streamEvents) {
            this.repliesByMarker = repliesByMarker;
            this.defaultReply = defaultReply;
            this.streamEvents = streamEvents;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            streamTurns.add(turn);
            return streamEvents;
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            completeTurns.add(turn);
            String joined = joinMessages(turn);
            for (Map.Entry<String, String> entry : repliesByMarker.entrySet()) {
                if (joined.contains(entry.getKey())) {
                    return Mono.just(entry.getValue());
                }
            }
            return Mono.just(defaultReply);
        }

        private static String joinMessages(ChatTurn turn) {
            StringBuilder sb = new StringBuilder();
            for (ChatMessage message : turn.messages()) {
                sb.append(message.content()).append('\n');
            }
            return sb.toString();
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

    private static final String GROUPED_PLAN =
            "{\"steps\":[{\"title\":\"查天气\",\"group\":1},"
                    + "{\"title\":\"算着装\",\"group\":1},{\"title\":\"汇总\",\"group\":2}]}";

    private AgentRequestContext ctx() {
        return AgentRequestContext.of("u1", COMPLEX_MESSAGE);
    }

    private SubAgentExecutor subAgentExecutor(LlmProviderRouter router, boolean enabled,
                                              int poolSize) {
        return new SubAgentExecutor(router, new ToolExecutor(List.of(new EchoTool())),
                null, null, null, enabled, poolSize, 32, Duration.ofSeconds(5), 2, 500,
                List.of());
    }

    private AgentOrchestrator orchestrator(ScriptedProvider provider,
                                           SubAgentExecutor subAgentExecutor) {
        LlmProviderRouter router = new LlmProviderRouter(provider, null, List.of());
        LlmTaskPlanner planner = new LlmTaskPlanner(router);
        return new AgentOrchestrator(router, new ToolExecutor(List.of(new EchoTool())),
                null, null, null, AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS, null, null,
                planner, null, null, null, null, subAgentExecutor);
    }

    @Test
    void streamDispatchesSubAgentsAndMergesResultsIntoFinalAnswer() {
        ScriptedProvider provider = new ScriptedProvider(Map.of(
                "查天气", "天气结果",
                "算着装", "着装结果",
                "汇总", "汇总完成",
                "[SUBAGENT RESULTS]", "综合：天气结果 + 着装结果，汇总完成"),
                GROUPED_PLAN,
                Flux.just(ModelEvent.token("综合：天气结果 + 着装结果，汇总完成"),
                        ModelEvent.done(Map.of())));
        LlmProviderRouter router = new LlmProviderRouter(provider, null, List.of());
        AgentOrchestrator orchestrator = orchestrator(provider, subAgentExecutor(router, true, 2));

        StepVerifier.create(orchestrator.stream(ctx()))
                .expectNextMatches(e -> e.type().equals("plan")
                        && e.data() instanceof ExecutionPlan plan
                        && plan.steps().size() == 3)
                .expectNextMatches(e -> e.type().equals("token")
                        && e.data().equals("综合：天气结果 + 着装结果，汇总完成"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        // 规划 1 次 + 3 个子代理
        assertThat(provider.completeTurns).hasSize(4);
        // 最终答案流只发生一次，且消息中带 [SUBAGENT RESULTS] 合并块
        assertThat(provider.streamTurns).hasSize(1);
        ChatTurn finalTurn = provider.streamTurns.get(0);
        String joined = finalTurn.messages().stream()
                .map(ChatMessage::content).reduce("", String::concat);
        assertThat(joined).contains("[SUBAGENT RESULTS]")
                .contains("天气结果").contains("着装结果").contains("汇总完成");
    }

    @Test
    void completeMergesSubAgentResultsAndProducesFinalAnswer() {
        Map<String, String> replies = new java.util.LinkedHashMap<>();
        replies.put("[SUBAGENT RESULTS]", "综合答案"); // 最具体标记优先
        replies.put("查天气", "天气结果");
        replies.put("算着装", "着装结果");
        replies.put("汇总", "汇总完成");
        ScriptedProvider provider = new ScriptedProvider(replies,
                GROUPED_PLAN, Flux.empty());
        LlmProviderRouter router = new LlmProviderRouter(provider, null, List.of());
        AgentOrchestrator orchestrator = orchestrator(provider, subAgentExecutor(router, true, 2));

        String answer = orchestrator.complete(ctx()).block(Duration.ofSeconds(10));

        assertThat(answer).isEqualTo("综合答案");
        assertThat(provider.completeTurns).hasSize(5); // 规划 + 3 子代理 + 最终作答
    }

    @Test
    void fallsBackToPlanInjectionWhenSubAgentDisabled() {
        ScriptedProvider provider = new ScriptedProvider(Map.of(
                "[PLAN]", "已按计划执行完毕"),
                GROUPED_PLAN,
                Flux.just(ModelEvent.token("已按计划执行完毕"), ModelEvent.done(Map.of())));
        LlmProviderRouter router = new LlmProviderRouter(provider, null, List.of());
        AgentOrchestrator orchestrator = orchestrator(provider, subAgentExecutor(router, false, 2));

        StepVerifier.create(orchestrator.stream(ctx()))
                .expectNextMatches(e -> e.type().equals("plan"))
                .expectNextMatches(e -> e.type().equals("token")
                        && e.data().equals("已按计划执行完毕"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(provider.completeTurns).hasSize(2); // 规划 + 主线程执行轮
        assertThat(provider.completeTurns.get(1).messages())
                .anyMatch(m -> m.content().contains("[PLAN]"));
        assertThat(provider.streamTurns).isEmpty();
    }

    @Test
    void fallsBackToPlanExecutionWhenSubAgentExecutorThrows() {
        ScriptedProvider provider = new ScriptedProvider(Map.of(
                "[PLAN]", "已按计划执行完毕"),
                GROUPED_PLAN,
                Flux.just(ModelEvent.token("已按计划执行完毕"), ModelEvent.done(Map.of())));
        LlmProviderRouter router = new LlmProviderRouter(provider, null, List.of());
        SubAgentExecutor throwing = new SubAgentExecutor(
                router, new ToolExecutor(List.of(new EchoTool())), null, null, null,
                true, 2, 32, Duration.ofSeconds(5), 2, 500, List.of()) {
            @Override
            public List<SubAgentResult> execute(AgentRequestContext parent, ExecutionPlan plan,
                                                String traceId) {
                throw new RuntimeException("sub-agent pool unavailable");
            }
        };
        AgentOrchestrator orchestrator = orchestrator(provider, throwing);

        StepVerifier.create(orchestrator.stream(ctx()))
                .expectNextMatches(e -> e.type().equals("plan"))
                .expectNextMatches(e -> e.type().equals("token")
                        && e.data().equals("已按计划执行完毕"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(provider.completeTurns).hasSize(2);
        assertThat(provider.completeTurns.get(1).messages())
                .anyMatch(m -> m.content().contains("[PLAN]"));
    }
}
