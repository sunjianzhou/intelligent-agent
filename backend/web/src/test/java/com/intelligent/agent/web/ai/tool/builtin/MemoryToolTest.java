package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式记忆工具契约（2026-08-15 补齐，对齐 Python store_memory / search_memories）：
 * userId 取自执行上下文，写入与检索按用户隔离。
 */
class MemoryToolTest {

    @Test
    void storeMemoryUpsertsRecordWithContextUser() {
        VectorMemoryRepository repository = new VectorMemoryRepository();
        MemoryTool tool = new MemoryTool(MemoryTool.STORE_MEMORY, repository);

        Object result = tool.execute(
                Map.of("content", "用户偏好深色模式", "category", "preference", "importance", 0.9),
                ToolExecutionContext.of("alice", "user"));

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("success")).isEqualTo(true);
        assertThat(repository.count(MemorySearchQuery.builder("alice", "", 10).build()))
                .isEqualTo(1);
        MemoryRecord record = repository.list(
                MemorySearchQuery.builder("alice", "", 10).build()).get(0);
        assertThat(record.content()).isEqualTo("用户偏好深色模式");
        assertThat(record.type()).isEqualTo("preference");
        assertThat(record.importance()).isEqualTo(0.9);
    }

    @Test
    void searchMemoriesReturnsMatchingRecordsForSameUserOnly() {
        VectorMemoryRepository repository = new VectorMemoryRepository();
        MemoryTool store = new MemoryTool(MemoryTool.STORE_MEMORY, repository);
        store.execute(Map.of("content", "张三的生日是 1 月 1 日"), ToolExecutionContext.of("alice", "user"));
        store.execute(Map.of("content", "李四的生日是 2 月 2 日"), ToolExecutionContext.of("bob", "user"));
        MemoryTool search = new MemoryTool(MemoryTool.SEARCH_MEMORIES, repository);

        Object result = search.execute(
                Map.of("query", "生日", "limit", 5), ToolExecutionContext.of("alice", "user"));

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.get("success")).isEqualTo(true);
        assertThat((List<?>) map.get("results")).hasSize(1);
    }

    @Test
    void blankContentRejected() {
        MemoryTool tool = new MemoryTool(MemoryTool.STORE_MEMORY, new VectorMemoryRepository());

        Object result = tool.execute(Map.of("content", "  "), ToolExecutionContext.of("alice", "user"));

        assertThat(((Map<?, ?>) result).get("success")).isEqualTo(false);
    }
}
