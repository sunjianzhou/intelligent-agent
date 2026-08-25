package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-01 历史压缩测试（窗口滚动 / 摘要注入 / 无摘要降级）：
 * 超预算且有摘要 → 滚动保留最近消息并注入摘要；无摘要 → 铁律不裁剪。
 */
class ConversationMemoryServiceCompactionTest {

    private final VectorMemoryRepository repository = new VectorMemoryRepository();
    private final SemanticResponseCache cache = new SemanticResponseCache();
    private final MemoryDistillationService distiller = new MemoryDistillationService();
    private final ConversationMemoryService service =
            new ConversationMemoryService(repository, cache, distiller, Runnable::run);

    private final ContextBudget budget = new ContextBudget(1024, Map.of());
    private final ContextBudget.Plan plan = budget.plan("qwen2.5:7b", Map.of());

    private static List<ChatMessage> longHistory(int turns) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            history.add(ChatMessage.user("第" + i + "轮用户的陈述内容用于占满上下文预算测试"));
            history.add(ChatMessage.assistant("回答内容第" + i + "轮也尽量写长一些以便快速超过预算"));
        }
        return history;
    }

    private static AgentRequestContext ctx() {
        return new AgentRequestContext(
                "u1", "最近的问题", null, null, null, null, true, true, null, Map.of());
    }

    @Test
    void shortHistoryStaysUntouched() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("你好"), ChatMessage.assistant("你好呀"));

        ConversationMemoryService.CompactionResult result =
                service.compactHistory(ctx(), history, plan);

        assertThat(result.dropped()).isZero();
        assertThat(result.summaryUsed()).isFalse();
        assertThat(result.overBudgetNoSummary()).isFalse();
        assertThat(result.history()).hasSize(2);
    }

    @Test
    void rollingWindowKeepsRecentMessagesAndInjectsSummary() {
        repository.upsert(new MemoryRecord(
                "sum-1", "u1", "会话摘要: 用户早期陈述偏好喝茶", null, null, "summary",
                Map.of("source", "session_summary"), 0.6));
        List<ChatMessage> history = longHistory(80);
        ChatMessage last = history.get(history.size() - 1);

        ConversationMemoryService.CompactionResult result =
                service.compactHistory(ctx(), history, plan);

        assertThat(result.dropped()).isGreaterThan(0);
        assertThat(result.summaryUsed()).isTrue();
        assertThat(result.history().get(0).role()).isEqualTo("system");
        assertThat(result.history().get(0).content()).contains("[RECENT SESSION SUMMARY]")
                .contains("偏好喝茶");
        // 最近的最后一条消息必须保留
        assertThat(result.history().get(result.history().size() - 1)).isEqualTo(last);
        // 压缩后所有消息估算不超历史预算
        assertThat(ContextBudget.estimateMessages(result.history()))
                .isLessThanOrEqualTo(plan.historyTokens());
        // 开头保持 user 角色（摘要后第一条）
        assertThat(result.history().get(1).role()).isEqualTo("user");
    }

    @Test
    void noSummaryMeansNoTrim() {
        List<ChatMessage> history = longHistory(80);

        ConversationMemoryService.CompactionResult result =
                service.compactHistory(ctx(), history, plan);

        assertThat(result.dropped()).isZero();
        assertThat(result.summaryUsed()).isFalse();
        assertThat(result.overBudgetNoSummary()).isTrue();
        assertThat(result.history()).hasSameSizeAs(history);
        assertThat(result.history()).containsExactlyElementsOf(history);
    }

    @Test
    void recentSummariesReturnsLatestSummaryByCreatedDesc() {
        repository.upsert(new MemoryRecord(
                "old", "u1", "旧摘要", null, null, "summary", Map.of(), 0.5));
        repository.upsert(new MemoryRecord(
                "new", "u1", "新摘要", null, null, "summary", Map.of(), 0.5));

        List<MemoryRecord> summaries = service.recentSummaries(ctx());

        assertThat(summaries).extracting(MemoryRecord::id).containsExactly("new", "old");
    }
}
