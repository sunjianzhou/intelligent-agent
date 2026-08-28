package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** R-16：流式中断后以相同 requestId 重发 → 复用工具结果，不重复执行副作用工具。 */
class AgentOrchestratorCheckpointTest {

    static class CountingTool implements AgentTool {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("counter", "计数工具", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return "executed-" + executions.incrementAndGet();
        }
    }

    /** 无工具结果消息 → 返回原生工具调用；已带工具结果 → 返回最终文本。 */
    static class ToolThenAnswerProvider implements LlmProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "tool-then-answer";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.just(ModelEvent.token("完成"), ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            return Mono.just("完成");
        }

        @Override
        public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
            calls.incrementAndGet();
            boolean hasToolResult = turn.messages().stream()
                    .anyMatch(m -> "tool".equals(m.role()));
            if (hasToolResult) {
                return Mono.just(new LlmResponse("完成", List.of()));
            }
            return Mono.just(new LlmResponse("",
                    List.of(ToolCall.of("counter", Map.of("v", 1)))));
        }
    }

    private static AgentOrchestrator withCheckpoint(ToolThenAnswerProvider provider,
                                                    CountingTool tool,
                                                    ToolCheckpointStore store) {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(tool)),
                null, null, null, AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS,
                null, null, null, null, null, null, null, null, null,
                false, List.of(), store);
    }

    private static AgentRequestContext ctx(String requestId) {
        return new AgentRequestContext(
                "u1", "执行", null, null, null, null, true, true, "web", Map.of(),
                null, null, false, List.of(), requestId, null);
    }

    @Test
    void replaysToolResultOnRetryWithSameRequestId() {
        ToolThenAnswerProvider provider = new ToolThenAnswerProvider();
        CountingTool tool = new CountingTool();
        AgentOrchestrator orchestrator =
                withCheckpoint(provider, tool, new ToolCheckpointStore());
        AgentRequestContext ctx = ctx("req-replay");

        // 第一次：工具执行后客户端中断（tool_calls_done 之后）
        StepVerifier.create(orchestrator.stream(ctx))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .thenCancel()
                .verify();
        assertThat(tool.executions).hasValue(1);

        // 同 requestId 重发：工具结果从缓存复用，不再执行
        StepVerifier.create(orchestrator.stream(ctx))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("完成"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
        assertThat(tool.executions).hasValue(1);
    }

    @Test
    void differentRequestIdReexecutesTool() {
        ToolThenAnswerProvider provider = new ToolThenAnswerProvider();
        CountingTool tool = new CountingTool();
        AgentOrchestrator orchestrator =
                withCheckpoint(provider, tool, new ToolCheckpointStore());

        StepVerifier.create(orchestrator.stream(ctx("req-a"))).expectNextCount(3).verifyComplete();
        StepVerifier.create(orchestrator.stream(ctx("req-b"))).expectNextCount(3).verifyComplete();

        assertThat(tool.executions).hasValue(2);
    }

    @Test
    void successfulCompletionClearsCheckpoint() {
        ToolThenAnswerProvider provider = new ToolThenAnswerProvider();
        CountingTool tool = new CountingTool();
        ToolCheckpointStore store = new ToolCheckpointStore();
        AgentOrchestrator orchestrator = withCheckpoint(provider, tool, store);
        AgentRequestContext ctx = ctx("req-done");

        StepVerifier.create(orchestrator.stream(ctx)).expectNextCount(3).verifyComplete();

        // 完整跑完后断点已清理：同 id 重发会重新执行
        StepVerifier.create(orchestrator.stream(ctx)).expectNextCount(3).verifyComplete();
        assertThat(tool.executions).hasValue(2);
    }

    @Test
    void withoutRequestIdNoCaching() {
        ToolThenAnswerProvider provider = new ToolThenAnswerProvider();
        CountingTool tool = new CountingTool();
        AgentOrchestrator orchestrator =
                withCheckpoint(provider, tool, new ToolCheckpointStore());
        AgentRequestContext ctx = AgentRequestContext.of("u1", "执行");

        StepVerifier.create(orchestrator.stream(ctx))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .thenCancel()
                .verify();
        StepVerifier.create(orchestrator.stream(ctx)).expectNextCount(3).verifyComplete();

        assertThat(tool.executions).hasValue(2);
    }
}
