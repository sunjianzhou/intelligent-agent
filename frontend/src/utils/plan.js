/**
 * G6 planning 前置：把后端 plan 事件转换为前端消息对象。
 * WS 协议为 { plan: { steps: [...] } }，SSE 透传为 { steps: [...] }。
 * 无有效步骤时返回 null（调用方直接忽略）。
 */
export function planEventToMessage(data) {
  const steps = data?.plan?.steps || data?.steps || []
  if (!Array.isArray(steps) || !steps.length) return null

  const clean = steps
    .map(s => (typeof s === 'string' ? { title: s, detail: '' } : s))
    .filter(s => s && typeof s.title === 'string' && s.title.trim())
    .map(s => ({ title: s.title.trim(), detail: (s.detail || '').trim() }))

  if (!clean.length) return null
  return { role: 'plan', steps: clean, timestamp: new Date() }
}
