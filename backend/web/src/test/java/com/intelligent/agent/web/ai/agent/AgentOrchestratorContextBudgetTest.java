package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.memory.ContextBudget;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-01 编排器上下文预算测试：200 条消息长会话请求不超窗口、摘要注入、
 * 无摘要时不裁剪历史（宁可超窗告警）。
 */
class AgentOrchestratorContextBudgetTest {

    /** 捕获每次发给 LLM 的 turn，用于断言请求体 token 预算。 */
    static class CaptureProvider implements LlmProvider {
        final List<ChatTurn> turns = new ArrayList<>();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            turns.add(turn);
            return Flux.just(ModelEvent.token("ok"), ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            turns.add(turn);
            return Mono.just("ok");
        }
    }

    private final CaptureProvider provider = new CaptureProvider();
    private final VectorMemoryRepository repository = new VectorMemoryRepository();
    private final ConversationMemoryService memoryService =
            new ConversationMemoryService(repository, new SemanticResponseCache(),
                    new MemoryDistillationService(), task -> { /* 丢弃后台蒸馏/摘要任务，保持确定性 */ });
    private final ContextBudget budget = new ContextBudget(1024, Map.of());

    private AgentOrchestrator orchestrator() {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of()), memoryService, null, null, 5,
                null, null, null, null, null, null, budget);
    }

    private static void recordTurns(ConversationMemoryService service, int turns) {
        for (int i = 0; i < turns; i++) {
            service.recordTurn(
                    new AgentRequestContext("u1", "第" + i + "轮用户的陈述内容用于构造长会话",
                            null, null, null, null, true, true, null, Map.of()),
                    "回答" + i);
        }
    }

    private static AgentRequestContext ask() {
        return new AgentRequestContext(
                "u1", "最近的问题是什么", null, null, null, null, true, true, null, Map.of());
    }

    @Test
    void twoHundredMessageConversationStaysWithinWindowAndKeepsKeyContext() {
        recordTurns(memoryService, 200);
        repository.upsert(new MemoryRecord(
                "sum-1", "u1", "会话摘要: 用户早期陈述偏好喝茶", null, null, "summary",
                Map.of("source", "session_summary"), 0.6));
        ContextBudget.Plan plan = budget.plan(null, Map.of());

        String answer = orchestrator().complete(ask()).block();

        assertThat(answer).isEqualTo("ok");
        assertThat(provider.turns).hasSize(1);
        List<ChatMessage> messages = provider.turns.get(0).messages();
        // 请求体估算不超可用预算（即不超 num_ctx 窗口）
        assertThat(ContextBudget.estimateMessages(messages))
                .isLessThanOrEqualTo(plan.usableTokens());
        // 摘要注入
        assertThat(messages).extracting(ChatMessage::content)
                .anySatisfy(content -> {
                    assertThat(content).contains("[RECENT SESSION SUMMARY]");
                    assertThat(content).contains("偏好喝茶");
                });
        // 最旧消息被滚动丢弃，最近消息保留
        assertThat(messages).extracting(ChatMessage::content)
                .doesNotContain("第0轮用户的陈述内容用于构造长会话");
        assertThat(messages.get(messages.size() - 2).content()).isEqualTo("回答199");
        // 当前用户消息始终保留
        assertThat(messages.get(messages.size() - 1).content()).isEqualTo("最近的问题是什么");
    }

    @Test
    void noSummaryMeansHistoryNotTrimmed() {
        recordTurns(memoryService, 200);

        String answer = orchestrator().complete(ask()).block();

        assertThat(answer).isEqualTo("ok");
        assertThat(provider.turns).hasSize(1);
        List<ChatMessage> messages = provider.turns.get(0).messages();
        // 短期记忆 deque 上限 100 条：无摘要时 100 条历史全量保留 + 当前消息
        // （宁可超窗，不静默丢上下文；压缩仅在有摘要时滚动）
        assertThat(messages).hasSize(101);
        assertThat(messages).extracting(ChatMessage::content)
                .contains("第150轮用户的陈述内容用于构造长会话", "回答199");
        // 超出预算但保留全部：这是降级铁律的预期行为
        assertThat(ContextBudget.estimateMessages(messages))
                .isGreaterThan(budget.plan(null, Map.of()).usableTokens());
    }
}
