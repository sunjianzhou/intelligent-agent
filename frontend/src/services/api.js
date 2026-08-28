import { useAuthStore } from '@/stores/auth'
import { useErrorBusStore } from '@/stores/errorBus'
import { ElMessage } from 'element-plus'

const BASE = '/api'

// 静默端点：这些接口失败不弹 toast（轮询类接口，失败是正常状态）
const SILENT_URLS = ['/api/health', '/api/python/health', '/api/system/info', '/api/system/resources']

const _REQUEST_TIMEOUT_MS = 30000

const request = async (url, options = {}) => {
  const authStore = useAuthStore()
  const errorBus  = useErrorBusStore()
  const silent    = SILENT_URLS.some(p => url === p)

  // FormData 传入时不设 Content-Type，让浏览器自动添加 multipart boundary
  const isFormData = options.body instanceof FormData
  const headers = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(authStore.token ? { 'Authorization': `Bearer ${authStore.token}` } : {}),
    ...(options.headers || {}),
  }

  // 支持调用方通过 options.timeout 自定义超时（ms），默认 30s
  const timeoutMs = options.timeout || _REQUEST_TIMEOUT_MS
  const controller = new AbortController()
  const timeoutId  = setTimeout(() => controller.abort(), timeoutMs)

  try {
    const res = await fetch(url, {
      ...options,
      headers,
      cache: 'no-store',
      signal: controller.signal,
    })
    clearTimeout(timeoutId)

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

    if (!res.ok) {
      // 尽量取服务端错误消息（如 503 的"服务繁忙，请稍后再试"），避免只显示 HTTP 状态码
      let serverMsg = null
      try {
        const body = await res.json()
        serverMsg = body?.data?.message || body?.message || null
      } catch (e) {
        // 非 JSON 错误体，忽略
      }
      const friendly = res.status === 503 ? '服务繁忙，请稍后再试' : null
      throw new Error(serverMsg || friendly || `HTTP ${res.status}`)
    }
    return await res.json()
  } catch (err) {
    clearTimeout(timeoutId)
    const isTimeout = err.name === 'AbortError'
    const msg = isTimeout
      ? `请求超时：${url.replace(BASE, '')}（${timeoutMs / 1000}s）`
      : `请求失败：${url.replace(BASE, '')} (${err.message})`
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

export const invalidateMemory = (id, reason) =>
  request(`${BASE}/memory/${id}/invalidate`, {
    method: 'POST', body: JSON.stringify({ reason })
  })

export const restoreMemory = (id) =>
  request(`${BASE}/memory/${id}/restore`, { method: 'POST' })

export const getInvalidatedMemories = (limit = 50) =>
  request(`${BASE}/memory/invalidated?limit=${limit}`)

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
export const getUsageStats = (username, month) =>
  request(`${BASE}/analytics/usage/${username}${month ? `?month=${month}` : ''}`)
export const getUsageQuota = (username) =>
  request(`${BASE}/analytics/usage-quota/${username}`)

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

// ── Conversations history ─────────────────────────────────────────────────────

export const listConversations   = () => request(`${BASE}/conversations`)
export const getConversation     = (id) => request(`${BASE}/conversations/${encodeURIComponent(id)}`)
export const deleteConversation  = (id) => request(`${BASE}/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const clearConversations  = () => request(`${BASE}/conversations`, { method: 'DELETE' })
export const renameConversation  = (id, title) =>
  request(`${BASE}/conversations/${encodeURIComponent(id)}/rename`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
export const exportConversation  = (id) =>
  request(`${BASE}/conversations/${encodeURIComponent(id)}/export`)
export const branchConversation  = (messages, parentSessionId) =>
  request(`${BASE}/conversations/branch`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ messages, parent_session_id: parentSessionId }),
  })
export const retractMessages = (sessionId, messageIds) =>
  request(`${BASE}/conversations/${encodeURIComponent(sessionId)}/retract`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ message_ids: messageIds }),
  })

// ── Image generation ──────────────────────────────────────────────────────────

export const getImageProviderStatus = () => request(`${BASE}/image/provider-status`)
export const getImageProgress       = () => request(`${BASE}/image/progress`)
export const listImageModels        = () => request(`${BASE}/image/models`)
export const switchImageModel       = (model) =>
  request(`${BASE}/image/switch-model`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model }),
  })
export const listImageLoras = () => request(`${BASE}/image/loras`)
export const listImageControlNets = () => request(`${BASE}/image/controlnets`)
export const getComfyuiWorkflow = () => request(`${BASE}/image/comfyui-workflow`)
export const saveComfyuiWorkflow = (workflow) =>
  request(`${BASE}/image/comfyui-workflow`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ workflow }),
  })
export const resetComfyuiWorkflow = () =>
  request(`${BASE}/image/comfyui-workflow`, { method: 'DELETE' })
// 高步数/CFG 时本地推理可能超过 30s 默认超时，放宽到 5 分钟
export const generateImage = (params) =>
  request(`${BASE}/image/generate`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params), timeout: 300000,
  })
export const listGeneratedImages = () => request(`${BASE}/images`)
export const deleteGeneratedImage = (filename) =>
  request(`${BASE}/images/${encodeURIComponent(filename)}`, { method: 'DELETE' })

// ── HITL approval (G6) ───────────────────────────────────────────────────────
export const decideApproval = (approvalId, approved) =>
  request(`${BASE}/approvals/${encodeURIComponent(approvalId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approved }),
  })

// ── Knowledge files ───────────────────────────────────────────────────────────

export const listKnowledgeFiles  = () => request(`${BASE}/knowledge/files`)
export const deleteKnowledgeFile = (fileId) =>
  request(`${BASE}/knowledge/files/${encodeURIComponent(fileId)}`, { method: 'DELETE' })
export const uploadKnowledgeFile = (file, description = '') => {
  const fd = new FormData()
  fd.append('file', file)
  if (description) fd.append('description', description)
  // 传入 FormData，request() 内部会跳过 Content-Type 设置，让浏览器自动加 multipart boundary
  // timeout: 60000 — 文件上传允许更长的超时（是通用 30s 的 2 倍）
  return request(`${BASE}/knowledge/upload`, { method: 'POST', body: fd, timeout: 60000 })
}

// ── Roles ─────────────────────────────────────────────────────────────────────

export const listRolesApi      = () => request(`${BASE}/roles`)
export const getActiveRoleApi  = () => request(`${BASE}/roles/activate`)
export const activateRoleApi   = (roleId) =>
  request(`${BASE}/roles/activate`, { method: 'POST', body: JSON.stringify({ role_id: roleId }) })
export const deactivateRoleApi = () => request(`${BASE}/roles/activate`, { method: 'DELETE' })
export const syncRoleToServer  = (roleId, data) =>
  request(`${BASE}/roles/${encodeURIComponent(roleId)}`, { method: 'PUT', body: JSON.stringify(data) })

// ── 云端服务商配置 ─────────────────────────────────────────
export const listCloudProviders     = () => request(`${BASE}/cloud/providers`)
export const getCloudPresets        = () => request(`${BASE}/cloud/presets`)
export const createCloudProvider    = (data) =>
  request(`${BASE}/cloud/providers`, { method: 'POST', body: JSON.stringify(data) })
export const updateCloudProvider    = (id, data) =>
  request(`${BASE}/cloud/providers/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteCloudProvider    = (id) =>
  request(`${BASE}/cloud/providers/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const activateCloudProvider  = (id) =>
  request(`${BASE}/cloud/providers/${encodeURIComponent(id)}/activate`, { method: 'POST' })
export const deactivateCloudProviders = () =>
  request(`${BASE}/cloud/deactivate`, { method: 'POST' })

// ── Agent 运行追踪（G4）───────────────────────────────────
export const getTraces      = (limit = 50) => request(`${BASE}/traces?limit=${limit}`)
export const getTrace       = (requestId) =>
  request(`${BASE}/traces/${encodeURIComponent(requestId)}`)
export const deleteTrace    = (requestId) =>
  request(`${BASE}/traces/${encodeURIComponent(requestId)}`, { method: 'DELETE' })

// ── MCP 服务器（G2）───────────────────────────────────────
export const listMcpServers    = () => request(`${BASE}/mcp/servers`)
export const getMcpServer      = (id) => request(`${BASE}/mcp/servers/${encodeURIComponent(id)}`)
export const createMcpServer   = (data) =>
  request(`${BASE}/mcp/servers`, { method: 'POST', body: JSON.stringify(data) })
export const updateMcpServer   = (id, data) =>
  request(`${BASE}/mcp/servers/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteMcpServer   = (id) =>
  request(`${BASE}/mcp/servers/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const connectMcpServer  = (id) =>
  request(`${BASE}/mcp/servers/${encodeURIComponent(id)}/connect`, { method: 'POST' })
export const disconnectMcpServer = (id) =>
  request(`${BASE}/mcp/servers/${encodeURIComponent(id)}/disconnect`, { method: 'POST' })
