package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.memory.AgentContext;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 分层记忆编排测试：系统提示按 [EPISODIC MEMORY] / [SEMANTIC MEMORY] 分段注入，
 * 旧 longTermRecall 上下文走 [LONG-TERM MEMORY] 兼容路径。
 */
class AgentOrchestratorLayeredMemoryTest {

    static class CapturingProvider implements LlmProvider {
        final List<ChatTurn> turns = new ArrayList<>();

        @Override
        public String name() {
            return "capturing";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.empty();
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            turns.add(turn);
            return Mono.just("你好");
        }

        @Override
        public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
            turns.add(turn);
            return Mono.just(new LlmResponse("你好", List.of()));
        }
    }

    /** 返回预置上下文的记忆服务（模拟不同形态的 loadContext）。 */
    static class StubMemoryService extends ConversationMemoryService {
        private final AgentContext context;

        StubMemoryService(AgentContext context) {
            super(null, null, null);
            this.context = context;
        }

        @Override
        public AgentContext loadContext(AgentRequestContext ctx) {
            return context;
        }

        @Override
        public void recordTurn(AgentRequestContext ctx, String answer) {
            // no-op
        }
    }

    private AgentOrchestrator orchestrator(CapturingProvider provider,
                                           ConversationMemoryService memory) {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of()),
                memory, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS);
    }

    @Test
    void initialMessagesSeparateEpisodicAndSemanticSections() {
        VectorMemoryRepository repository = new VectorMemoryRepository();
        repository.upsert(new MemoryRecord(
                "s1", "u1", "会话摘要: 昨天讨论了迁移", null, null, "summary",
                Map.of(), 0.95));
        repository.upsert(new MemoryRecord(
                "f1", "u1", "事实: 用户喜欢咖啡", null, null, "fact",
                Map.of(), 0.95));
        ConversationMemoryService memory = new ConversationMemoryService(
                repository, new SemanticResponseCache(), new MemoryDistillationService());
        CapturingProvider provider = new CapturingProvider();

        orchestrator(provider, memory).stream(AgentRequestContext.of("u1", "聊了什么"))
                .blockLast(Duration.ofSeconds(5));

        List<ChatMessage> messages = provider.turns.get(0).messages();
        assertThat(messages).anyMatch(m -> m.role().equals("system")
                && m.content().startsWith("[EPISODIC MEMORY]"));
        assertThat(messages).anyMatch(m -> m.role().equals("system")
                && m.content().startsWith("[SEMANTIC MEMORY]"));
        assertThat(messages).noneMatch(m -> m.role().equals("system")
                && m.content().startsWith("[LONG-TERM MEMORY]"));
    }

    @Test
    void fallsBackToLegacySectionWhenContextHasOnlyMergedRecall() {
        AgentContext legacy = new AgentContext(
                List.of(),
                List.of(new MemoryRecord("m1", "u1", "旧格式记忆", Map.of(), 0.9)),
                "", Optional.empty());
        CapturingProvider provider = new CapturingProvider();

        orchestrator(provider, new StubMemoryService(legacy))
                .stream(AgentRequestContext.of("u1", "你好")).blockLast(Duration.ofSeconds(5));

        List<ChatMessage> messages = provider.turns.get(0).messages();
        assertThat(messages).anyMatch(m -> m.role().equals("system")
                && m.content().startsWith("[LONG-TERM MEMORY]"));
    }
}
