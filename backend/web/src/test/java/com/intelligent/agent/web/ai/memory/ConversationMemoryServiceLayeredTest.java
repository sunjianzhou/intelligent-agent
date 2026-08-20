package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 分层记忆测试：working / episodic（summary）/ semantic（fact）分层召回，
 * RAG 按层配额，合并召回保持兼容。
 */
class ConversationMemoryServiceLayeredTest {

    private final VectorMemoryRepository repository = new VectorMemoryRepository();
    private final ConversationMemoryService service = new ConversationMemoryService(
            repository, new SemanticResponseCache(), new MemoryDistillationService());

    private AgentRequestContext ctx(String message) {
        return AgentRequestContext.of("u1", message);
    }

    @Test
    void partitionsRecallIntoEpisodicAndSemanticLayers() {
        repository.upsert(new MemoryRecord(
                "s1", "u1", "会话摘要: 昨天讨论了迁移方案", null, null, "summary",
                Map.of("source", "session_summary"), 0.95));
        repository.upsert(new MemoryRecord(
                "f1", "u1", "事实: 用户喜欢咖啡", null, null, "fact",
                Map.of("source", "distillation"), 0.95));

        AgentContext loaded = service.loadContext(ctx("聊了什么"));

        assertThat(loaded.episodicRecall()).extracting(MemoryRecord::id).contains("s1");
        assertThat(loaded.episodicRecall()).extracting(MemoryRecord::id).doesNotContain("f1");
        assertThat(loaded.semanticRecall()).extracting(MemoryRecord::id).contains("f1");
        assertThat(loaded.semanticRecall()).extracting(MemoryRecord::id).doesNotContain("s1");
        assertThat(loaded.longTermRecall()).extracting(MemoryRecord::id)
                .containsExactlyInAnyOrder("s1", "f1");
    }

    @Test
    void enforcesPerLayerQuotas() {
        for (int i = 0; i < 5; i++) {
            repository.upsert(new MemoryRecord(
                    "s" + i, "u1", "会话摘要: 第 " + i + " 轮", null, null, "summary",
                    Map.of(), 0.95));
        }
        for (int i = 0; i < 5; i++) {
            repository.upsert(new MemoryRecord(
                    "f" + i, "u1", "事实: 偏好 " + i, null, null, "fact",
                    Map.of(), 0.95));
        }

        AgentContext loaded = service.loadContext(ctx("偏好 摘要"));

        assertThat(loaded.episodicRecall()).hasSizeLessThanOrEqualTo(2);
        assertThat(loaded.semanticRecall()).hasSizeLessThanOrEqualTo(3);
        assertThat(loaded.longTermRecall()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void returnsEmptyLayersWhenMemoryDisabled() {
        AgentRequestContext noMemory = new AgentRequestContext(
                "u1", "hi", null, null, null, null, true, false, null, Map.of());

        AgentContext loaded = service.loadContext(noMemory);

        assertThat(loaded.episodicRecall()).isEmpty();
        assertThat(loaded.semanticRecall()).isEmpty();
        assertThat(loaded.longTermRecall()).isEmpty();
    }
}
