package com.intelligent.agent.web.ai.agent;

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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentOrchestrator Task 4 行为测试：
 * 任务标记事件、分支失败停止、多模态图片透传。
 */
class AgentOrchestratorTaskAndBranchTest {

    /** 捕获每次 complete 的 ChatTurn。 */
    static class CapturingProvider implements LlmProvider {
        final List<String> replies;
        final AtomicReference<ChatTurn> lastTurn = new AtomicReference<>();
        final CopyOnWriteArrayList<ChatTurn> allTurns = new CopyOnWriteArrayList<>();

        CapturingProvider(List<String> replies) {
            this.replies = replies;
        }

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.just(ModelEvent.token("ok"), ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            lastTurn.set(turn);
            allTurns.add(turn);
            int index = Math.min(allTurns.size() - 1, replies.size() - 1);
            return Mono.just(replies.get(index));
        }
    }

    static class FailTool implements AgentTool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("fail", "总是失败", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            throw new IllegalStateException("connection refused");
        }
    }

    private final ToolExecutor failingTools = new ToolExecutor(List.of(new FailTool()));

    @Test
    void emitsTaskEventsAndStripsSentinelsFromStream() {
        CapturingProvider provider = new CapturingProvider(List.of(
                "完成 [TASK_DONE:task-001] 了，[TASK_BLOCKED:task-002] 无法继续"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), new ToolExecutor(List.of()));
        AgentRequestContext ctx = new AgentRequestContext(
                "u1", "hi", null, null, "proj-1", null, true, true, "web", Map.of(),
                null, null, false);

        StepVerifier.create(orchestrator.stream(ctx))
                .expectNextMatches(e -> e.type().equals("token")
                        && !String.valueOf(e.data()).contains("TASK_DONE"))
                .expectNextMatches(e -> e.type().equals("task_update"))
                .expectNextMatches(e -> e.type().equals("task_blocked"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void completeStripsTaskSentinels() {
        CapturingProvider provider = new CapturingProvider(List.of(
                "好的 [TASK_DONE:task-9]"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), new ToolExecutor(List.of()));

        StepVerifier.create(orchestrator.complete(AgentRequestContext.of("u1", "hi")))
                .expectNext("好的")
                .verifyComplete();
    }

    @Test
    void stopsLoopOnSameToolErrorThreeTimes() {
        CapturingProvider provider = new CapturingProvider(List.of(
                "<tool_call>{\"tool\": \"fail\", \"args\": {}}</tool_call>",
                "<tool_call>{\"tool\": \"fail\", \"args\": {}}</tool_call>",
                "<tool_call>{\"tool\": \"fail\", \"args\": {}}</tool_call>",
                "不应到达"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), failingTools);

        String answer = orchestrator.complete(AgentRequestContext.of("u1", "go")).block();

        assertThat(answer).contains("分支失败").contains("same_tool_same_error");
        assertThat(provider.allTurns.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void passesImageToProviderTurn() {
        CapturingProvider provider = new CapturingProvider(List.of("ok"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), new ToolExecutor(List.of()));
        AgentRequestContext ctx = new AgentRequestContext(
                "u1", "看图", null, null, null, null, true, true, "web", Map.of(),
                "aGVsbG8=", null, false);

        orchestrator.complete(ctx).block();

        assertThat(provider.lastTurn.get().images()).containsExactly("aGVsbG8=");
    }
}
