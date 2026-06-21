import { describe, it, expect } from 'vitest'
import { resolvePendingMessageIds } from '../utils/messageIdSync'

describe('resolvePendingMessageIds', () => {
  it('内容前缀完全匹配时直接命中', () => {
    const local = { role: 'assistant', content: '你好，世界' }
    const backend = [
      { id: 'b-1', role: 'user', content: '你好' },
      { id: 'b-2', role: 'assistant', content: '你好，世界' },
    ]
    const result = resolvePendingMessageIds([local], backend)
    expect(result.get(local)).toBe('b-2')
  })

  it('前端超长消息被截断加后缀，仍能通过前缀匹配命中', () => {
    const longContent = 'x'.repeat(300)
    const local = { role: 'assistant', content: longContent.slice(0, 200) + '…（响应过长已截断）' }
    const backend = [{ id: 'b-1', role: 'assistant', content: longContent }]
    const result = resolvePendingMessageIds([local], backend)
    expect(result.get(local)).toBe('b-1')
  })

  it('内容不匹配时退化为同 role 位置兜底（取最近一条未占用的）', () => {
    const local = { role: 'assistant', content: '本地内容和后端不一样' }
    const backend = [
      { id: 'b-1', role: 'user', content: '提问' },
      { id: 'b-2', role: 'assistant', content: '完全不同的内容' },
    ]
    const result = resolvePendingMessageIds([local], backend)
    expect(result.get(local)).toBe('b-2')
  })

  it('多条本地消息按倒序匹配，且不会重复占用同一条后端消息', () => {
    const localA = { role: 'user', content: '第一句' }
    const localB = { role: 'assistant', content: '第二句回复' }
    const backend = [
      { id: 'b-1', role: 'user', content: '第一句' },
      { id: 'b-2', role: 'assistant', content: '第二句回复' },
    ]
    const result = resolvePendingMessageIds([localA, localB], backend)
    expect(result.get(localA)).toBe('b-1')
    expect(result.get(localB)).toBe('b-2')
  })

  it('后端完全没有对应消息时返回空 Map（不报错）', () => {
    const local = { role: 'user', content: '没人接收的消息' }
    const result = resolvePendingMessageIds([local], [])
    expect(result.has(local)).toBe(false)
  })

  it('两条本地消息内容相同时，分别匹配到不同的后端条目（不会都指向同一条）', () => {
    const localA = { role: 'user', content: '你好' }
    const localB = { role: 'user', content: '你好' }
    const backend = [
      { id: 'b-1', role: 'user', content: '你好' },
      { id: 'b-2', role: 'user', content: '你好' },
    ]
    const result = resolvePendingMessageIds([localA, localB], backend)
    const ids = [result.get(localA), result.get(localB)].sort()
    expect(ids).toEqual(['b-1', 'b-2'])
  })
})
