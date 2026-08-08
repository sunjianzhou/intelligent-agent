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

    /** 作用域删除：仅当记录属于该用户时删除；不存在或属他人返回 false。 */
    boolean delete(String userId, String memoryId);
}
