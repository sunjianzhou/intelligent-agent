// R-04 记忆纠错闭环：MemoryView/MemoryCard 共用的纯列表状态转换。
// 后端软删除（失效）可恢复；这些函数只在本地列表上同步 API 结果。

export const isInvalidated = (mem) => !!(mem && mem.invalidated)

/** 失效后本地列表转换：标记 invalidated + 原因 + 时间。 */
export const invalidateLocal = (list, id, reason = '') =>
  list.map(m => (m.id === id
    ? { ...m, invalidated: true, invalidated_reason: reason, invalidated_at: new Date().toISOString() }
    : m))

/** 恢复后本地列表转换：清除失效标记。 */
export const restoreLocal = (list, id) =>
  list.map(m => (m.id === id
    ? { ...m, invalidated: false, invalidated_reason: '' }
    : m))

/** 置顶：importance 置 1.0。 */
export const pinLocal = (list, id) =>
  list.map(m => (m.id === id ? { ...m, importance: 1 } : m))

/** 默认视图过滤：未失效的才展示。 */
export const activeMemories = (list) => (list || []).filter(m => !isInvalidated(m))
