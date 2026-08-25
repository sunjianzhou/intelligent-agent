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
    /** R-04 软删除元数据键。 */
    public static final String META_INVALIDATED = "invalidated";
    public static final String META_INVALIDATED_REASON = "invalidated_reason";
    public static final String META_INVALIDATED_AT = "invalidated_at";

    private static final double SIMILARITY_WEIGHT = 0.7;
    // G5（2026-08-15）：时间衰减维度，score = 0.7*sim + 0.2*importance + 0.1*recency
    private static final double RECENCY_WEIGHT = 0.1;
    private static final double IMPORTANCE_WEIGHT_G5 = 0.2;
    private static final double RECENCY_HALF_LIFE_MS = 24.0 * 3600_000;

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();
    /** 记录 id → 嵌入向量缓存（G5：随记录落盘，避免每次检索全量重嵌入）。 */
    private final Map<String, double[]> vectors = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 按用户分片的写锁 + 每用户独立文件：写放大从"全库全量重写"降到"单用户全量"，跨用户不再串行。 */
    private static final int LOCK_STRIPES = 64;
    private final Object[] userLocks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            userLocks[i] = new Object();
        }
    }
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
        // 内容可能变化，作废旧向量，检索时惰性重嵌
        vectors.remove(record.id());
        evictIfNeeded();
        persist(record.userId());
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
                .filter(record -> !isInvalidated(record))
                .filter(record -> matches(record, query))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        double[] queryVector = embedText(query.text());
        boolean backfilled = false;
        List<Scored> scoredList = new ArrayList<>(candidates.size());
        for (MemoryRecord record : candidates) {
            double[] recordVector = vectorFor(record);
            if (recordVector == null) {
                recordVector = embedText(record.content());
                vectors.put(record.id(), recordVector);
                backfilled = true;
            }
            double similarity = cosine(queryVector, recordVector);
            // 召回质量：要求有语义相似度；仅当重要度极高（>=0.9，如手动置顶）时允许零相似召回
            if (similarity > 0.0 || record.importance() >= 0.9) {
                MemoryRecord hit = bumpAccess(record);
                scoredList.add(scored(hit, similarity));
            }
        }
        if (backfilled) {
            persist(query.userId());
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
                .filter(record -> !isInvalidated(record))
                .filter(record -> matches(record, filter))
                .sorted(Comparator.comparing(MemoryRecord::createdAt).reversed())
                .limit(filter.limit())
                .toList();
    }

    @Override
    public int count(MemorySearchQuery filter) {
        return (int) records.values().stream()
                .filter(record -> record.userId().equals(filter.userId()))
                .filter(record -> !isInvalidated(record))
                .filter(record -> matches(record, filter))
                .count();
    }

    // ── R-04 软删除/恢复 ─────────────────────────────────────────────────

    @Override
    public boolean invalidate(String userId, String memoryId, String reason) {
        MemoryRecord existing = records.get(memoryId);
        if (existing == null || !existing.userId().equals(userId) || isInvalidated(existing)) {
            return false;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(existing.metadata());
        metadata.put(META_INVALIDATED, true);
        metadata.put(META_INVALIDATED_REASON,
                reason == null || reason.isBlank() ? "用户手动失效" : reason);
        metadata.put(META_INVALIDATED_AT, Instant.now().toString());
        MemoryRecord updated = new MemoryRecord(
                existing.id(), existing.userId(), existing.roleId(), existing.projectId(),
                existing.type(), existing.content(), metadata, existing.importance(),
                existing.createdAt(), Instant.now(), existing.accessCount());
        records.put(memoryId, updated);
        vectors.remove(memoryId);
        persist(userId);
        return true;
    }

    @Override
    public boolean restore(String userId, String memoryId) {
        MemoryRecord existing = records.get(memoryId);
        if (existing == null || !existing.userId().equals(userId) || !isInvalidated(existing)) {
            return false;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(existing.metadata());
        metadata.remove(META_INVALIDATED);
        metadata.remove(META_INVALIDATED_REASON);
        metadata.remove(META_INVALIDATED_AT);
        MemoryRecord updated = new MemoryRecord(
                existing.id(), existing.userId(), existing.roleId(), existing.projectId(),
                existing.type(), existing.content(), metadata, existing.importance(),
                existing.createdAt(), Instant.now(), existing.accessCount());
        records.put(memoryId, updated);
        vectors.remove(memoryId);
        persist(userId);
        return true;
    }

    @Override
    public List<MemoryRecord> listInvalidated(String userId, int limit) {
        return records.values().stream()
                .filter(record -> record.userId().equals(userId))
                .filter(VectorMemoryRepository::isInvalidated)
                .sorted(Comparator.comparing(MemoryRecord::updatedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    static boolean isInvalidated(MemoryRecord record) {
        return Boolean.TRUE.equals(record.metadata().get(META_INVALIDATED));
    }

    @Override
    public void clear(String userId) {
        List<String> removedIds = records.entrySet().stream()
                .filter(entry -> entry.getValue().userId().equals(userId))
                .map(Map.Entry::getKey)
                .toList();
        removedIds.forEach(records::remove);
        removedIds.forEach(vectors::remove);
        persist(userId);
    }

    @Override
    public boolean delete(String userId, String memoryId) {
        MemoryRecord existing = records.get(memoryId);
        if (existing == null || !existing.userId().equals(userId)) {
            return false;
        }
        boolean removed = records.remove(memoryId, existing);
        if (removed) {
            vectors.remove(memoryId);
            persist(userId);
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
        if (query.excludedTypes() != null && record.type() != null
                && query.excludedTypes().contains(record.type())) {
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
        double score = SIMILARITY_WEIGHT * similarity
                + IMPORTANCE_WEIGHT_G5 * record.importance()
                + RECENCY_WEIGHT * recency(record);
        return new Scored(record, score);
    }

    /** 时间衰减：24h 半衰期指数，新记录 ≈1，越旧越趋近 0。 */
    private static double recency(MemoryRecord record) {
        long ageMs = Math.max(0, System.currentTimeMillis() - record.createdAt().toEpochMilli());
        return Math.exp(-ageMs / RECENCY_HALF_LIFE_MS);
    }

    private double[] embedText(String text) {
        return embeddingService == null ? TextEmbedding.embed(text) : embeddingService.embed(text);
    }

    private double[] vectorFor(MemoryRecord record) {
        double[] cached = vectors.get(record.id());
        if (cached == null) {
            return null;
        }
        // 维度不匹配（如切换 embedding 模型/兜底方式）→ 作废重嵌
        double[] fresh = embedText(record.content());
        if (cached.length != fresh.length) {
            vectors.put(record.id(), fresh);
            return fresh;
        }
        return cached;
    }

    private double cosine(double[] a, double[] b) {
        return embeddingService == null ? TextEmbedding.cosine(a, b) : embeddingService.cosine(a, b);
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
        java.util.Set<String> affectedUsers = new java.util.LinkedHashSet<>();
        for (String id : evict) {
            MemoryRecord removed = records.get(id);
            if (removed != null) {
                affectedUsers.add(removed.userId());
            }
            records.remove(id);
            vectors.remove(id);
        }
        for (String user : affectedUsers) {
            persist(user);
        }
        log.info("向量记忆达到上限 {}，淘汰 {} 条", maxRecords, evict.size());
    }

    // ── 磁盘持久化（dataDir 非空时启用） ───────────────────────────────

    private Object lockFor(String userId) {
        return userLocks[(userId == null ? "" : userId).hashCode() & (LOCK_STRIPES - 1)];
    }

    private static String fileName(String userId) {
        String safe = (userId == null ? "default" : userId)
                .replaceAll("[^A-Za-z0-9_.@:\\-]", "_");
        return safe.isBlank() ? "default" : safe;
    }

    /** 只重写该用户的记忆文件（紧凑 JSON），不再全库全量落盘；按用户分片锁。 */
    private void persist(String userId) {
        if (dataDir == null) {
            return;
        }
        synchronized (lockFor(userId)) {
            try {
                Path memoryDir = dataDir.resolve("memory");
                Files.createDirectories(memoryDir);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("version", 1);
                List<Map<String, Object>> list = new ArrayList<>();
                for (MemoryRecord record : records.values()) {
                    if (record.userId().equals(userId)) {
                        list.add(toMap(record));
                    }
                }
                data.put("records", list);
                Files.writeString(memoryDir.resolve(fileName(userId) + ".json"),
                        MAPPER.writeValueAsString(data), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("向量记忆持久化失败 (user={}): {}", userId, e.getMessage());
            }
        }
    }

    private void load() {
        if (dataDir == null) {
            return;
        }
        Path memoryDir = dataDir.resolve("memory");
        if (!Files.isDirectory(memoryDir)) {
            return;
        }
        try {
            Path legacy = memoryDir.resolve("vector_memory.json");
            if (Files.exists(legacy)) {
                loadFile(legacy);
                // 迁移：旧单文件 → 按用户拆分，逐用户落盘确认后再移除旧文件
                java.util.Set<String> users = new java.util.LinkedHashSet<>();
                for (MemoryRecord record : records.values()) {
                    users.add(record.userId());
                }
                for (String user : users) {
                    persist(user);
                }
                Files.deleteIfExists(legacy);
            }
            try (var stream = Files.list(memoryDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().equals("vector_memory.json"))
                        .forEach(this::loadFile);
            }
        } catch (Exception e) {
            log.warn("向量记忆加载失败（忽略，以空库启动）: {}", e.getMessage());
        }
    }

    private void loadFile(Path file) {
        try {
            Map<String, Object> data = MAPPER.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), new TypeReference<>() {});
            Object list = data.get("records");
            int loaded = 0;
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        MemoryRecord record = fromMap((Map<String, Object>) item);
                        if (record != null && record.id() != null) {
                            records.put(record.id(), record);
                            Object vector = ((Map<String, Object>) item).get("vector");
                            if (vector instanceof List) {
                                double[] restored = new double[((List<?>) vector).size()];
                                for (int i = 0; i < restored.length; i++) {
                                    restored[i] = ((Number) ((List<?>) vector).get(i)).doubleValue();
                                }
                                vectors.put(record.id(), restored);
                            }
                            loaded++;
                        }
                    }
                }
            }
            log.info("向量记忆已加载 {} 条: {}", loaded, file);
        } catch (Exception e) {
            log.warn("向量记忆文件读取失败 {}（跳过）: {}", file, e.getMessage());
        }
    }

    private Map<String, Object> toMap(MemoryRecord r) {
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
        double[] vector = vectors.get(r.id());
        if (vector != null) {
            List<Double> vectorList = new ArrayList<>(vector.length);
            for (double v : vector) {
                vectorList.add(v);
            }
            m.put("vector", vectorList);
        }
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
