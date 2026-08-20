import { describe, it, expect } from 'vitest'
import { planEventToMessage } from '@/utils/plan'

describe('planEventToMessage', () => {
  it('converts WS plan event with steps into a plan message', () => {
    const msg = planEventToMessage({
      plan: {
        steps: [
          { title: '查天气', detail: '搜索明日天气数据' },
          { title: '计算着装' },
        ],
      },
    })
    expect(msg).not.toBeNull()
    expect(msg.role).toBe('plan')
    expect(msg.steps).toHaveLength(2)
    expect(msg.steps[0]).toEqual({ title: '查天气', detail: '搜索明日天气数据' })
    expect(msg.steps[1].detail).toBe('')
  })

  it('accepts SSE-style data with top-level steps', () => {
    const msg = planEventToMessage({
      steps: [{ title: '第一步' }, { title: '第二步' }],
    })
    expect(msg).not.toBeNull()
    expect(msg.steps.map(s => s.title)).toEqual(['第一步', '第二步'])
  })

  it('accepts plain-string steps', () => {
    const msg = planEventToMessage({ plan: { steps: ['先查资料', '再写总结'] } })
    expect(msg).not.toBeNull()
    expect(msg.steps[0].title).toBe('先查资料')
    expect(msg.steps[0].detail).toBe('')
  })

  it('returns null when no usable steps', () => {
    expect(planEventToMessage({})).toBeNull()
    expect(planEventToMessage({ plan: { steps: [] } })).toBeNull()
    expect(planEventToMessage({ plan: { steps: [{ title: '  ' }] } })).toBeNull()
    expect(planEventToMessage(null)).toBeNull()
  })
})
