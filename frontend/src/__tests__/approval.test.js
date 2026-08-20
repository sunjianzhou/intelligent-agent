import { describe, it, expect } from 'vitest'
import { approvalEventToMessage } from '@/utils/approval'

describe('approvalEventToMessage', () => {
  it('converts WS approval event into a pending approval message', () => {
    const msg = approvalEventToMessage({
      approval: {
        approval_id: 'aprv_abc',
        tool: 'channel_message',
        args: { message: '大家好' },
      },
    })
    expect(msg).not.toBeNull()
    expect(msg.role).toBe('approval')
    expect(msg.status).toBe('pending')
    expect(msg.approval.approval_id).toBe('aprv_abc')
    expect(msg.approval.tool).toBe('channel_message')
    expect(msg.approval.args).toEqual({ message: '大家好' })
  })

  it('returns null when approval data is unusable', () => {
    expect(approvalEventToMessage({})).toBeNull()
    expect(approvalEventToMessage({ approval: { tool: 'channel_message' } })).toBeNull()
    expect(approvalEventToMessage({ approval: { approval_id: 'x' } })).toBeNull()
    expect(approvalEventToMessage(null)).toBeNull()
  })
})
