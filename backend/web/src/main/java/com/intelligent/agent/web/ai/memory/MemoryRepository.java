package com.intelligent.agent.web.ai.memory;

import java.util.List;

/**
 * 记忆仓库端口（Plan 2 / Task 1）。
 * <p>
 * 所有操作都按用户作用域隔离：search 只返回 query.userId 的记录，
 * delete 只允许删除属于该用户的记录。
 */
public interface MemoryRepository {

    /** 写入或覆盖（同 id）一条记忆。 */
    void upsert(MemoryRecord record);

    /** 便捷检索：按用户隔离 + 语义相似度返回 top N。 */
    List<MemoryRecord> search(String userId, String text, int limit);

    /** 完整检索：支持 role_id / project_id / type / importance 过滤。 */
    List<MemoryRecord> search(MemorySearchQuery query);

    /** 按过滤条件列出记录（无需查询文本，按创建时间倒序）。 */
    List<MemoryRecord> list(MemorySearchQuery filter);

    /** 按过滤条件计数。 */
    int count(MemorySearchQuery filter);

    /** 清空某用户全部记忆。 */
    void clear(String userId);

    /** 作用域删除：仅当记录属于该用户时删除；不存在或属他人返回 false。 */
    boolean delete(String userId, String memoryId);

    // ── R-04 软删除/失效（可恢复） ───────────────────────────────────────

    /** 软删除：标记 invalidated=true（含原因与时间），检索层不再召回；可恢复。 */
    default boolean invalidate(String userId, String memoryId, String reason) {
        return false;
    }

    /** 恢复软删除记录：清除 invalidated 标记，重新进入检索。 */
    default boolean restore(String userId, String memoryId) {
        return false;
    }

    /** 列出该用户已失效（软删除）的记录，供 MemoryView 恢复入口使用。 */
    default List<MemoryRecord> listInvalidated(String userId, int limit) {
        return List.of();
    }
}
