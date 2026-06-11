import { useAuthStore } from '@/stores/auth'
import { useErrorBusStore } from '@/stores/errorBus'
import { ElMessage } from 'element-plus'

const BASE = '/api'

// 静默端点：这些接口失败不弹 toast（轮询类接口，失败是正常状态）
const SILENT_URLS = ['/api/health', '/api/python/health', '/api/system/info', '/api/system/resources']

const request = async (url, options = {}) => {
  const authStore = useAuthStore()
  const errorBus  = useErrorBusStore()
  const silent    = SILENT_URLS.some(p => url === p)

  const headers = {
    'Content-Type': 'application/json',
    ...(authStore.token ? { 'Authorization': `Bearer ${authStore.token}` } : {}),
    ...(options.headers || {}),
  }

  try {
    const res = await fetch(url, {
      ...options,
      headers,
      cache: 'no-store',
    })

    // 处理 token 续期
    const newToken = res.headers.get('X-New-Token')
    if (newToken) authStore.refreshToken(newToken)

    // 处理 401 跳转登录
    if (res.status === 401) {
      authStore.logout()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
      return null
    }

    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return await res.json()
  } catch (err) {
    const msg = `请求失败：${url.replace(BASE, '')} (${err.message})`
    console.error('[API]', msg)
    errorBus.push(msg, 'error', url)
    if (!silent) {
      ElMessage({ message: msg, type: 'error', duration: 4000, showClose: true })
    }
    return null
  }
}

export const getJavaHealth   = () => request(`${BASE}/health`)
export const getPythonHealth = () => request(`${BASE}/python/health`)
export const getSystemInfo   = () => request(`${BASE}/system/info`)
export const getModels       = () => request(`${BASE}/models`)
export const getTools        = () => request(`${BASE}/tools/list`)

export const getMemoryList   = (type = 'long_term', limit = 50) =>
  request(`${BASE}/memory/list?memory_type=${type}&limit=${limit}`)

export const deleteMemory    = (id) =>
  request(`${BASE}/memory/${id}`, { method: 'DELETE' })

export const updateMemoryImportance = (id, importance) =>
  request(`${BASE}/memory/${id}/importance`, {
    method: 'PATCH', body: JSON.stringify({ importance })
  })

export const searchMemory    = (q, limit = 10) =>
  request(`${BASE}/memory/search?q=${encodeURIComponent(q)}&limit=${limit}`)

export const clearAllMemory  = () =>
  request(`${BASE}/memory`, { method: 'DELETE' })
export const distillMemory   = () =>
  request(`${BASE}/memory/distill`, { method: 'POST' })

export const batchImportMemory = (items) =>
  request(`${BASE}/memory/batch-import`, {
    method: 'POST', body: JSON.stringify({ items })
  })

export const getMemoryStats      = () => request(`${BASE}/memory`)
export const getMemorySummaries  = (limit = 30) => request(`${BASE}/memory/summaries?limit=${limit}`)
export const exportMemory        = (format = 'json') => `${BASE}/memory/export?format=${format}`

export const switchModel = (modelName) =>
  request(`${BASE}/model/switch`, {
    method: 'POST',
    body: JSON.stringify({ model: modelName })
  })

export const getRuntimeConfig  = () => request(`${BASE}/config/runtime`)
export const updateRuntimeConfig = (data) =>
  request(`${BASE}/config/runtime`, { method: 'PATCH', body: JSON.stringify(data) })

export const getTasksList    = (status, limit = 50) => {
  const params = new URLSearchParams({ limit })
  if (status) params.append('status', status)
  return request(`${BASE}/tasks/list?${params}`)
}
export const createTask      = (data) =>
  request(`${BASE}/tasks/create`, { method: 'POST', body: JSON.stringify(data) })
export const deleteTask      = (id) =>
  request(`${BASE}/tasks/${id}`, { method: 'DELETE' })
export const cancelTask      = (id) =>
  request(`${BASE}/tasks/${id}/cancel`, { method: 'POST' })
export const updateTask      = (id, data) =>
  request(`${BASE}/tasks/${id}`, { method: 'PATCH', body: JSON.stringify(data) })
export const executeTaskNow  = (id) =>
  request(`${BASE}/tasks/${id}/execute`, { method: 'POST' })
export const getTaskStats    = () => request(`${BASE}/tasks/stats`)
export const getTaskActions  = () => request(`${BASE}/tasks/actions`)

export const getSystemResources = () => request(`${BASE}/system/resources`)

export const getSkills      = (tag, enabledOnly = false) =>
  request(`${BASE}/skills?enabled_only=${enabledOnly}${tag ? '&tag=' + tag : ''}`)
export const createSkill    = (data) =>
  request(`${BASE}/skills`, { method: 'POST', body: JSON.stringify(data) })
export const updateSkill    = (id, data) =>
  request(`${BASE}/skills/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteSkill    = (id) =>
  request(`${BASE}/skills/${id}`, { method: 'DELETE' })
export const toggleSkill    = (id) =>
  request(`${BASE}/skills/${id}/toggle`, { method: 'PATCH' })

export const getSkillTemplates = () =>
  request(`${BASE}/skills/templates/list`)
export const applySkillTemplate = (templateId) =>
  request(`${BASE}/skills/templates/${templateId}/apply`, { method: 'POST' })

export const submitFeedback = (data) =>
  request(`${BASE}/analytics/feedback`, {
    method: 'POST',
    body: JSON.stringify(data)
  })

export const getAnalyticsStats  = (username) =>
  request(`${BASE}/analytics/stats/${username}`)

export const getAnalyticsRecords = (username, limit = 50, rating = null) => {
  const params = new URLSearchParams({ limit })
  if (rating) params.append('rating', rating)
  return request(`${BASE}/analytics/records/${username}?${params}`)
}

export const getSkillLogs  = (username, limit = 100, skillName = null) => {
  const params = new URLSearchParams({ limit })
  if (skillName) params.append('skill_name', skillName)
  return request(`${BASE}/analytics/skill-logs/${username}?${params}`)
}

export const pollNotifications = () => request(`${BASE}/notifications/poll`)

export const getSkillStats = (username) =>
  request(`${BASE}/analytics/skill-stats/${username}`)

export const getToolCalls  = (limit = 50, toolName = null) => {
  const params = new URLSearchParams({ limit })
  if (toolName) params.append('tool_name', toolName)
  return request(`${BASE}/analytics/tool-calls?${params}`)
}
export const getToolStats = () => request(`${BASE}/analytics/tool-stats`)

// ── Projects CRUD ─────────────────────────────────────────────────────────────

export const listProjects    = () => request(`${BASE}/projects`)
export const createProject   = (data) => request(`${BASE}/projects`, { method: 'POST', body: JSON.stringify(data) })
export const getProjectById  = (id) => request(`${BASE}/projects/${encodeURIComponent(id)}`)
export const updateProject   = (id, data) => request(`${BASE}/projects/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteProjectApi = (id) => request(`${BASE}/projects/${encodeURIComponent(id)}`, { method: 'DELETE' })

// ── Project API ───────────────────────────────────────────────────────────────

export const putProjectSpec = (data) =>
  request(`${BASE}/project/spec`, { method: 'PUT', body: JSON.stringify(data) })

export const getProjectSpec = (projectId) =>
  request(`${BASE}/project/spec?project_id=${encodeURIComponent(projectId)}`)

export const extractProjectContext = (projectId, userId) =>
  request(`${BASE}/project/context/extract`, {
    method: 'POST',
    body: JSON.stringify({ project_id: projectId, user_id: userId }),
  })

export const getProjectContext = (projectId, query, limit = 5) =>
  request(`${BASE}/project/context?project_id=${encodeURIComponent(projectId)}&query=${encodeURIComponent(query)}&limit=${limit}`)

export const decomposeProjectTasks = (projectId, taskDescription) =>
  request(`${BASE}/project/tasks/decompose`, {
    method: 'POST',
    body: JSON.stringify({ project_id: projectId, task_description: taskDescription }),
  })

export const getProjectTasks = (projectId) =>
  request(`${BASE}/project/tasks?project_id=${encodeURIComponent(projectId)}`)