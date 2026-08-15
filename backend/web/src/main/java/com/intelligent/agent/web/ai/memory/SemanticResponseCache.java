package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.infrastructure.vectorstore.TextEmbedding;
import com.intelligent.agent.web.infrastructure.vectorstore.EmbeddingService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 语义响应缓存（24h TTL）。
 * <p>
 * 缓存键必须包含 userId + persona + model + 归一化问题 ——
 * 不同角色/模型/用户之间严格隔离；同时提供相似问题检索。
 */
public class SemanticResponseCache {

    public static final Duration DEFAULT_TTL = Duration.ofHours(24);
    public static final int DEFAULT_MAX_ENTRIES = 2000;

    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final EmbeddingService embeddingService;
    private final int maxEntries;

    public SemanticResponseCache() {
        this(DEFAULT_TTL, null, DEFAULT_MAX_ENTRIES);
    }

    public SemanticResponseCache(Duration ttl) {
        this(ttl, null, DEFAULT_MAX_ENTRIES);
    }

    public SemanticResponseCache(Duration ttl, EmbeddingService embeddingService) {
        this(ttl, embeddingService, DEFAULT_MAX_ENTRIES);
    }

    public SemanticResponseCache(Duration ttl, EmbeddingService embeddingService, int maxEntries) {
        this.ttl = ttl;
        this.embeddingService = embeddingService;
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
    }

    /** 便捷写入：model 为 null。 */
    public void put(String userId, String persona, String question, String answer) {
        put(userId, persona, null, question, answer);
    }

    /** 便捷读取：model 为 null。 */
    public Optional<String> get(String userId, String persona, String question) {
        return get(userId, persona, null, question);
    }

    public void put(String userId, String persona, String model, String question, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        entries.put(key(userId, persona, model, question), new CacheEntry(answer, Instant.now()));
        evictIfNeeded();
    }

    public Optional<String> get(String userId, String persona, String model, String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        CacheEntry entry = entries.get(key(userId, persona, model, question));
        if (entry == null || expired(entry)) {
            if (entry != null) {
                entries.remove(key(userId, persona, model, question));
            }
            return Optional.empty();
        }
        return Optional.of(entry.answer());
    }

    /** 语义相似检索：同用户/角色/模型范围内，与问题最相似且超过阈值的缓存答案。 */
    public Optional<String> findSimilar(String userId, String persona, String model,
                                        String question, double minSimilarity) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        List<Scored> scored = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        List<CacheEntry> candidateEntries = new ArrayList<>();
        for (Map.Entry<String, CacheEntry> e : entries.entrySet()) {
            String[] parts = e.getKey().split("\\|", 4);
            if (parts.length < 4
                    || !parts[0].equals(safe(userId))
                    || !parts[1].equals(safe(persona))
                    || !parts[2].equals(safe(model))) {
                continue;
            }
            CacheEntry entry = e.getValue();
            if (expired(entry)) {
                entries.remove(e.getKey());
                continue;
            }
            candidates.add(parts[3]);
            candidateEntries.add(entry);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        double[] queryVector = embeddingService == null
                ? TextEmbedding.embed(question) : embeddingService.embed(question);
        java.util.List<double[]> vectors = embeddingService == null
                ? candidates.stream().map(TextEmbedding::embed).toList()
                : embeddingService.embedAll(candidates);
        for (int i = 0; i < vectors.size(); i++) {
            double similarity = embeddingService == null
                    ? TextEmbedding.cosine(queryVector, vectors.get(i))
                    : embeddingService.cosine(queryVector, vectors.get(i));
            if (similarity >= minSimilarity) {
                scored.add(new Scored(candidateEntries.get(i).answer(), similarity));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed())
                .findFirst()
                .map(Scored::answer);
    }

    /** 当前缓存条目数（/api/config/runtime usage 用）。 */
    public int entries() {
        evictExpired();
        return entries.size();
    }

    private void evictExpired() {
        entries.entrySet().removeIf(e -> expired(e.getValue()));
    }

    /** 容量上限：先清过期，仍超限时按创建时间淘汰最旧条目。 */
    private void evictIfNeeded() {
        if (entries.size() <= maxEntries) {
            return;
        }
        evictExpired();
        int excess = entries.size() - maxEntries;
        if (excess <= 0) {
            return;
        }
        entries.entrySet().stream()
                // createdAt 同毫秒时按 key 决胜，保证淘汰确定性（2026-08-15）
                .sorted(java.util.Comparator
                        .comparing((Map.Entry<String, CacheEntry> e) -> e.getValue().createdAt())
                        .thenComparing(Map.Entry::getKey))
                .limit(excess)
                .forEach(e -> entries.remove(e.getKey(), e.getValue()));
    }

    private boolean expired(CacheEntry entry) {
        return entry.createdAt().plus(ttl).isBefore(Instant.now());
    }

    private static String key(String userId, String persona, String model, String question) {
        return safe(userId) + "|" + safe(persona) + "|" + safe(model) + "|"
                + normalize(question);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("|", "");
    }

    private record CacheEntry(String answer, Instant createdAt) {
    }

    private record Scored(String answer, double similarity) {
    }
}
