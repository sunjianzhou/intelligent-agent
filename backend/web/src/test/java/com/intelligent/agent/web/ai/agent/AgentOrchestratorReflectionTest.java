package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.agent.reflection.LlmAnswerReflector;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6 reflection 后验编排集成测试：工具执行后答案自检修订、失败保留草稿、无工具不触发。
 */
class AgentOrchestratorReflectionTest {

    /** 工具轮与自检轮分离的脚本 provider。 */
    static class ScriptedProvider implements LlmProvider {
        final List<Mono<LlmResponse>> toolRounds;
        final Mono<String> reflectionReply;
        final List<ChatTurn> turns = new ArrayList<>();
        final int[] toolCalls = {0};
        final int[] reflectionCalls = {0};

        ScriptedProvider(List<Mono<LlmResponse>> toolRounds, Mono<String> reflectionReply) {
            this.toolRounds = toolRounds;
            this.reflectionReply = reflectionReply;
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
            reflectionCalls[0]++;
            return reflectionReply;
        }

        @Override
        public Mono<LlmResponse> completeWithTools(ChatTurn turn,
                                                   List<ToolDefinition> tools) {
            turns.add(turn);
            int idx = Math.min(toolCalls[0]++, toolRounds.size() - 1);
            return toolRounds.get(idx);
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

    private static final String REQUEST = "请 echo hi 然后给出结论";
    private static final ToolCall NATIVE_CALL = ToolCall.of("echo", Map.of("text", "hi"));
    private static final String DRAFT = "草稿答案";

    private AgentOrchestrator orchestrator(ScriptedProvider provider) {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(new EchoTool())),
                null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS, null, null,
                null, new LlmAnswerReflector(
                        new LlmProviderRouter(provider, null, List.of())));
    }

    @Test
    void streamReflectsToolAnswerAndEmitsRevisedText() {
        ScriptedProvider provider = new ScriptedProvider(
                List.of(
                        Mono.just(new LlmResponse("", List.of(NATIVE_CALL))),
                        Mono.just(new LlmResponse(DRAFT, List.of()))),
                Mono.just("echo:hi，结论已完成"));

        StepVerifier.create(orchestrator(provider).stream(AgentRequestContext.of("u1", REQUEST)))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token")
                        && e.data().equals("echo:hi，结论已完成"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        // 第 3 次调用是自检：complete() 只被调用一次，且提示词包含草稿与工具结果
        assertThat(provider.reflectionCalls[0]).isEqualTo(1);
        assertThat(provider.turns).hasSize(3);
        String user = provider.turns.get(2).messages().get(1).content();
        assertThat(user).contains(DRAFT).contains("echo:hi").contains(REQUEST);
    }

    @Test
    void streamKeepsDraftWhenReflectionFails() {
        ScriptedProvider provider = new ScriptedProvider(
                List.of(
                        Mono.just(new LlmResponse("", List.of(NATIVE_CALL))),
                        Mono.just(new LlmResponse(DRAFT, List.of()))),
                Mono.error(new RuntimeException("model down")));

        StepVerifier.create(orchestrator(provider).stream(AgentRequestContext.of("u1", REQUEST)))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals(DRAFT))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void streamKeepsDraftWhenReflectionReturnsBlank() {
        ScriptedProvider provider = new ScriptedProvider(
                List.of(
                        Mono.just(new LlmResponse("", List.of(NATIVE_CALL))),
                        Mono.just(new LlmResponse(DRAFT, List.of()))),
                Mono.just("   "));

        StepVerifier.create(orchestrator(provider).stream(AgentRequestContext.of("u1", REQUEST)))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals(DRAFT))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void streamSkipsReflectionWithoutTools() {
        ScriptedProvider provider = new ScriptedProvider(
                List.of(Mono.just(new LlmResponse("你好", List.of()))),
                Mono.just("不应被调用"));

        StepVerifier.create(orchestrator(provider).stream(AgentRequestContext.of("u1", "你好")))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("你好"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(provider.reflectionCalls[0]).isZero();
    }

    @Test
    void completeReflectsToolAnswer() {
        ScriptedProvider provider = new ScriptedProvider(
                List.of(
                        Mono.just(new LlmResponse("", List.of(NATIVE_CALL))),
                        Mono.just(new LlmResponse(DRAFT, List.of()))),
                Mono.just("echo:hi，结论已完成"));

        String answer = orchestrator(provider).complete(AgentRequestContext.of("u1", REQUEST))
                .block(Duration.ofSeconds(5));

        assertThat(answer).isEqualTo("echo:hi，结论已完成");
        assertThat(provider.reflectionCalls[0]).isEqualTo(1);
    }
}
