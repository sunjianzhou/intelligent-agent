package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆 / RAG / 语义缓存测试（Plan 2 / Task 2）：
 * 缓存键必须包含 persona 与 model；短期历史、长期检索、项目上下文、
 * 五轮蒸馏、十轮摘要、回写缓存。
 */
class ConversationMemoryServiceTest {

    private final VectorMemoryRepository repository = new VectorMemoryRepository();
    private final SemanticResponseCache cache = new SemanticResponseCache();
    private final MemoryDistillationService distiller = new MemoryDistillationService();
    private final ConversationMemoryService service =
            new ConversationMemoryService(repository, cache, distiller);

    // ── 语义缓存键隔离 ────────────────────────────────────────

    @Test
    void cacheKeyIncludesPersona() {
        cache.put("u", "writer", "q", "a");

        assertThat(cache.get("u", "coder", "q")).isEmpty();
    }

    @Test
    void cacheKeyIncludesModel() {
        cache.put("u", "writer", "qwen", "q", "a");

        assertThat(cache.get("u", "writer", "llama", "q")).isEmpty();
        assertThat(cache.get("u", "writer", "qwen", "q")).contains("a");
    }

    @Test
    void cacheReturnsHitForSameKey() {
        cache.put("u", "writer", "q", "a");

        assertThat(cache.get("u", "writer", "q")).contains("a");
    }

    @Test
    void cacheFindsSemanticallySimilarQuestion() {
        cache.put("u", "writer", "how to migrate the java backend", "answer");

        assertThat(cache.findSimilar("u", "writer", null,
                "how to migrate java backend", 0.6)).contains("answer");
    }

    // ── loadContext ───────────────────────────────────────────

    @Test
    void loadContextEmptyWhenMemoryDisabled() {
        AgentRequestContext noMemory = new AgentRequestContext(
                "u1", "hi", null, null, null, null, true, false, null, Map.of());

        AgentContext loaded = service.loadContext(noMemory);

        assertThat(loaded.history()).isEmpty();
        assertThat(loaded.longTermRecall()).isEmpty();
        assertThat(loaded.cachedAnswer()).isEmpty();
    }

    @Test
    void loadContextIncludesShortTermHistory() {
        service.recordTurn(ctx("u1", "第一轮"), "回答一");

        AgentContext loaded = service.loadContext(ctx("u1", "第二轮"));

        assertThat(loaded.history())
                .extracting(ChatMessage::content)
                .contains("第一轮", "回答一");
    }

    @Test
    void loadContextIncludesLongTermRecall() {
        repository.upsert(new MemoryRecord(
                "m1", "u1", "alice prefers tea", Map.of("type", "preference"), 0.9));

        AgentContext loaded = service.loadContext(ctx("u1", "what does alice prefer"));

        assertThat(loaded.longTermRecall()).isNotEmpty();
        assertThat(loaded.longTermRecall().get(0).content()).contains("tea");
    }

    // ── 撤回级联（Task 4.5） ──────────────────────────────────

    @Test
    void purgeRemovesMatchingShortTermMessages() {
        service.recordTurn(ctx("u1", "这是要撤回的消息"), "撤回的回答");

        int purged = service.purgeMessages("u1", List.of("这是要撤回的消息"));

        assertThat(purged).isEqualTo(1);
        AgentContext loaded = service.loadContext(ctx("u1", "第二轮"));
        assertThat(loaded.history()).extracting(ChatMessage::content)
                .doesNotContain("这是要撤回的消息");
    }

    @Test
    void excludeFiltersLongTermRecall() {
        repository.upsert(new MemoryRecord(
                "m1", "u1", "用户住在上海，养了一只猫", Map.of("type", "preference"), 0.9));
        assertThat(service.loadContext(ctx("u1", "用户住在哪里")).longTermRecall()).isNotEmpty();

        service.excludeFromLongTerm("u1", List.of("用户住在上海，养了一只猫"));

        assertThat(service.loadContext(ctx("u1", "用户住在哪里")).longTermRecall()).isEmpty();
    }

    @Test
    void loadContextIncludesProjectContext() {
        repository.upsert(new MemoryRecord(
                "m1", "u1", "project alpha milestone is next week", null, "p1", "project",
                Map.of(), 0.8));

        AgentRequestContext withProject = new AgentRequestContext(
                "u1", "status?", null, null, "p1", null, true, true, null, Map.of());

        assertThat(service.loadContext(withProject).projectContext()).contains("milestone");
    }

    // ── recordTurn / 蒸馏 / 摘要 ──────────────────────────────

    @Test
    void recordTurnStoresHistoryAndCache() {
        service.recordTurn(ctx("u1", "我的问题"), "我的答案");

        AgentContext loaded = service.loadContext(ctx("u1", "另一个问题"));

        assertThat(loaded.history()).hasSize(2);
        assertThat(cache.get("u1", null, null, "我的问题")).contains("我的答案");
    }

    @Test
    void distillsFactsEveryFiveTurns() {
        for (int i = 1; i <= 5; i++) {
            service.recordTurn(ctx("u1", "用户第" + i + "轮陈述我的偏好是喝茶"), "回答" + i);
        }

        List<MemoryRecord> facts = repository.search(
                MemorySearchQuery.builder("u1", "偏好", 20).type("fact").build());

        assertThat(facts).isNotEmpty();
        assertThat(facts.get(0).type()).isEqualTo("fact");
    }

    @Test
    void summarizesEveryTenTurns() {
        for (int i = 1; i <= 10; i++) {
            service.recordTurn(ctx("u1", "第" + i + "轮用户消息内容"), "回答" + i);
        }

        List<MemoryRecord> summaries = repository.search(
                MemorySearchQuery.builder("u1", "会话摘要", 20).type("summary").build());

        assertThat(summaries).isNotEmpty();
        assertThat(summaries.get(0).type()).isEqualTo("summary");
    }

    @Test
    void extractsProjectContextEveryProjectIntervalTurns() {
        for (int i = 1; i <= 8; i++) {
            service.recordTurn(withProject(ctx("u1", "项目第" + i + "轮：后端使用 Java 21 重构"), "p1"), "回答" + i);
        }

        List<MemoryRecord> project = repository.search(
                MemorySearchQuery.builder("u1", "Java 21", 20)
                        .projectId("p1")
                        .type("project")
                        .build());

        assertThat(project).isNotEmpty();
        assertThat(project.get(0).projectId()).isEqualTo("p1");
    }

    private static AgentRequestContext withProject(AgentRequestContext base, String projectId) {
        return new AgentRequestContext(base.userId(), base.message(), base.model(), base.persona(),
                projectId, base.sessionId(), base.useTools(), base.useMemory(), base.channel(), base.options());
    }

    private static AgentRequestContext ctx(String userId, String message) {
        return new AgentRequestContext(
                userId, message, null, null, null, null, true, true, null, Map.of());
    }
}
