package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TODO-110 Task 5：记忆蒸馏 LLM 提取 + 规则式兜底 + 项目上下文提取。
 */
class MemoryDistillationServiceTest {

    private final VectorMemoryRepository repository = new VectorMemoryRepository();

    @Test
    void ruleBasedExtractionWithoutLlm() {
        MemoryDistillationService distiller = new MemoryDistillationService();

        List<String> facts = distiller.extractFacts(List.of(
                ChatMessage.user("用户喜欢喝绿茶"),
                ChatMessage.assistant("好的"),
                ChatMessage.user("hi")));

        assertThat(facts).containsExactly("用户喜欢喝绿茶");
    }

    @Test
    void llmExtractionReplacesRawMessages() {
        MemoryDistillationService distiller = distillationWith(
                new FakeProvider("用户喜欢喝茶\n用户住在上海"));

        List<String> facts = distiller.extractFacts("u1", null, List.of(
                ChatMessage.user("我今天泡了龙井茶，平时住上海")));

        assertThat(facts).containsExactly("用户喜欢喝茶", "用户住在上海");
    }

    @Test
    void fallsBackToRulesWhenLlmFails() {
        MemoryDistillationService distiller = distillationWith(new FailingProvider());

        List<String> facts = distiller.extractFacts("u1", null, List.of(
                ChatMessage.user("用户喜欢喝绿茶")));

        assertThat(facts).containsExactly("用户喜欢喝绿茶");
    }

    @Test
    void disabledLlmUsesRules() {
        LlmExtractionService llm = new LlmExtractionService(
                router(new FakeProvider("用户喜欢喝茶")), "qwen2.5:7b",
                Duration.ofSeconds(5), false);
        MemoryDistillationService distiller =
                new MemoryDistillationService(5, 10, 8, llm);

        List<String> facts = distiller.extractFacts("u1", null, List.of(
                ChatMessage.user("用户喜欢喝绿茶")));

        assertThat(facts).containsExactly("用户喜欢喝绿茶");
    }

    @Test
    void extractProjectContextWritesProjectRecords() {
        MemoryDistillationService distiller = distillationWith(
                new FakeProvider("项目决定采用 Java 21\n里程碑：下周一上线"));

        distiller.extractProjectContext("u1", "p1", List.of(
                ChatMessage.user("我们定了用 Java 21 做后端")), repository);

        List<MemoryRecord> records = repository.search(
                MemorySearchQuery.builder("u1", "Java 21", 20)
                        .projectId("p1")
                        .type("project")
                        .build());
        assertThat(records).isNotEmpty();
        assertThat(records)
                .extracting(MemoryRecord::content)
                .anyMatch(content -> content.contains("Java 21"));
        assertThat(records).allMatch(record -> "p1".equals(record.projectId()));
    }

    @Test
    void distillWritesFactsThroughLlm() {
        MemoryDistillationService distiller = distillationWith(
                new FakeProvider("用户偏好深色模式"));

        int stored = distiller.distill("u1", null, List.of(
                ChatMessage.user("界面请用深色模式")), repository);

        assertThat(stored).isEqualTo(1);
        List<MemoryRecord> facts = repository.search(
                MemorySearchQuery.builder("u1", "深色", 20).type("fact").build());
        assertThat(facts).isNotEmpty();
        assertThat(facts.get(0).content()).contains("深色模式");
    }

    private static MemoryDistillationService distillationWith(LlmProvider provider) {
        LlmExtractionService llm = new LlmExtractionService(
                router(provider), "qwen2.5:7b", Duration.ofSeconds(5), true);
        return new MemoryDistillationService(5, 10, 8, llm);
    }

    private static LlmProviderRouter router(LlmProvider provider) {
        return new LlmProviderRouter(provider, null, List.of());
    }

    private static final class FakeProvider implements LlmProvider {
        private final String answer;

        FakeProvider(String answer) {
            this.answer = answer;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.empty();
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            return Mono.just(answer);
        }
    }

    private static final class FailingProvider implements LlmProvider {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.empty();
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            return Mono.error(new LlmProviderException("boom"));
        }
    }
}
