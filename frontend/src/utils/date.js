const _locale = 'zh-CN'

/**
 * "10:47" — 聊天消息时间戳
 */
export const formatTime = (d) => {
  const date = d instanceof Date ? d : new Date(d)
  if (isNaN(date)) return '-'
  return date.toLocaleTimeString(_locale, { hour: '2-digit', minute: '2-digit' })
}

/**
 * "05-31 10:47" — 记忆条目、侧边栏等紧凑场景
 */
export const formatDateTime = (d) => {
  if (!d) return '-'
  const date = d instanceof Date ? d : new Date(d)
  if (isNaN(date)) return '-'
  return date.toLocaleString(_locale, {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

/**
 * "2026-05-31 10:47" — 统计/报表里的完整时间，替代 ISO.slice(0,16).replace('T',' ')
 */
export const formatISOShort = (iso) => {
  if (!iso) return '-'
  return iso.slice(0, 16).replace('T', ' ')
}

/**
 * 当前时间的文件名安全字符串，如 "2026-05-31_10-47-22"
 */
export const formatForFilename = () =>
  new Date().toLocaleString(_locale)
    .replace(/[/:]/g, '-')
    .replace(' ', '_')
