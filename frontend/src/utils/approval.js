/**
 * G6 HITL：把后端 approval_required 事件转换为前端消息对象。
 * 无 approval_id 或工具名时返回 null（调用方直接忽略）。
 */
export function approvalEventToMessage(data) {
  const a = data?.approval
  if (!a || !a.approval_id || !a.tool) return null
  return {
    role: 'approval',
    approval: { approval_id: a.approval_id, tool: a.tool, args: a.args || {} },
    status: 'pending',
    timestamp: new Date(),
  }
}
