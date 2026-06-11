import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { isTokenExpired } from '@/utils/jwt'
import { formatTime } from '@/utils/date'
import { genId } from '@/utils/string'
import {
  switchModel as apiSwitchModel,
  getModels as apiGetModels,
  clearAllMemory as apiClearAllMemory,
} from '@/services/api'
import { useProjectStore } from '@/stores/project'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

function _newSessionId() {
  return 'sess_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7)
}

export const useWebSocketStore = defineStore('websocket', () => {
  // ── 状态 ────────────────────────────────────────────────
  const isConnected      = ref(false)
  const messages         = ref([])
  const systemInfo       = ref(null)
  const lastResponseTime = ref(null)
  const error            = ref(null)
  const isMockMode       = ref(false)
  const isStreaming      = ref(false)
  const streamingIndex   = ref(-1)
  const activeToolSteps  = ref([])   // 工具执行中的实时步骤列表
  // Increments on every chat_done / error so ChatView can watch and reset local isThinking
  const chatEndSignal    = ref(0)
  const currentModel     = ref('')
  const availableModels  = ref([])
  const cloudMode        = ref(false)
  const cloudModel       = ref('')
  const responseTimes = ref([])   // 最近20次响应时间
  const currentSessionId = ref(localStorage.getItem('ia_session_id') || _newSessionId())
  let ws = null
  let heartbeatTimer = null       // 定期 REST 心跳，触发 X-New-Token 续期
  let _historyLoaded = false      // 每次登录只加载一次历史，重连时不覆盖内存消息
  const _shownNotifKeys = new Set() // 通知去重：防止 WS 重连时同一条通知被重复展示
  let _manualClose = false        // cancelStreaming 主动关闭时，阻止 onclose 的自动重连

  // ── Token 工具 ────────────────────────────────────────────
  const redirectToLogin = () => {
    localStorage.removeItem('agent_token')
    window.location.href = '/login'
  }

  /** 调用任意 REST 接口以触发 X-New-Token 滑动续期 */
  const pingRestForRenewal = async () => {
    try {
      const token = localStorage.getItem('agent_token')
      if (!token || isTokenExpired(token)) return
      await fetch('/api/health', {
        headers: { Authorization: `Bearer ${token}` }
      })
    } catch {
      // 网络失败不影响 WS 连接
    }
  }

  // ── 计算属性 ─────────────────────────────────────────────
  const connectionStatus = computed(() => {
    if (isMockMode.value) return isConnected.value ? '模拟模式' : '模拟未连接'
    return isConnected.value ? '已连接' : '未连接'
  })

  const modelStatus = computed(() => {
    // cloudMode comes from REST /api/models (per-user, authoritative).
    // systemInfo.cloud_mode is global config — intentionally excluded here
    // to prevent the global cloud setting from overriding a per-user local mode.
    if (cloudMode.value && cloudModel.value) return `${cloudModel.value} ☁`
    return currentModel.value || systemInfo.value?.agent_model || '未知'
  })

  // ── WebSocket 连接 ────────────────────────────────────────
  // 默认 URL 走当前页面同源，HTTPS 自动升级为 WSS，避免 Docker/反代场景下硬编码端口
  const _defaultWsUrl = () => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws`
  }

  const connect = (url) => {
    const wsTarget = url || _defaultWsUrl()
    // 同时检查 OPEN(1) 和 CONNECTING(0)，防止并发调用泄漏 WebSocket 对象
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
    if (USE_MOCK) { enableMockMode(); return }

    const token = localStorage.getItem('agent_token') || ''
    if (!token) {
      console.log('[WS] 未登录，跳过连接')
      return
    }

    // 仅首次连接（非重连）加载历史，避免重连时覆盖内存中的新消息
    if (!_historyLoaded) {
      _loadChatHistory()
      _historyLoaded = true
    }

    const wsUrl = `${wsTarget}?token=${token}`
    console.log('[WS] 连接:', wsUrl)
    ws = new WebSocket(wsUrl)

    ws.onopen = () => {
      isConnected.value = true
      error.value = null
      console.log('[WS] 连接成功')
      // 每 10 分钟 REST 心跳，确保长时间 WS 会话的 Token 自动续期
      clearInterval(heartbeatTimer)
      heartbeatTimer = setInterval(pingRestForRenewal, 10 * 60 * 1000)
    }

    ws.onmessage = (event) => {
      try {
        handleMessage(JSON.parse(event.data))
      } catch {
        const cleaned = typeof event.data === 'string'
          ? event.data.replace(/^Hello from server: /, '')
          : event.data
        try {
          handleMessage(JSON.parse(cleaned))
        } catch {
          console.error('[WS] 消息解析失败:', event.data)
        }
      }
    }

    ws.onerror = (err) => {
      console.error('[WS] 连接错误:', err)
      isConnected.value = false
      error.value = err
      addMessage({ role: 'system', content: 'WebSocket 连接错误，请检查后端是否运行', timestamp: new Date() })
    }

    ws.onclose = () => {
      console.log('[WS] 连接关闭')
      isConnected.value = false
      clearInterval(heartbeatTimer)
      heartbeatTimer = null

      // cancelStreaming 已负责 500ms 后重连，此处不再追加第二个重连定时器
      if (_manualClose) {
        console.log('[WS] 主动关闭，跳过自动重连')
        return
      }

      const token = localStorage.getItem('agent_token')
      if (!token) return

      // Token 过期时跳登录，避免无限重连死循环
      if (isTokenExpired(token)) {
        console.warn('[WS] Token 已过期，跳转登录页')
        redirectToLogin()
        return
      }

      // Token 有效，5 秒后重连（走同源 URL，自动适配 HTTPS/Docker/反代）
      setTimeout(() => {
        if (!isConnected.value) connect()
      }, 5000)
    }
  }

  // ── Mock 模式 ─────────────────────────────────────────────
  const enableMockMode = async () => {
    const { WebSocketMock } = await import('@/services/websocket-mock')
    console.log('[WS] 启用模拟模式')
    isMockMode.value = true
    ws = new WebSocketMock('ws://localhost:8080/ws')

    ws.onopen    = () => { isConnected.value = true; error.value = null }
    ws.onmessage = (event) => { try { handleMessage(JSON.parse(event.data)) } catch {} }
    ws.onclose   = () => { isConnected.value = false }
    ws.onerror   = (err) => { error.value = err; isConnected.value = false }
  }

  // ── 消息处理 ──────────────────────────────────────────────
  const handleMessage = (data) => {
    switch (data.type) {

      case 'connection_established':
        isConnected.value = true
        break

      case 'system_info':
        systemInfo.value = data.info
        break

      case 'thinking':
        // isThinking 由 ChatView 本地管理；此处无需处理
        break

      case 'tool_call_start':
        // 单个工具启动，追加到进度列表
        activeToolSteps.value.push({
          tool_name:    data.tool_data?.tool_name || '未知工具',
          args_summary: data.tool_data?.args_summary || '',
          status:       'running',
        })
        break

      case 'tool_calls_done':
        // 工具调用完成：清空进度列表，插入结果卡片
        activeToolSteps.value = []
        if (data.tool_calls && data.tool_calls.length > 0) {
          // 检测是否有任务创建工具调用，设置提示 flag
          const taskCreated = data.tool_calls.some(tc =>
            tc.tool === 'create_reminder' || tc.tool === 'create_periodic_reminder'
          )
          addMessage({
            role:        'tool_calls',
            toolCalls:   data.tool_calls,
            taskCreated: taskCreated,  // 用于在 UI 显示"查看任务"提示
            timestamp:   new Date()
          })
        }
        break

      case 'chat_token':
        // 流式 token，逐字追加
        appendToken(data.token)
        break

      case 'chat_done':
        finalizeStream(data.response_time)
        lastResponseTime.value = data.response_time
        if (data.response_time != null) {
          responseTimes.value.push({ time: data.response_time, ts: Date.now() })
          if (responseTimes.value.length > 20) responseTimes.value.shift()
        }
        break

      case 'chat_response':
        // 非流式降级兼容（保留，飞书等接入时可能走这里）
        if (data.tool_calls && data.tool_calls.length > 0) {
          addMessage({
            role:      'tool_calls',
            toolCalls: data.tool_calls,
            timestamp: new Date()
          })
        }
        addMessage({
          role:         'assistant',
          content:      data.message,
          responseTime: data.response_time,
          timestamp:    new Date()
        })
        lastResponseTime.value = data.response_time
        break

      case 'task_update': {
        // LLM 回复中检测到 [TASK_DONE:<task_id>]，更新项目任务树状态
        useProjectStore().markTaskDone(data.task_data)
        break
      }

      case 'task_blocked': {
        // LLM 回复中检测到 [TASK_BLOCKED:<task_id>]，更新项目任务树状态
        useProjectStore().markTaskBlocked(data.task_data)
        break
      }

      case 'notification': {
        // Java 每5秒主动 push，取代前端30s轮询
        const allNotifs = data.notifications || []
        const cleanMsg = (msg) => msg
          ? msg.replace(/^[⏰🔔]\s*(周期?提醒[:：]\s*)?/u, '').replace(/^提醒[:：]\s*/, '').trim() || msg
          : ''

        // 去重：用 "timestamp_message" 作 key，防止 WS 重连时同一通知被展示两次
        const _notifKey = (n) => `${n.timestamp || ''}_${(n.message || '').slice(0, 80)}`
        const newNotifs = allNotifs.filter(n => {
          const k = _notifKey(n)
          if (_shownNotifKeys.has(k)) return false
          _shownNotifKeys.add(k)
          // 防止 Set 无限增长：保留最近 200 条
          if (_shownNotifKeys.size > 200) _shownNotifKeys.delete(_shownNotifKeys.values().next().value)
          return true
        })
        if (!newNotifs.length) break

        const assistantNotifs = newNotifs.filter(n => n.role === 'assistant').slice(-3)
        const systemNotifs    = newNotifs.filter(n => n.role !== 'assistant')
        const systemGroups = {}
        systemNotifs.forEach(n => {
          const key = cleanMsg(n.message)
          if (!systemGroups[key]) systemGroups[key] = { n, count: 0 }
          systemGroups[key].count++
        })
        Object.values(systemGroups).forEach(({ n, count }) => {
          const timeStr = formatTime(n.timestamp)
          const countTag = count > 1 ? `（共 ${count} 条）` : ''
          addMessage({
            id: genId(), role: 'system', notif: true,
            content:   `🔔 **定时提醒** · ${timeStr}${countTag}\n\n${cleanMsg(n.message)}`,
            timestamp: new Date(n.timestamp),
          })
        })
        assistantNotifs.forEach(n => {
          addMessage({ id: genId(), role: 'assistant', content: n.message, notif: true, timestamp: new Date(n.timestamp) })
        })
        break
      }

      case 'error':
        // 若正在流式输出时收到 error，在已接收内容末尾加截断标记，让用户知道回复不完整
        if (isStreaming.value && streamingIndex.value !== -1) {
          messages.value[streamingIndex.value].content += String.fromCharCode(10, 10) + '⚠️ *[响应被中断]*'
        }
        finalizeStream(null)
        addMessage({ role: 'system', content: `错误: ${data.message}`, timestamp: new Date() })
        break

      default:
        console.warn('[WS] 未知消息类型:', data.type)
    }
  }

  // ── 流式消息管理 ──────────────────────────────────────────
  const startStreamMessage = () => {
    messages.value.push({
      id:          genId(),
      role:        'assistant',
      content:     '',
      isStreaming: true,
      timestamp:   new Date()
    })
    streamingIndex.value = messages.value.length - 1
    isStreaming.value    = true
  }

  const MAX_MSG_CHARS = 200_000  // 单条消息上限 ~200KB，防止超长响应撑爆浏览器内存
  const appendToken = (token) => {
    if (streamingIndex.value === -1) startStreamMessage()
    const cur = messages.value[streamingIndex.value].content
    if (cur.length < MAX_MSG_CHARS) {
      messages.value[streamingIndex.value].content += token
    } else if (!cur.endsWith('\n\n…（响应过长已截断）')) {
      messages.value[streamingIndex.value].content += '\n\n…（响应过长已截断）'
    }
  }

  const finalizeStream = (responseTime) => {
    if (streamingIndex.value !== -1) {
      messages.value[streamingIndex.value].isStreaming = false
      if (responseTime != null) {
        messages.value[streamingIndex.value].responseTime = responseTime
      }
    }
    streamingIndex.value = -1
    isStreaming.value    = false
    chatEndSignal.value++  // notify watchers (e.g. ChatView.isThinking reset)
    // 流式完成后持久化（此时 isStreaming 已为 false）
    _saveChatHistory()
  }

  // ── 发送消息 ──────────────────────────────────────────────
  const send = (data) => {
    if (!ws || ws.readyState !== 1) {
      console.error('[WS] 未连接，无法发送')
      return false
    }
    try {
      ws.send(JSON.stringify(data))
      return true
    } catch (err) {
      console.error('[WS] 发送失败:', err)
      return false
    }
  }

  const sendChatMessage = (message, useTools = true, useMemory = true, projectId = null, pendingTasks = null) => {
    const payload = {
      type: 'chat_message', message,
      use_tools: useTools, use_memory: useMemory,
      session_id: currentSessionId.value,
    }
    if (projectId) payload.project_id = projectId
    if (pendingTasks && pendingTasks.length) payload.pending_tasks = pendingTasks
    return send(payload)
  }

  /** 开始新会话：生成新 session_id，清空当前消息 */
  const startNewSession = () => {
    const id = _newSessionId()
    currentSessionId.value = id
    localStorage.setItem('ia_session_id', id)
    clearMessages()
  }

  /** 取消当前正在生成的响应：断开并重连 WebSocket，Java 侧 SSE 流随之终止 */
  const cancelStreaming = () => {
    if (!isStreaming.value) return
    isStreaming.value = false
    activeToolSteps.value = []
    // 标记最后一条流式消息为已完成
    const idx = streamingIndex.value
    if (idx !== -1 && messages.value[idx]) {
      const msg = messages.value[idx]
      if (msg.isStreaming) {
        messages.value[idx] = {
          ...msg,
          content: (msg.content || '') + '\n\n*（已停止生成）*',
          isStreaming: false,
        }
      }
    }
    streamingIndex.value = -1
    // 设置 flag：阻止 onclose 在 5s 后再次触发重连，与下面的 500ms 定时器竞争
    _manualClose = true
    if (ws) {
      ws.close()
      ws = null
    }
    isConnected.value = false
    // 500ms 后重连（使用与初始连接相同的同源动态 URL）
    setTimeout(() => {
      _manualClose = false   // 重置，让后续正常断线仍能自动重连
      connect()
    }, 500)
  }

  // ── 模型管理 ──────────────────────────────────────────────
  const switchModel = async (modelName) => {
    const result = await apiSwitchModel(modelName)
    if (result?.success) {
      currentModel.value = result.current_model || modelName
      // 切换后同步云端模式状态，确保 Header 显示正确的模型名
      cloudMode.value  = !!result.cloud_mode
      cloudModel.value = result.cloud_mode ? (result.current_model || modelName) : ''
      if (systemInfo.value) {
        systemInfo.value = { ...systemInfo.value, agent_model: result.current_model || modelName }
      }
    }
    return result
  }

  const loadModels = async () => {
    const data = await apiGetModels()
    if (data) {
      availableModels.value = data.available_models || []
      currentModel.value    = data.current_model    || ''
      cloudMode.value       = !!data.cloud_mode
      cloudModel.value      = data.cloud_model      || ''
    }
  }

  // ── 聊天记录 localStorage 持久化 ─────────────────────────
  const CHAT_STORAGE_KEY = 'ia_chat_history'
  const CHAT_MAX_PERSIST = 50   // 最多保留最近 50 条

  const _saveChatHistory = () => {
    try {
      const toSave = messages.value
        .filter(m => m.role === 'user' || m.role === 'assistant' || m.role === 'tool_calls')
        .slice(-CHAT_MAX_PERSIST)
        .map(m => {
          // 去掉运行时状态字段
          const { isStreaming: _s, ...rest } = m
          return { ...rest, timestamp: m.timestamp instanceof Date ? m.timestamp.toISOString() : m.timestamp }
        })
      localStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify(toSave))
    } catch { /* 存储满时忽略 */ }
  }

  const _loadChatHistory = () => {
    try {
      const raw = localStorage.getItem(CHAT_STORAGE_KEY)
      if (!raw) return
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed) && parsed.length > 0) {
        messages.value = parsed.map(m => ({
          ...m,
          timestamp: m.timestamp ? new Date(m.timestamp) : new Date(),
        }))
      }
    } catch { /* 损坏的缓存直接忽略 */ }
  }

  // ── 工具方法 ──────────────────────────────────────────────
  // _saveChatHistory 防抖：批量通知到来时多条 addMessage 合并为一次写盘
  let _saveTimer = null
  const _scheduleSave = () => {
    clearTimeout(_saveTimer)
    _saveTimer = setTimeout(_saveChatHistory, 300)
  }

  const addMessage = (msg) => {
    messages.value.push(msg)
    // 只在消息完成时持久化（流式 isStreaming 消息不写存储），防抖 300ms 批量合并
    if (!msg.isStreaming) _scheduleSave()
  }
  const clearMessages = async () => {
    messages.value = []
    localStorage.removeItem(CHAT_STORAGE_KEY)
    // 同步清除后端短期记忆，保持前后端状态一致
    try {
      await apiClearAllMemory()
    } catch { /* 网络失败不影响前端清空 */ }
  }
  const disconnect    = ()    => {
    clearTimeout(_saveTimer)         // 防 logout 后 debounce 写入上一个用户的数据
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
    ws?.close()
    ws = null
    isConnected.value = false
    isMockMode.value  = false
    // 退出时清空消息和持久化，防止下一个用户看到本次会话数据
    messages.value = []
    localStorage.removeItem(CHAT_STORAGE_KEY)
    localStorage.removeItem('ia_session_id')
    _historyLoaded = false           // 重置，下次登录重新加载属于新用户的历史
    currentSessionId.value = _newSessionId()
  }

  return {
    // 状态
    isConnected, messages, systemInfo, lastResponseTime,
    error, isMockMode, currentModel, availableModels,
    isStreaming, streamingIndex, activeToolSteps, chatEndSignal,
    currentSessionId,
    // 计算属性
    connectionStatus, modelStatus,
    // 方法
    connect, disconnect, send, sendChatMessage, cancelStreaming,
    addMessage, clearMessages,
    startStreamMessage, appendToken, finalizeStream, responseTimes,
    switchModel, loadModels, startNewSession,
  }
})