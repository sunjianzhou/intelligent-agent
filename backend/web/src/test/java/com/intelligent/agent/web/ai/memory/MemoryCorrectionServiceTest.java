package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-04 聊天内记忆纠错测试：指令识别（删除/更新）、软删除生效（下一轮不再召回）、更新写入新记忆。
 */
class MemoryCorrectionServiceTest {

    private final MemoryCorrectionService correction = new MemoryCorrectionService();
    private final VectorMemoryRepository repository = new VectorMemoryRepository();

    @Test
    void detectsDeleteDirective() {
        MemoryCorrectionService.CorrectionRequest req =
                correction.detect("删掉你记的喜欢喝茶");

        assertThat(req).isNotNull();
        assertThat(req.kind()).isEqualTo(MemoryCorrectionService.CorrectionRequest.Kind.DELETE);
        assertThat(req.target()).contains("喜欢喝茶");
    }

    @Test
    void detectsUpdateDirective() {
        MemoryCorrectionService.CorrectionRequest req =
                correction.detect("把喜欢喝茶改成喜欢咖啡");

        assertThat(req).isNotNull();
        assertThat(req.kind()).isEqualTo(MemoryCorrectionService.CorrectionRequest.Kind.UPDATE);
        assertThat(req.target()).contains("喜欢喝茶");
        assertThat(req.replacement()).contains("喜欢咖啡");
    }

    @Test
    void normalMessageIsNotCorrection() {
        assertThat(correction.detect("今天天气怎么样")).isNull();
        assertThat(correction.detect("帮我写一份周报")).isNull();
        assertThat(correction.detect("")).isNull();
    }

    @Test
    void deleteStopsNextRoundRecall() {
        repository.upsert(new MemoryRecord(
                "m1", "alice", "用户喜欢喝茶", Map.of("type", "fact"), 0.9));

        String reply = correction.apply("alice",
                correction.detect("删掉你记的喜欢喝茶"), repository);

        assertThat(reply).contains("已修正记忆").contains("删除了 1 条");
        // 下一轮检索不再召回旧事实
        assertThat(repository.search("alice", "喝茶", 5)).isEmpty();
        // 软删除可恢复
        assertThat(repository.listInvalidated("alice", 10)).hasSize(1);
    }

    @Test
    void updateInvalidatesOldAndWritesNew() {
        repository.upsert(new MemoryRecord(
                "m1", "alice", "用户喜欢喝茶", Map.of("type", "fact"), 0.9));

        String reply = correction.apply("alice",
                correction.detect("把喜欢喝茶改成喜欢咖啡"), repository);

        assertThat(reply).contains("更新为").contains("喜欢咖啡");
        assertThat(repository.search("alice", "喝茶", 5)).isEmpty();
        assertThat(repository.search("alice", "喜欢咖啡", 5))
                .anyMatch(r -> r.content().contains("喜欢咖啡"));
    }

    @Test
    void deleteWithNoMatchRepliesHonestly() {
        String reply = correction.apply("alice",
                correction.detect("删掉你记的养了一只猫"), repository);

        assertThat(reply).contains("未找到").contains("养了一只猫");
    }
}
