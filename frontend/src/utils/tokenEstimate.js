// R-01：与后端 ContextBudget 完全一致的 token 估算规则
// CJK ≈ 1 token/字，其余 ≈ 0.25 token/字符（向上取整）；每条消息另计固定格式开销。
const CJK_RE = /[\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]/g

export const MESSAGE_OVERHEAD_TOKENS = 4

export const estimateTokens = (text) => {
  const s = String(text ?? '')
  const cjk = (s.match(CJK_RE) || []).length
  const non = s.length - cjk
  return cjk + Math.ceil(non * 0.25)
}

export const estimateMessages = (messages) => {
  if (!messages) return 0
  return messages.reduce(
    (sum, m) => sum + MESSAGE_OVERHEAD_TOKENS + estimateTokens(m?.content || ''),
    0,
  )
}
