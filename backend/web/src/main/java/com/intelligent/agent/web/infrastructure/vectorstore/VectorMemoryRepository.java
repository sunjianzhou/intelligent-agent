package com.intelligent.agent.web.infrastructure.vectorstore;

import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存向量记忆仓库（Task 1 的矢量存储实现）。
 * <p>
 * 使用字符 n-gram 哈希嵌入 + 余弦相似度，无需外部模型即可满足端口契约；
 * 后续可在不改变端口的前提下替换为 Spring AI 向量存储实现。
 * 线程安全：ConcurrentHashMap + 原子自增访问计数。
 */
public class VectorMemoryRepository implements MemoryRepository {

    private static final int EMBEDDING_DIM = 128;
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
        double[] queryVector = embed(query.text());

        return records.values().stream()
                .filter(record -> record.userId().equals(query.userId()))
                .filter(record -> matches(record, query))
                .map(record -> scored(record, cosine(queryVector, embed(record.content()))))
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

    // ── 哈希嵌入 + 余弦相似度 ─────────────────────────────────

    private static double[] embed(String text) {
        double[] vector = new double[EMBEDDING_DIM];
        String normalized = text.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff ]", " ").trim();
        AtomicInteger position = new AtomicInteger();
        // 字符 3-gram，双哈希减少碰撞
        String[] tokens = normalized.split("\\s+");
        StringBuilder grams = new StringBuilder();
        for (String token : tokens) {
            String padded = "  " + token + "  ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                grams.append(padded, i, i + 3).append('|');
            }
        }
        String gramText = grams.toString();
        for (int i = 0; i + 3 <= gramText.length(); i += 3) {
            int hash = hash(gramText.substring(i, i + 3));
            int index = Math.floorMod(hash, EMBEDDING_DIM);
            vector[index] += 1.0;
            int secondIndex = Math.floorMod(hash * 31 + 7, EMBEDDING_DIM);
            vector[secondIndex] += 0.5;
            position.incrementAndGet();
        }
        if (position.get() == 0 && !normalized.isEmpty()) {
            int hash = hash(normalized);
            vector[Math.floorMod(hash, EMBEDDING_DIM)] += 1.0;
        }
        normalize(vector);
        return vector;
    }

    private static int hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    private static void normalize(double[] vector) {
        double norm = 0.0;
        for (double v : vector) {
            norm += v * v;
        }
        if (norm == 0.0) {
            return;
        }
        double inv = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inv;
        }
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private record Scored(MemoryRecord record, double score) {
        boolean aboveZero() {
            return score > 0.0;
        }
    }
}
