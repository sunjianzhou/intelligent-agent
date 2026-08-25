import { describe, expect, it } from 'vitest'
import {
  isInvalidated,
  invalidateLocal,
  restoreLocal,
  pinLocal,
  activeMemories,
} from '@/utils/memoryActions'

const mem = { id: 'm1', content: '用户喜欢喝茶', importance: 0.5 }

describe('memoryActions（R-04 记忆纠错本地状态转换）', () => {
  it('失效后标记 invalidated + 原因，并从默认视图过滤', () => {
    const list = invalidateLocal([mem], 'm1', '记错了')

    expect(isInvalidated(list[0])).toBe(true)
    expect(list[0].invalidated_reason).toBe('记错了')
    expect(list[0].invalidated_at).toBeTruthy()
    expect(activeMemories(list)).toHaveLength(0)
    expect(activeMemories([mem])).toHaveLength(1)
  })

  it('恢复后清除失效标记并回到默认视图', () => {
    const invalidated = invalidateLocal([mem], 'm1', '过时')
    const restored = restoreLocal(invalidated, 'm1')

    expect(isInvalidated(restored[0])).toBe(false)
    expect(restored[0].invalidated_reason).toBe('')
    expect(activeMemories(restored)).toHaveLength(1)
  })

  it('置顶把 importance 置为 1', () => {
    const list = pinLocal([mem], 'm1')
    expect(list[0].importance).toBe(1)
  })
})
