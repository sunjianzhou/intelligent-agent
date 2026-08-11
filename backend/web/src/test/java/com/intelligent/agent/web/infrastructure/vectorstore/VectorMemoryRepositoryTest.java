package com.intelligent.agent.web.infrastructure.vectorstore;

import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
