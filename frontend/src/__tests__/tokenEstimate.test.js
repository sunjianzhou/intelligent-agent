import { describe, expect, it } from 'vitest'
import { estimateTokens, estimateMessages, MESSAGE_OVERHEAD_TOKENS } from '@/utils/tokenEstimate'

describe('tokenEstimate（与后端 ContextBudget 同规则）', () => {
  it('CJK 每字 1 token', () => {
    expect(estimateTokens('你好世界')).toBe(4)
    expect(estimateTokens('カタカナ')).toBe(4)
  })

  it('非 CJK 每 4 字符约 1 token（向上取整）', () => {
    expect(estimateTokens('abcdefgh')).toBe(2)
    expect(estimateTokens('abc')).toBe(1)
  })

  it('空文本为 0', () => {
    expect(estimateTokens('')).toBe(0)
    expect(estimateTokens(null)).toBe(0)
    expect(estimateTokens(undefined)).toBe(0)
  })

  it('消息列表按条计固定开销', () => {
    const messages = [
      { role: 'user', content: '你好' },
      { role: 'assistant', content: 'hi' },
    ]
    expect(estimateMessages(messages)).toBe(
      2 * MESSAGE_OVERHEAD_TOKENS + estimateTokens('你好') + estimateTokens('hi'),
    )
    expect(estimateMessages(null)).toBe(0)
  })
})
