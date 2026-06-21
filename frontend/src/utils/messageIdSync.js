const PREFIX_LEN = 200

function prefixOf(content) {
  return (content || '').slice(0, PREFIX_LEN)
}

/**
 * 断流 fallback 用：把本地还没拿到真实 id 的消息，对齐到后端返回的消息列表上。
 *
 * 双重定位：
 *   1. 内容前缀匹配优先（不用完全相等——前端超长消息会被截断并加后缀标记，
 *      与后端存的完整内容不一致，只比前 200 字符）
 *   2. 匹配不到时退化为"同 role 里最近一条未被占用的"位置兜底
 *
 * 本地消息按传入顺序（应为时间正序）从后往前处理，每条匹配到的后端条目会被
 * 标记为已占用，避免重复匹配（典型场景：同一句话连续发了两次）。
 *
 * @param {Array<{role: string, content: string}>} pendingLocalMessages
 * @param {Array<{id: string, role: string, content: string}>} backendMessages
 * @returns {Map<object, string>} key 是传入的本地消息对象引用，value 是匹配到的后端 id
 */
export function resolvePendingMessageIds(pendingLocalMessages, backendMessages) {
  const result = new Map()
  const usedBackendIdx = new Set()

  for (let i = pendingLocalMessages.length - 1; i >= 0; i--) {
    const local = pendingLocalMessages[i]
    const localPrefix = prefixOf(local.content)

    let matchIdx = -1
    for (let j = backendMessages.length - 1; j >= 0; j--) {
      if (usedBackendIdx.has(j)) continue
      const b = backendMessages[j]
      if (b.role === local.role && prefixOf(b.content) === localPrefix) {
        matchIdx = j
        break
      }
    }

    if (matchIdx === -1) {
      for (let j = backendMessages.length - 1; j >= 0; j--) {
        if (usedBackendIdx.has(j)) continue
        if (backendMessages[j].role === local.role) {
          matchIdx = j
          break
        }
      }
    }

    if (matchIdx !== -1 && backendMessages[matchIdx].id) {
      result.set(local, backendMessages[matchIdx].id)
      usedBackendIdx.add(matchIdx)
    }
  }

  return result
}
