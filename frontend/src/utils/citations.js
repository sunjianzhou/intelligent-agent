// R-05 知识问答引用：去重与追加（供 websocket store 使用）。

export const citationKey = (c) => {
  const fileId = c?.file_id
  if (!fileId) return ''
  return `${fileId}#${c?.chunk_index ?? ''}`
}

/** 追加引用并按 file_id#chunk_index 去重；无效引用忽略。 */
export const appendCitation = (citations, citation) => {
  const key = citationKey(citation)
  if (!key) return citations || []
  if ((citations || []).some(c => citationKey(c) === key)) return citations
  return [...(citations || []), citation]
}
