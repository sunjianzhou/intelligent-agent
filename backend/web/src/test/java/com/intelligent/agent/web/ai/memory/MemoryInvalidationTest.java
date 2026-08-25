package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-04 软删除/失效测试：失效后检索不再召回、可恢复、恢复后重新召回、作用域隔离。
 */
class MemoryInvalidationTest {

    private final VectorMemoryRepository repository = new VectorMemoryRepository();

    private void seed(String id, String userId, String content) {
        repository.upsert(new MemoryRecord(id, userId, content, Map.of("type", "fact"), 0.8));
    }

    @Test
    void invalidatedRecordIsExcludedFromSearchAndList() {
        seed("m1", "alice", "用户喜欢喝茶");

        boolean ok = repository.invalidate("alice", "m1", "记错了");

        assertThat(ok).isTrue();
        assertThat(repository.search("alice", "喝茶", 5)).isEmpty();
        assertThat(repository.list(MemorySearchQuery.builder("alice", "", 10).build()))
                .isEmpty();
        assertThat(repository.listInvalidated("alice", 10))
                .extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void restoredRecordIsSearchableAgain() {
        seed("m1", "alice", "用户喜欢喝茶");
        repository.invalidate("alice", "m1", "临时失效");

        boolean restored = repository.restore("alice", "m1");

        assertThat(restored).isTrue();
        assertThat(repository.search("alice", "喝茶", 5))
                .extracting(MemoryRecord::id).containsExactly("m1");
        assertThat(repository.listInvalidated("alice", 10)).isEmpty();
    }

    @Test
    void invalidateAndRestoreAreScopedToOwner() {
        seed("m1", "alice", "alice 的隐私");

        assertThat(repository.invalidate("bob", "m1", "x")).isFalse();
        assertThat(repository.search("alice", "隐私", 5)).hasSize(1);

        repository.invalidate("alice", "m1", "x");
        assertThat(repository.restore("bob", "m1")).isFalse();
        assertThat(repository.listInvalidated("alice", 10)).hasSize(1);

        assertThat(repository.restore("alice", "m1")).isTrue();
        assertThat(repository.search("alice", "隐私", 5)).hasSize(1);
    }

    @Test
    void invalidatingMissingOrAlreadyInvalidatedReturnsFalse() {
        assertThat(repository.invalidate("alice", "nope", "x")).isFalse();

        seed("m1", "alice", "内容");
        repository.invalidate("alice", "m1", "x");
        assertThat(repository.invalidate("alice", "m1", "again")).isFalse();
        assertThat(repository.restore("alice", "nope")).isFalse();
    }
}
