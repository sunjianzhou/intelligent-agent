package com.intelligent.agent.web.infrastructure.vectorstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量记忆仓库（Task 1 的向量存储实现）。
 * <p>
 * 使用真实 embedding（{@link EmbeddingService}，Ollama /api/embed）或 n-gram 哈希兜底 +
 * 余弦相似度。可配置磁盘持久化（dataDir 非空时启动加载、变更写回 JSON），
 * 并带容量上限（默认 5000 条，超出按 重要度/访问次数/时间 淘汰）。
 * 线程安全：{@link ConcurrentHashMap} + 同步持久化。
 */
public class VectorMemoryRepository implements MemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryRepository.class);
    public static final int DEFAULT_MAX_RECORDS = 5000;

    private static final double SIMILARITY_WEIGHT = 0.7;
    private static final double IMPORTANCE_WEIGHT = 0.3;

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();
    private final EmbeddingService embeddingService;
    private final Path dataDir;
    private final int maxRecords;

    public VectorMemoryRepository() {
        this(null, null, DEFAULT_MAX_RECORDS);
    }

    public VectorMemoryRepository(EmbeddingService embeddingService) {
        this(embeddingService, null, DEFAULT_MAX_RECORDS);
    }

    public VectorMemoryRepository(Path dataDir) {
        this(null, dataDir, DEFAULT_MAX_RECORDS);
    }

    public VectorMemoryRepository(EmbeddingService embeddingService, Path dataDir, int maxRecords) {
        this.embeddingService = embeddingService;
        this.dataDir = dataDir;
        this.maxRecords = maxRecords > 0 ? maxRecords : DEFAULT_MAX_RECORDS;
        load();
    }

    @Override
    public void upsert(MemoryRecord record) {
        if (record == null || record.id() == null || record.userId() == null) {
            throw new IllegalArgumentException("record, id and userId must not be null");
        }
        records.put(record.id(), record);
        evictIfNeeded();
        persist();
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
        List<MemoryRecord> candidates = records.values().stream()
                .filter(record -> record.userId().equals(query.userId()))
                .filter(record -> matches(record, query))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        double[] queryVector = embeddingService == null
                ? TextEmbedding.embed(query.text()) : embeddingService.embed(query.text());
        List<String> contents = candidates.stream().map(MemoryRecord::content).toList();
        java.util.List<double[]> vectors = embeddingService == null
                ? contents.stream().map(TextEmbedding::embed).toList()
                : embeddingService.embedAll(contents);
        List<Scored> scoredList = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            MemoryRecord record = candidates.get(i);
            double similarity = embeddingService == null
                    ? TextEmbedding.cosine(queryVector, vectors.get(i))
                    : embeddingService.cosine(queryVector, vectors.get(i));
            // 召回质量：要求有语义相似度；仅当重要度极高（>=0.9，如手动置顶）时允许零相似召回
            if (similarity > 0.0 || record.importance() >= 0.9) {
                MemoryRecord hit = bumpAccess(record);
                scoredList.add(scored(hit, similarity));
            }
        }
        return scoredList.stream()
                .sorted(Comparator.comparingDouble((Scored s) -> s.score()).reversed())
                .limit(query.limit())
                .map(Scored::record)
                .toList();
    }

    @Override
    public List<MemoryRecord> list(MemorySearchQuery filter) {
        return records.values().stream()
                .filter(record -> record.userId().equals(filter.userId()))
                .filter(record -> matches(record, filter))
                .sorted(Comparator.comparing(MemoryRecord::createdAt).reversed())
                .limit(filter.limit())
                .toList();
    }

    @Override
    public int count(MemorySearchQuery filter) {
        return (int) records.values().stream()
                .filter(record -> record.userId().equals(filter.userId()))
                .filter(record -> matches(record, filter))
                .count();
    }

    @Override
    public void clear(String userId) {
        records.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
        persist();
    }

    @Override
    public boolean delete(String userId, String memoryId) {
        MemoryRecord existing = records.get(memoryId);
        if (existing == null || !existing.userId().equals(userId)) {
            return false;
        }
        boolean removed = records.remove(memoryId, existing);
        if (removed) {
            persist();
        }
        return removed;
    }

    // ── 过滤与打分 ──────────────────────────────────────────────────────

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

    private MemoryRecord bumpAccess(MemoryRecord record) {
        MemoryRecord updated = record.withAccessCount(record.accessCount() + 1);
        records.put(record.id(), updated);
        return updated;
    }

    private static Scored scored(MemoryRecord record, double similarity) {
        double score = SIMILARITY_WEIGHT * similarity + IMPORTANCE_WEIGHT * record.importance();
        return new Scored(record, score);
    }

    // ── 容量上限 ─────────────────────────────────────────────────────────

    private synchronized void evictIfNeeded() {
        if (records.size() <= maxRecords) {
            return;
        }
        int excess = records.size() - maxRecords;
        List<String> evict = records.values().stream()
                .sorted(Comparator.comparingDouble(MemoryRecord::importance)
                        .thenComparingInt(MemoryRecord::accessCount)
                        .thenComparing(MemoryRecord::updatedAt))
                .limit(excess)
                .map(MemoryRecord::id)
                .toList();
        for (String id : evict) {
            records.remove(id);
        }
        log.info("向量记忆达到上限 {}，淘汰 {} 条", maxRecords, evict.size());
    }

    // ── 磁盘持久化（dataDir 非空时启用） ───────────────────────────────

    private synchronized void persist() {
        if (dataDir == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", 1);
            List<Map<String, Object>> list = new ArrayList<>(records.size());
            for (MemoryRecord record : records.values()) {
                list.add(toMap(record));
            }
            data.put("records", list);
            Files.createDirectories(dataDir.resolve("memory"));
            Files.writeString(dataDir.resolve("memory").resolve("vector_memory.json"),
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("向量记忆持久化失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (dataDir == null) {
            return;
        }
        Path file = dataDir.resolve("memory").resolve("vector_memory.json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, Object> data = new ObjectMapper().readValue(
                    Files.readString(file, StandardCharsets.UTF_8), new TypeReference<>() {});
            Object list = data.get("records");
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        MemoryRecord record = fromMap((Map<String, Object>) item);
                        if (record != null && record.id() != null) {
                            records.put(record.id(), record);
                        }
                    }
                }
            }
            log.info("向量记忆已加载 {} 条: {}", records.size(), file);
        } catch (Exception e) {
            log.warn("向量记忆加载失败（忽略，以空库启动）: {}", e.getMessage());
        }
    }

    private static Map<String, Object> toMap(MemoryRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("userId", r.userId());
        m.put("roleId", r.roleId());
        m.put("projectId", r.projectId());
        m.put("type", r.type());
        m.put("content", r.content());
        m.put("metadata", r.metadata());
        m.put("importance", r.importance());
        m.put("createdAt", r.createdAt().toString());
        m.put("updatedAt", r.updatedAt().toString());
        m.put("accessCount", r.accessCount());
        return m;
    }

    private static MemoryRecord fromMap(Map<String, Object> m) {
        try {
            return new MemoryRecord(
                    str(m.get("id")),
                    str(m.get("userId")),
                    str(m.get("roleId")),
                    str(m.get("projectId")),
                    str(m.get("type")),
                    str(m.get("content")),
                    m.get("metadata") instanceof Map
                            ? (Map<String, Object>) m.get("metadata") : Map.of(),
                    dbl(m.get("importance"), 0.5),
                    instant(m.get("createdAt")),
                    instant(m.get("updatedAt")),
                    (int) dbl(m.get("accessCount"), 0));
        } catch (Exception e) {
            log.warn("向量记忆记录解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double dbl(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static Instant instant(Object value) {
        return value == null ? Instant.now() : Instant.parse(String.valueOf(value));
    }

    private record Scored(MemoryRecord record, double score) {
    }
}
