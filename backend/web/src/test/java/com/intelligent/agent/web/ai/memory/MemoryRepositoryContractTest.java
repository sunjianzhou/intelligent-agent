package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆仓库端口契约测试（Plan 2 / Task 1）：
 * upsert / 按用户隔离的 search / 按用户作用域的 delete，
 * 以及 role_id / project_id / type / importance 过滤。
 */
class MemoryRepositoryContractTest {

    private final MemoryRepository repository = new VectorMemoryRepository();

    // ── 用户隔离 ──────────────────────────────────────────────

    @Test
    void searchNeverReturnsAnotherUsersMemory() {
        repository.upsert(new MemoryRecord("m1", "alice", "secret", Map.of()));

        assertThat(repository.search("bob", "secret", 5)).isEmpty();
    }

    @Test
    void searchReturnsOwnMemoryForSameUser() {
        repository.upsert(new MemoryRecord("m1", "alice", "she prefers dark mode", Map.of("type", "preference")));

        List<MemoryRecord> hits = repository.search("alice", "dark mode preference", 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo("m1");
        assertThat(hits.get(0).userId()).isEqualTo("alice");
    }

    // ── 多条件过滤 ────────────────────────────────────────────

    @Test
    void searchFiltersByProjectIdAndType() {
        repository.upsert(new MemoryRecord("m1", "alice", "java migration plan", null, "p1", "task", Map.of(), 0.8));
        repository.upsert(new MemoryRecord("m2", "alice", "java migration notes", null, "p1", "knowledge", Map.of(), 0.7));
        repository.upsert(new MemoryRecord("m3", "alice", "java migration notes", null, "p2", "task", Map.of(), 0.7));

        MemorySearchQuery query = MemorySearchQuery.builder("alice", "migration", 5)
                .projectId("p1")
                .type("task")
                .build();

        assertThat(repository.search(query)).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void searchFiltersByImportance() {
        repository.upsert(new MemoryRecord("m1", "alice", "low value note", Map.of()));
        repository.upsert(new MemoryRecord("m2", "alice", "important binding rule", Map.of(), 0.9));

        MemorySearchQuery query = MemorySearchQuery.builder("alice", "note", 5)
                .minImportance(0.6)
                .build();

        assertThat(repository.search(query)).extracting(MemoryRecord::id).containsExactly("m2");
    }

    // ── 作用域删除 ────────────────────────────────────────────

    @Test
    void deleteIsScopedToOwnerUser() {
        repository.upsert(new MemoryRecord("m1", "alice", "secret", Map.of()));

        assertThat(repository.delete("bob", "m1")).isFalse();
        assertThat(repository.search("alice", "secret", 5)).hasSize(1);

        assertThat(repository.delete("alice", "m1")).isTrue();
        assertThat(repository.search("alice", "secret", 5)).isEmpty();
    }

    // ── upsert 语义 ───────────────────────────────────────────

    @Test
    void upsertOverwritesRecordWithSameId() {
        repository.upsert(new MemoryRecord("m1", "alice", "old content", Map.of()));
        repository.upsert(new MemoryRecord("m1", "alice", "new content", Map.of("type", "fact"), 0.9));

        List<MemoryRecord> hits = repository.search("alice", "new content", 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).isEqualTo("new content");
        assertThat(hits.get(0).importance()).isEqualTo(0.9);
    }

    @Test
    void searchRanksHigherImportanceFirstWhenSimilar() {
        repository.upsert(new MemoryRecord("m1", "alice", "user prefers tea", Map.of(), 0.5));
        repository.upsert(new MemoryRecord("m2", "alice", "user prefers tea", Map.of(), 0.95));

        List<MemoryRecord> hits = repository.search("alice", "user prefers tea", 5);

        assertThat(hits).extracting(MemoryRecord::id).containsExactly("m2", "m1");
    }

    @Test
    void listFiltersWithoutQueryText() {
        repository.upsert(new MemoryRecord("m1", "alice", "spec v1", null, "p1", "project_spec", Map.of(), 0.8));
        repository.upsert(new MemoryRecord("m2", "alice", "spec v2", null, "p1", "project_spec", Map.of(), 0.8));
        repository.upsert(new MemoryRecord("m3", "bob", "bob spec", null, "p1", "project_spec", Map.of(), 0.8));

        MemorySearchQuery filter = MemorySearchQuery.builder("alice", "", 10)
                .projectId("p1")
                .type("project_spec")
                .build();

        assertThat(repository.list(filter)).extracting(MemoryRecord::id)
                .containsExactlyInAnyOrder("m1", "m2");
    }
}
