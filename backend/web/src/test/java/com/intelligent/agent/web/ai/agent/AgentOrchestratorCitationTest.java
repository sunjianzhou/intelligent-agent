package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-05 RAG 引用溯源测试：知识问答返回 citation 事件（含来源标注）、
 * 上下文注入 [SOURCE] 标注与作答约束、非 knowledge 召回无引用。
 */
class AgentOrchestratorCitationTest {

    static class CapturingProvider implements LlmProvider {
        final List<ChatTurn> turns = new ArrayList<>();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.just(ModelEvent.token("安装步骤是……"), ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            turns.add(turn);
            return Mono.just("安装步骤是……");
        }

        @Override
        public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
            turns.add(turn);
            return Mono.just(new LlmResponse("安装步骤是……", List.of()));
        }
    }

    private final VectorMemoryRepository repository = new VectorMemoryRepository();
    private final ConversationMemoryService memoryService =
            new ConversationMemoryService(repository, new SemanticResponseCache(),
                    new MemoryDistillationService(), task -> { });
    private final CapturingProvider provider = new CapturingProvider();

    private AgentOrchestrator orchestrator() {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of()), memoryService, null, null, 5,
                null, null, null, null, null, null);
    }

    private static AgentRequestContext ask(String message) {
        return new AgentRequestContext(
                "u1", message, null, null, null, null, true, true, null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<ModelEvent> run(AgentOrchestrator orchestrator, String message) {
        return orchestrator.stream(ask(message)).collectList().block(Duration.ofSeconds(5));
    }

    @Test
    void knowledgeAnswerEmitsCitationAndInjectsSourceAnnotation() {
        repository.upsert(new MemoryRecord(
                "k1", "u1", "安装步骤：先安装 JDK 21 再配置环境变量", null, null, "knowledge",
                Map.of("file_id", "f1", "filename", "产品手册.md", "chunk_index", 2), 0.7));

        List<ModelEvent> events = run(orchestrator(), "安装步骤是什么");

        assertThat(events).extracting(ModelEvent::type).contains("citation");
        Map<String, Object> citation = (Map<String, Object>) events.stream()
                .filter(e -> "citation".equals(e.type()))
                .map(ModelEvent::data).findFirst().orElseThrow();
        assertThat(citation.get("label")).isEqualTo("产品手册.md#段落2");
        assertThat(citation.get("file_id")).isEqualTo("f1");
        assertThat(citation.get("chunk_index")).isEqualTo(2);

        // 上下文注入 [SOURCE] 标注 + 基于引用作答的约束
        List<ChatMessage> messages = provider.turns.get(0).messages();
        assertThat(messages).anyMatch(m -> m.role().equals("system")
                && m.content().contains("[SOURCE: 产品手册.md#段落2]")
                && m.content().contains("基于上方引用作答"));
    }

    @Test
    void nonKnowledgeRecallHasNoCitation() {
        repository.upsert(new MemoryRecord(
                "f1", "u1", "用户喜欢喝茶", null, null, "fact", Map.of(), 0.9));

        List<ModelEvent> events = run(orchestrator(), "用户喜欢什么");

        assertThat(events).extracting(ModelEvent::type).doesNotContain("citation");
        assertThat(provider.turns.get(0).messages())
                .noneMatch(m -> m.role().equals("system") && m.content().contains("[SOURCE:"));
    }

    @Test
    void knowledgeWithoutSourceMetadataHasNoCitation() {
        repository.upsert(new MemoryRecord(
                "k2", "u1", "公司成立于 2024 年", null, null, "knowledge",
                Map.of("description", "无来源元数据"), 0.7));

        List<ModelEvent> events = run(orchestrator(), "公司成立于哪年");

        assertThat(events).extracting(ModelEvent::type).doesNotContain("citation");
    }
}
