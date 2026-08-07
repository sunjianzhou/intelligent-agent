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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 ReAct 编排测试：无工具事件顺序（token → done）、工具轮执行、
 * 最多 5 轮工具调用上限、非流式完整回复。
 */
class AgentOrchestratorTest {

    /** 每次 complete() 依次返回 replies 中的文本；stream() 固定返回 streamEvents。 */
    static class FakeProvider implements LlmProvider {
        final List<String> replies;
        final Flux<ModelEvent> streamEvents;
        final int[] completeCalls = {0};

        FakeProvider(List<String> replies, Flux<ModelEvent> streamEvents) {
            this.replies = replies;
            this.streamEvents = streamEvents;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return streamEvents;
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            completeCalls[0]++;
            String reply = replies.get(Math.min(completeCalls[0] - 1, replies.size() - 1));
            return Mono.just(reply);
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

    private final FakeProvider fake = new FakeProvider(
            List.of("你好"),
            Flux.just(ModelEvent.token("你好"), ModelEvent.done(Map.of())));
    private final ToolExecutor tools = new ToolExecutor(List.of(new EchoTool()));
    private final AgentOrchestrator orchestrator =
            new AgentOrchestrator(new LlmProviderRouter(fake, null, List.of()), tools);

    @Test
    void streamsTokenThenDoneWithoutTools() {
        StepVerifier.create(orchestrator.stream(AgentRequestContext.of("u1", "你好")))
                .expectNextMatches(e -> e.type().equals("token"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void executesToolRoundsThenStreamsFinalAnswer() {
        FakeProvider toolFake = new FakeProvider(
                List.of(
                        "<tool_call>{\"tool\": \"echo\", \"args\": {\"text\": \"hi\"}}</tool_call>",
                        "结果是 hi"),
                Flux.just(ModelEvent.token("结果是 hi"), ModelEvent.done(Map.of())));
        AgentOrchestrator o =
                new AgentOrchestrator(new LlmProviderRouter(toolFake, null, List.of()), tools);

        StepVerifier.create(o.stream(AgentRequestContext.of("u1", "请 echo hi")))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("结果是 hi"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
        assertThat(toolFake.completeCalls[0]).isEqualTo(2);
    }

    @Test
    void stopsAfterFiveToolRounds() {
        FakeProvider loopFake = new FakeProvider(
                List.of("<tool_call>{\"tool\": \"echo\", \"args\": {\"text\": \"x\"}}</tool_call>"),
                Flux.just(ModelEvent.token("ok"), ModelEvent.done(Map.of())));
        AgentOrchestrator o =
                new AgentOrchestrator(new LlmProviderRouter(loopFake, null, List.of()), tools);

        StepVerifier.create(o.stream(AgentRequestContext.of("u1", "loop")))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
        assertThat(loopFake.completeCalls[0]).isEqualTo(5);
    }

    @Test
    void completeReturnsFinalTextWithoutTools() {
        StepVerifier.create(orchestrator.complete(AgentRequestContext.of("u1", "你好")))
                .expectNext("你好")
                .verifyComplete();
    }
}
