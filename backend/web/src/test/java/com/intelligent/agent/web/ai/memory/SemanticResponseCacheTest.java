package com.intelligent.agent.web.ai.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 语义响应缓存单元契约（2026-08-15 补充）：
 * 精确命中 / userId+persona+model 隔离 / 语义相似命中 / 容量上限淘汰。
 */
class SemanticResponseCacheTest {

    private final SemanticResponseCache cache = new SemanticResponseCache();

    @Test
    void exactHitReturnsCachedAnswer() {
        cache.put("alice", "p1", "m1", "今天天气如何", "晴天");

        assertThat(cache.get("alice", "p1", "m1", "今天天气如何"))
                .contains("晴天");
    }

    @Test
    void cacheIsScopedByUserPersonaAndModel() {
        cache.put("alice", "p1", "m1", "今天天气如何", "晴天");

        assertThat(cache.get("bob", "p1", "m1", "今天天气如何")).isEmpty();
        assertThat(cache.get("alice", "p2", "m1", "今天天气如何")).isEmpty();
        assertThat(cache.get("alice", "p1", "m2", "今天天气如何")).isEmpty();
    }

    @Test
    void similarQuestionHitsWhenAboveThreshold() {
        cache.put("alice", "p1", "m1", "今天天气如何", "今天是晴天");

        // n-gram 兜底：相似问题在 0.5 阈值下命中
        assertThat(cache.findSimilar("alice", "p1", "m1", "今天天气怎么样", 0.5))
                .contains("今天是晴天");
        // 完全不同的问题在高阈值下不应命中
        assertThat(cache.findSimilar("alice", "p1", "m1", "量子计算是什么", 0.8))
                .isEmpty();
    }

    @Test
    void capacityEvictsOldestEntries() {
        SemanticResponseCache small = new SemanticResponseCache(Duration.ofHours(24), null, 3);
        small.put("alice", "p1", "m1", "q1", "a1");
        small.put("alice", "p1", "m1", "q2", "a2");
        small.put("alice", "p1", "m1", "q3", "a3");
        small.put("alice", "p1", "m1", "q4", "a4");

        assertThat(small.entries()).isLessThanOrEqualTo(3);
        assertThat(small.get("alice", "p1", "m1", "q1")).isEmpty();
        assertThat(small.get("alice", "p1", "m1", "q4")).contains("a4");
    }

    @Test
    void expiredEntryIsSkipped() {
        SemanticResponseCache shortTtl = new SemanticResponseCache(Duration.ofMillis(1));
        shortTtl.put("alice", "p1", "m1", "q1", "a1");
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(shortTtl.get("alice", "p1", "m1", "q1")).isEmpty();
    }
}
