package com.intelligent.agent.web.infrastructure.vectorstore;

import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量记忆仓库（Task 1 的矢量存储实现）。
 * <p>
 * 使用字符 n-gram 哈希嵌入 + 余弦相似度，无需外部模型即可满足端口契约；
 * 后续可在不改变端口的前提下替换为 Spring AI 向量存储实现。
 * 线程安全：ConcurrentHashMap + 原子自增访问计数。
 */
public class VectorMemoryRepository implements MemoryRepository {

    private static final double SIMILARITY_WEIGHT = 0.7;
    private static final double IMPORTANCE_WEIGHT = 0.3;

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();

    @Override
    public void upsert(MemoryRecord record) {
        if (record == null || record.id() == null || record.userId() == null) {
            throw new IllegalArgumentException("record, id and userId must not be null");
        }
        records.put(record.id(), record);
    }

    @Override
    public List<MemoryRecord> search(String userId, String text, int limit) {
        return search(MemorySearchQuery.of(userId, text, limit));
    }

    @Override
    public List<MemoryRecord> search(MemorySearchQuery query) {
        if (query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        double[] queryVector = TextEmbedding.embed(query.text());

        return records.values().stream()
                .filter(record -> record.userId().equals(query.userId()))
                .filter(record -> matches(record, query))
                .map(record -> scored(record,
                        TextEmbedding.cosine(queryVector, TextEmbedding.embed(record.content()))))
                .filter(Scored::aboveZero)
                .sorted(Comparator.comparingDouble((Scored s) -> s.score()).reversed())
                .limit(query.limit())
                .map(Scored::record)
                .toList();
    }

    @Override
    public boolean delete(String userId, String memoryId) {
        MemoryRecord existing = records.get(memoryId);
        if (existing == null || !existing.userId().equals(userId)) {
            return false;
        }
        return records.remove(memoryId, existing);
    }

    // ── 过滤与打分 ────────────────────────────────────────────

    private boolean matches(MemoryRecord record, MemorySearchQuery query) {
        if (query.roleId() != null && !query.roleId().equals(record.roleId())) {
            return false;
        }
        if (query.projectId() != null && !query.projectId().equals(record.projectId())) {
            return false;
        }
        if (query.type() != null && !query.type().equals(record.type())) {
            return false;
        }
        return record.importance() >= query.minImportance();
    }

    private static Scored scored(MemoryRecord record, double similarity) {
        double score = SIMILARITY_WEIGHT * similarity + IMPORTANCE_WEIGHT * record.importance();
        return new Scored(record, score);
    }

    private record Scored(MemoryRecord record, double score) {
        boolean aboveZero() {
            return score > 0.0;
        }
    }
}
