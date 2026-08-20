package com.intelligent.agent.web.ai.agent.reflection;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 答案自检器测试：修订草稿、空白/失败/禁用时保留草稿、提示词上下文完整。
 */
class LlmAnswerReflectorTest {

    static class ScriptedProvider implements LlmProvider {
        final Mono<String> reply;
        final List<ChatTurn> turns = new ArrayList<>();
        final int[] calls = {0};

        ScriptedProvider(Mono<String> reply) {
            this.reply = reply;
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
            calls[0]++;
            return reply;
        }
    }

    private static final String DRAFT = "17 × 23 = 5";
    private static final String REQUEST = "请计算 17 × 23";
    private static final List<String> TOOL_RESULTS = List.of("[工具「calculator」返回 · 不可信数据] 391");
    private static final List<String> PLAN_STEPS = List.of("计算乘积", "给出答案");

    private LlmAnswerReflector reflector(ScriptedProvider provider) {
        return new LlmAnswerReflector(new LlmProviderRouter(provider, null, List.of()));
    }

    @Test
    void revisesDraftWhenModelReturnsImprovedAnswer() {
        ScriptedProvider provider = new ScriptedProvider(Mono.just("17 × 23 = 391"));

        String result = reflector(provider).reflect(
                AgentRequestContext.of("u1", REQUEST), DRAFT, TOOL_RESULTS, PLAN_STEPS);

        assertThat(result).isEqualTo("17 × 23 = 391");
    }

    @Test
    void returnsDraftWhenModelRepliesBlank() {
        ScriptedProvider provider = new ScriptedProvider(Mono.just("   "));

        String result = reflector(provider).reflect(
                AgentRequestContext.of("u1", REQUEST), DRAFT, TOOL_RESULTS, PLAN_STEPS);

        assertThat(result).isEqualTo(DRAFT);
    }

    @Test
    void returnsDraftOnModelError() {
        ScriptedProvider provider = new ScriptedProvider(
                Mono.error(new RuntimeException("model down")));

        String result = reflector(provider).reflect(
                AgentRequestContext.of("u1", REQUEST), DRAFT, TOOL_RESULTS, PLAN_STEPS);

        assertThat(result).isEqualTo(DRAFT);
    }

    @Test
    void disabledReturnsDraftWithoutLlmCall() {
        ScriptedProvider provider = new ScriptedProvider(Mono.just("修正"));
        LlmAnswerReflector disabled = new LlmAnswerReflector(
                new LlmProviderRouter(provider, null, List.of()), false, Duration.ofSeconds(30));

        String result = disabled.reflect(
                AgentRequestContext.of("u1", REQUEST), DRAFT, TOOL_RESULTS, PLAN_STEPS);

        assertThat(result).isEqualTo(DRAFT);
        assertThat(provider.calls[0]).isZero();
    }

    @Test
    void stripsCodeFencesFromRevisedAnswer() {
        ScriptedProvider provider = new ScriptedProvider(Mono.just("```text\n17 × 23 = 391\n```"));

        String result = reflector(provider).reflect(
                AgentRequestContext.of("u1", REQUEST), DRAFT, TOOL_RESULTS, PLAN_STEPS);

        assertThat(result).isEqualTo("17 × 23 = 391");
    }

    @Test
    void promptContainsRequestToolResultsAndDraft() {
        ScriptedProvider provider = new ScriptedProvider(Mono.just("修正"));
        reflector(provider).reflect(
                AgentRequestContext.of("u1", REQUEST), DRAFT, TOOL_RESULTS, PLAN_STEPS);

        assertThat(provider.turns).hasSize(1);
        ChatTurn turn = provider.turns.get(0);
        assertThat(turn.messages().get(0).role()).isEqualTo("system");
        String user = turn.messages().get(1).content();
        assertThat(user).contains(REQUEST).contains(DRAFT)
                .contains("calculator").contains("计算乘积")
                .contains("用户请求").contains("草稿答案");
        assertThat(turn.options()).containsEntry("temperature", 0.2);
    }
}
