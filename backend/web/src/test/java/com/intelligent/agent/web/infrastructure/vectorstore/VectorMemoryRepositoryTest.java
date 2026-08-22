package com.intelligent.agent.web.infrastructure.vectorstore;

import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VectorMemoryRepository：磁盘持久化往返 + 容量上限淘汰（Fix P0-4）。
 */
class VectorMemoryRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsRecords() {
        VectorMemoryRepository repo = new VectorMemoryRepository(tempDir);
        repo.upsert(new MemoryRecord("m1", "alice", "alice prefers tea",
                null, null, "fact", Map.of("source", "test"), 0.8));
        repo.upsert(new MemoryRecord("m2", "alice", "project p1 milestone next week",
                null, "p1", "project", Map.of(), 0.9));

        VectorMemoryRepository reloaded = new VectorMemoryRepository(tempDir);
        List<MemoryRecord> hits = reloaded.search("alice", "prefers tea", 5);

        MemoryRecord m1 = hits.stream()
                .filter(r -> r.id().equals("m1"))
                .findFirst()
                .orElseThrow();
        assertThat(m1.content()).isEqualTo("alice prefers tea");
        assertThat(m1.metadata()).containsEntry("source", "test");
    }

    @Test
    void clearPersistsRemoval() {
        VectorMemoryRepository repo = new VectorMemoryRepository(tempDir);
        repo.upsert(new MemoryRecord("m1", "alice", "secret", Map.of()));

        repo.clear("alice");

        VectorMemoryRepository reloaded = new VectorMemoryRepository(tempDir);
        assertThat(reloaded.count(MemorySearchQuery.builder("alice", "", 10).build())).isZero();
    }

    @Test
    void evictsBeyondCapacityKeepingHighestImportance() {
        VectorMemoryRepository repo = new VectorMemoryRepository(null, null, 2);
        repo.upsert(new MemoryRecord("low", "u", "low value", Map.of(), 0.1));
        repo.upsert(new MemoryRecord("high", "u", "important value", Map.of(), 0.95));
        repo.upsert(new MemoryRecord("mid", "u", "middle value", Map.of(), 0.5));

        List<MemoryRecord> remaining = repo.list(MemorySearchQuery.builder("u", "", 10).build());

        assertThat(remaining).hasSize(2);
        assertThat(remaining).extracting(MemoryRecord::id)
                .containsExactlyInAnyOrder("high", "mid");
    }

    @Test
    void searchVectorsArePersistedAndSurviveReload() throws Exception {
        VectorMemoryRepository repo = new VectorMemoryRepository(tempDir);
        repo.upsert(new MemoryRecord("m1", "alice", "alice prefers green tea",
                null, null, "fact", Map.of(), 0.8));
        repo.upsert(new MemoryRecord("m2", "alice", "project ships next week",
                null, "p1", "project", Map.of(), 0.5));

        // 首次检索触发惰性嵌入 + 落盘
        assertThat(repo.search("alice", "green tea", 5))
                .extracting(MemoryRecord::id)
                .contains("m1");

        String persisted = java.nio.file.Files.readString(
                tempDir.resolve("memory/alice.json"));
        assertThat(persisted).contains("\"vector\"");

        // 重载后仍可检索（向量从磁盘恢复，不重新嵌入）
        VectorMemoryRepository reloaded = new VectorMemoryRepository(tempDir);
        assertThat(reloaded.search("alice", "green tea", 5))
                .extracting(MemoryRecord::id)
                .contains("m1");
    }

    @Test
    void newerRecordRanksHigherWithTimeDecay() {
        VectorMemoryRepository repo = new VectorMemoryRepository();
        Instant now = Instant.now();
        MemoryRecord oldRecord = new MemoryRecord(
                "old", "alice", "shared preference note", null, null, "fact",
                Map.of(), 0.7, now.minus(10, ChronoUnit.DAYS), now.minus(10, ChronoUnit.DAYS), 0);
        MemoryRecord newRecord = new MemoryRecord(
                "new", "alice", "shared preference note", null, null, "fact",
                Map.of(), 0.7, now, now, 0);
        repo.upsert(oldRecord);
        repo.upsert(newRecord);

        // 内容/重要度相同，时间衰减使新记录排前（0.7sim + 0.2imp + 0.1recency）
        assertThat(repo.search("alice", "shared preference note", 5))
                .extracting(MemoryRecord::id)
                .containsExactly("new", "old");
    }

    @Test
    void migratesLegacySingleFileToPerUserFiles() throws Exception {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Map<String, Object> legacy = new java.util.LinkedHashMap<>();
        legacy.put("version", 1);
        legacy.put("records", List.of(
                legacyRecord("m1", "alice", "alice fact"),
                legacyRecord("m2", "bob", "bob fact")));
        Files.writeString(memoryDir.resolve("vector_memory.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(legacy),
                StandardCharsets.UTF_8);

        VectorMemoryRepository repo = new VectorMemoryRepository(tempDir);

        assertThat(Files.exists(memoryDir.resolve("alice.json"))).isTrue();
        assertThat(Files.exists(memoryDir.resolve("bob.json"))).isTrue();
        assertThat(Files.exists(memoryDir.resolve("vector_memory.json"))).isFalse();
        assertThat(repo.count(MemorySearchQuery.builder("alice", "", 10).build())).isEqualTo(1);
        assertThat(repo.count(MemorySearchQuery.builder("bob", "", 10).build())).isEqualTo(1);
    }

    private static Map<String, Object> legacyRecord(String id, String userId, String content) {
        return Map.of(
                "id", id,
                "userId", userId,
                "content", content,
                "metadata", Map.of(),
                "importance", 0.8,
                "createdAt", "2026-08-01T00:00:00Z",
                "updatedAt", "2026-08-01T00:00:00Z",
                "accessCount", 0);
    }
}
