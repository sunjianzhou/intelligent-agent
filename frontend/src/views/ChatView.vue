<template>
  <div class="chat-view">
    <!-- 消息搜索栏（Ctrl+F 触发） -->
    <ChatSearchBar
      :show="showSearch"
      :keyword="searchKeyword"
      :matches-count="searchMatches.length"
      :current-idx="searchCurrentIdx"
      @update:keyword="onSearchKeyword"
      @close="closeSearch"
      @prev="jumpToPrev"
      @next="jumpToNext"
    />

    <!-- 消息列表 -->
    <div class="message-list" ref="messageListRef">
      <!-- 空状态：产品价值引导 -->
      <ChatEmptyState
        v-if="messages.length === 0"
        :model-status="modelStatus"
        :is-cloud-mode="isCloudMode"
        @suggest="fillSuggestion"
      />

      <!-- 消息气泡 -->
      <ChatMessageRow
        v-for="(msg, index) in messages"
        :key="msg.id != null ? msg.id : index"
        :msg="msg"
        :index="index"
        :retract-mode="retractMode"
        :selected-retract-ids="selectedRetractIds"
        :search-keyword="searchKeyword"
        :is-search-match="searchMatches.includes(index)"
        :is-search-current="searchMatches[searchCurrentIdx] === index"
        :messages="messages"
        @toggle-retract="toggleRetractSelect"
        @branch="branchFromMessage"
      />

      <!-- 思考中指示器（含已等待秒数） -->
      <div v-if="isThinking" class="message-row assistant">
        <div class="avatar"><i class="fas fa-robot"></i></div>
        <div class="bubble-wrap">
          <div class="bubble assistant thinking-bubble">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="thinking-timer" v-if="thinkingSeconds > 3">{{ thinkingSeconds }}s</span>
          </div>
        </div>
      </div>

      <!-- 工具执行进度面板（实时显示每个工具启动状态） -->
      <div v-if="activeToolSteps.length > 0" class="message-row tool_calls">
        <div class="tool-running-card">
          <div class="tool-calls-title">
            <i class="fas fa-cog fa-spin" /> 正在调用工具…
          </div>
          <div
            v-for="(step, i) in activeToolSteps"
            :key="i"
            class="tool-call-item running"
          >
            <div class="tool-row-top">
              <span class="tool-name"><i class="fas fa-cube" /> {{ step.tool_name }}</span>
              <span class="tool-status"><i class="fas fa-spinner fa-spin" /> 执行中</span>
            </div>
            <div v-if="step.args_summary" class="tool-args">
              <i class="fas fa-code" style="font-size:0.72rem;color:#aaa" /> {{ step.args_summary }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 撤回模式底部浮层 -->
    <div v-if="retractMode" class="retract-toolbar">
      <span class="retract-count">
        已选 {{ selectedRetractIds.size }} 条
        <template v-if="selectedRetractIds.size >= MAX_RETRACT_BATCH"> （已达单次上限，请先确认或取消部分选择）</template>
      </span>
      <button class="retract-cancel-btn" @click="cancelRetractSelection">取消</button>
      <button class="retract-confirm-btn" :disabled="!selectedRetractIds.size" @click="confirmRetract">确认撤回</button>
    </div>

    <!-- Token 超限警告横幅 -->
    <transition name="banner-slide">
      <div v-if="tokenWarning && !ctxBannerDismissed" class="ctx-warn-banner">
        <i class="fas fa-exclamation-triangle ctx-warn-icon" />
        <span class="ctx-warn-text">
          上下文已达 <strong>{{ tokenPct }}%</strong>，继续对话可能丢失早期内容
        </span>
        <button class="ctx-warn-btn" @click="handleNewConversation">
          <i class="fas fa-plus" /> 新开对话
        </button>
        <button class="ctx-warn-close" @click="ctxBannerDismissed = true" title="忽略">
          <i class="fas fa-times" />
        </button>
      </div>
    </transition>

    <!-- 历史会话面板遮罩 -->
    <!-- 历史会话侧边栏 -->
    <ChatHistoryPanel
      :show="showHistory"
      :loading="historyLoading"
      :sessions="sessions"
      @close="showHistory = false"
      @new="onNewFromHistory"
      @load="loadSession"
      @delete="deleteSession"
      @rename="renameSession"
      @export="exportSession"
    />

    <!-- 配置条：角色 + 模型 -->
    <div class="config-bar">
      <div class="config-role">
        <i class="fas fa-id-card config-icon" />
        <select
          :value="activeRoleId"
          class="config-select"
          :disabled="roleActivating"
          @change="onRoleChange"
        >
          <option value="">默认助手</option>
          <option v-for="r in availableRoles" :key="r.roleId" :value="r.roleId">
            {{ r.roleCard?.name || r.roleId }}
          </option>
        </select>
        <!-- 角色激活徽章：有激活角色时醒目提示 -->
        <span v-if="activeRoleId" class="role-active-badge">
          <i class="fas fa-circle-dot" />
          {{ availableRoles.find(r => r.roleId === activeRoleId)?.roleCard?.name || activeRoleId }}
        </span>
        <i v-if="roleActivating" class="fas fa-circle-notch fa-spin config-icon" style="color:#a0aec0" />
      </div>

      <div class="config-model" ref="configSwitcherRef">
        <i class="fas fa-brain config-icon" />
        <button
          class="config-model-btn"
          :class="{ open: configDropdownOpen }"
          @click.stop="configDropdownOpen = !configDropdownOpen"
        >
          <span class="config-model-text">{{ modelStatus }}</span>
          <i class="fas fa-chevron-down config-chevron" />
        </button>
        <div v-if="configDropdownOpen" class="config-model-dropdown">
          <div class="config-dropdown-title">切换模型</div>
          <div
            v-for="m in availableModels"
            :key="m"
            class="config-dropdown-item"
            :class="{ active: m === currentModel, switching: configSwitchingModel === m }"
            @click="handleConfigSwitch(m)"
          >
            <i class="fas fa-cube" />
            <span>{{ m }}</span>
            <i v-if="m === currentModel" class="fas fa-check" style="color:var(--color-primary);margin-left:auto" />
            <i v-if="configSwitchingModel === m" class="fas fa-circle-notch fa-spin" style="margin-left:auto" />
          </div>
          <div v-if="availableModels.length === 0" class="config-dropdown-empty">暂无可用模型</div>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <ChatInputBar
      ref="inputBarRef"
      :is-connected="isConnected"
      :is-thinking="isThinking"
      :is-streaming="isStreaming"
      :model-status="modelStatus"
      :current-model="currentModel"
      :active-role-name="activeRoleName"
      :show-history="showHistory"
      :retract-mode="retractMode"
      :messages-count="messages.length"
      :estimated-tokens="estimatedTokens"
      :ctx-limit="CTX_LIMIT"
      :token-color="tokenColor"
      :token-warning="tokenWarning"
      :project-title="projectStore.activeProject?.title || ''"
      @send="handleSend"
      @cancel-stream="cancelStreaming"
      @toggle-history="toggleHistory"
      @export="exportChat"
      @toggle-retract-mode="toggleRetractMode"
      @clear-chat="handleClearChat"
      @open-role-model-sheet="showRoleModelSheet = true"
      @project-click="router.push('/project')"
    />

    <!-- 移动端：角色 + 模型选择底部抽屉 -->
    <BottomSheet v-model="showRoleModelSheet" title="角色与模型">
      <!-- 角色列表 -->
      <div class="rms-section-title">角色</div>
      <button
        class="rms-option"
        :class="{ active: !activeRoleId }"
        :aria-selected="!activeRoleId"
        @click="onRoleChange(''); showRoleModelSheet = false"
      >
        <i class="fas fa-robot rms-icon" aria-hidden="true" />
        <span>默认助手</span>
        <i v-if="!activeRoleId" class="fas fa-check rms-check" aria-hidden="true" />
      </button>
      <button
        v-for="r in availableRoles"
        :key="r.roleId"
        class="rms-option"
        :class="{ active: r.roleId === activeRoleId }"
        :aria-selected="r.roleId === activeRoleId"
        :aria-label="r.roleCard?.name || r.roleId"
        @click="onRoleChange(r.roleId); showRoleModelSheet = false"
      >
        <i class="fas fa-id-card rms-icon" aria-hidden="true" />
        <span>{{ r.roleCard?.name || r.roleId }}</span>
        <i v-if="r.roleId === activeRoleId" class="fas fa-check rms-check" aria-hidden="true" />
      </button>

      <div class="rms-divider" />

      <!-- 模型列表 -->
      <div class="rms-section-title">模型</div>
      <button
        v-for="m in availableModels"
        :key="m"
        class="rms-option"
        :class="{ active: m === currentModel }"
        :aria-selected="m === currentModel"
        :aria-label="m"
        @click="handleConfigSwitch(m); showRoleModelSheet = false"
      >
        <i class="fas fa-cube rms-icon" aria-hidden="true" />
        <span>{{ m }}</span>
        <i v-if="m === currentModel" class="fas fa-check rms-check" aria-hidden="true" />
      </button>
      <div v-if="availableModels.length === 0" class="rms-empty">暂无可用模型</div>
    </BottomSheet>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useWebSocketStore }    from '@/stores/websocket'
import { useLocalSessionStore } from '@/stores/localSession'
import { useConfirmDialogStore } from '@/stores/confirmDialog'
import { useProjectStore }      from '@/stores/project'
import {
  listConversations, getConversation, deleteConversation,
  renameConversation, exportConversation,
  branchConversation as apiBranchConversation,
  retractMessages as apiRetractMessages,
} from '@/services/api'
import { formatTime, formatForFilename } from '@/utils/date'
import { genId } from '@/utils/string'
import { estimateMessages } from '@/utils/tokenEstimate'
import { listRoles } from '@/services/roleStorage'
import {
  getActiveRoleApi, activateRoleApi, deactivateRoleApi, syncRoleToServer,
} from '@/services/api'
import BottomSheet from '@/components/common/BottomSheet.vue'
import ChatSearchBar    from '@/components/chat/ChatSearchBar.vue'
import ChatEmptyState   from '@/components/chat/ChatEmptyState.vue'
import ChatHistoryPanel from '@/components/chat/ChatHistoryPanel.vue'
import ChatMessageRow   from '@/components/chat/ChatMessageRow.vue'
import ChatInputBar     from '@/components/chat/ChatInputBar.vue'

// ── Store ──────────────────────────────────────────────────
const router       = useRouter()
const store        = useWebSocketStore()
const sessionStore = useLocalSessionStore()
const confirmDialog = useConfirmDialogStore()
const projectStore = useProjectStore()
const messages     = computed(() => store.messages)
const isConnected  = computed(() => store.isConnected)
const modelStatus  = computed(() => store.modelStatus)
const systemInfo   = computed(() => store.systemInfo)
const isCloudMode  = computed(() => modelStatus.value?.includes?.('☁') ?? false)
const isStreaming      = computed(() => store.isStreaming)
const activeToolSteps  = computed(() => store.activeToolSteps)
const cancelStreaming   = () => store.cancelStreaming()
const availableModels  = computed(() => store.availableModels)
const currentModel     = computed(() => store.currentModel)

// ── 配置条：角色 + 模型 ────────────────────────────────────
const availableRoles       = ref([])
const activeRoleId         = ref('')
const activeRoleName = computed(() => {
  if (!activeRoleId.value) return '默认助手'
  const role = availableRoles.value.find(r => r.roleId === activeRoleId.value)
  return role?.roleCard?.name || role?.name || activeRoleId.value
})
const roleActivating       = ref(false)
const configDropdownOpen   = ref(false)
const configSwitchingModel = ref('')
const configSwitcherRef    = ref(null)
// 移动端角色/模型选择底部抽屉
const showRoleModelSheet   = ref(false)

const loadRoleConfig = async () => {
  availableRoles.value = await listRoles()
  const data = await getActiveRoleApi()
  if (data?.role_id) activeRoleId.value = data.role_id
}

const onRoleChange = async (e) => {
  const roleId = e.target ? e.target.value : e
  roleActivating.value = true
  try {
    if (!roleId) {
      await deactivateRoleApi()
      activeRoleId.value = ''
      ElMessage({ message: '已切换回默认助手', type: 'success', duration: 1500 })
    } else {
      const role = availableRoles.value.find(r => r.roleId === roleId)
      if (role) await syncRoleToServer(roleId, role)
      await activateRoleApi(roleId)
      activeRoleId.value = roleId
      const name = role?.roleCard?.name || roleId
      ElMessage({ message: `已激活角色「${name}」`, type: 'success', duration: 1500 })
    }
  } catch {
    ElMessage.error('角色切换失败')
  } finally {
    roleActivating.value = false
  }
}

const handleConfigSwitch = async (modelName) => {
  if (modelName === currentModel.value || configSwitchingModel.value) return
  configSwitchingModel.value = modelName
  const result = await store.switchModel(modelName)
  if (!result?.success) ElMessage.error(`切换失败: ${result?.message || '未知错误'}`)
  configSwitchingModel.value = ''
  configDropdownOpen.value = false
}

const closeConfigDropdown = (e) => {
  if (configSwitcherRef.value && !configSwitcherRef.value.contains(e.target)) {
    configDropdownOpen.value = false
  }
}

// ── 本地状态 ───────────────────────────────────────────────
const isThinking           = ref(false)
const messageListRef       = ref(null)
const inputBarRef          = ref(null)

// ── 推理等待计时器 ─────────────────────────────────────────
const thinkingSeconds  = ref(0)
let   _thinkingTimer   = null

const startThinkingTimer = () => {
  thinkingSeconds.value = 0
  _thinkingTimer = setInterval(() => { thinkingSeconds.value++ }, 1000)
}
const stopThinkingTimer = () => {
  if (_thinkingTimer) { clearInterval(_thinkingTimer); _thinkingTimer = null }
  thinkingSeconds.value = 0
}

// ── 上下文 Token 用量估算（R-01）────────────────────────────
// 从 /api/system/resources 读取后端 ContextBudget 结构（num_ctx 唯一来源，
// 替代此前硬编码 CTX_LIMIT=8192 与失效的 ollama_num_ctx 字段）；
// 估算规则与后端 ContextBudget 完全一致（CJK≈1 / 其余≈0.25 token/字符 + 消息开销）。
const CTX_LIMIT = ref(8192) // 拉取失败时的回退值；成功后用后端 usable_tokens 覆盖
const fetchCtxLimit = async () => {
  try {
    const { getSystemResources } = await import('@/services/api')
    const res = await getSystemResources()
    const budget = res?.context_budget
    if (budget?.usable_tokens) CTX_LIMIT.value = budget.usable_tokens
  } catch { /* 忽略，用默认值 */ }
}
onMounted(fetchCtxLimit)
const estimatedTokens = computed(() => {
  return estimateMessages(
    messages.value.filter(m => m.role === 'user' || m.role === 'assistant'),
  )
})

const tokenPct     = computed(() => Math.min(100, Math.round(estimatedTokens.value / CTX_LIMIT.value * 100)))
const tokenColor   = computed(() => tokenPct.value >= 90 ? '#e53935' : tokenPct.value >= 70 ? '#f57c00' : '#aaa')
const tokenWarning       = computed(() => tokenPct.value >= 90)
const ctxBannerDismissed = ref(false)
// 每次警告重新触发时重置"忽略"状态，让 Banner 再次出现
watch(tokenWarning, (v) => { if (v) ctxBannerDismissed.value = false })

// ── 消息搜索（WANT-008）────────────────────────────────────
const showSearch      = ref(false)
const searchKeyword   = ref('')
const searchMatches   = ref([])
const searchCurrentIdx = ref(0)

const onSearchKeyword = (v) => {
  searchKeyword.value = v
  doMessageSearch()
}

let _searchTimer = null
const doMessageSearch = () => {
  clearTimeout(_searchTimer)
  _searchTimer = setTimeout(() => {
    const kw = searchKeyword.value.trim().toLowerCase()
    if (!kw) { searchMatches.value = []; return }
    const matches = []
    messages.value.forEach((m, i) => {
      if ((m.content || '').toLowerCase().includes(kw)) matches.push(i)
    })
    searchMatches.value = matches
    searchCurrentIdx.value = 0
    scrollToMatch(matches[0])
  }, 300)
}

const scrollToMatch = (idx) => {
  if (idx === undefined || idx === null) return
  const el = messageListRef.value
  if (!el) return
  const rows = el.querySelectorAll('.message-row')
  rows[idx]?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const jumpToNext = () => {
  if (!searchMatches.value.length) return
  searchCurrentIdx.value = (searchCurrentIdx.value + 1) % searchMatches.value.length
  scrollToMatch(searchMatches.value[searchCurrentIdx.value])
}

const jumpToPrev = () => {
  if (!searchMatches.value.length) return
  searchCurrentIdx.value = (searchCurrentIdx.value - 1 + searchMatches.value.length) % searchMatches.value.length
  scrollToMatch(searchMatches.value[searchCurrentIdx.value])
}

const closeSearch = () => {
  showSearch.value = false
  searchKeyword.value = ''
  searchMatches.value = []
}

const openSearch = () => {
  showSearch.value = true
}

// ── 计算属性 ───────────────────────────────────────────────

// scrollToBottom 使用 rAF 节流：每帧最多执行一次，避免每个 token 都排队 nextTick
let _scrollRafId = null
const scrollToBottom = () => {
  if (_scrollRafId) return
  _scrollRafId = requestAnimationFrame(() => {
    _scrollRafId = null
    const el = messageListRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

const fillSuggestion = (text) => {
  inputBarRef.value?.fillSuggestion(text)
}

// ── 发送逻辑 ───────────────────────────────────────────────
const handleSend = (text, imgB64, imgPreview) => {
  const userMsg = {
    id: genId(), role: 'user', content: text, timestamp: new Date(),
    _backendIdConfirmed: false,
    ...(imgPreview ? { imagePreview: imgPreview } : {}),
  }
  store.addMessage(userMsg)
  sessionStore.addMessage({
    role: 'user', content: text, timestamp: new Date().toISOString(),
    ...(imgB64 ? { images_b64: [imgB64] } : {}),
  })

  isThinking.value = true

  const activeTasks = projectStore.activeProject?.task_tree?.root_tasks ?? null
  const ok = store.sendChatMessage(text, true, true, projectStore.activeProjectId, activeTasks, imgB64)
  if (!ok) {
    isThinking.value = false
    store.addMessage({ role: 'system', content: '发送失败，请检查连接状态', timestamp: new Date() })
  }
}

// 监听新增 assistant 消息，自动写入本地会话
let _lastMsgLen = 0
watch(messages, (msgs) => {
  if (msgs.length <= _lastMsgLen) { _lastMsgLen = msgs.length; return }
  const newest = msgs[msgs.length - 1]
  if (newest?.role === 'assistant' && newest.content) {
    sessionStore.addMessage({ role: 'assistant', content: newest.content, timestamp: new Date().toISOString() })
  }
  _lastMsgLen = msgs.length
})

// ── 对话导出 ──────────────────────────────────────────────
const exportChat = (format = 'md') => {
  const chatMessages = messages.value.filter(
    m => m.role === 'user' || m.role === 'assistant'
  )
  if (!chatMessages.length) {
    ElMessage({ message: '暂无对话内容可导出', type: 'warning', duration: 2000 })
    return
  }

  const now      = formatForFilename()
  const model    = store.modelStatus || 'AI'

  let content = ''

  if (format === 'md') {
    content += `# 对话记录\n\n`
    content += `> 导出时间：${new Date().toLocaleString('zh-CN')}  \n`
    content += `> 模型：${model}\n\n---\n\n`
    chatMessages.forEach(m => {
      if (m.role === 'user') {
        content += `**用户**\n\n${m.content}\n\n`
      } else {
        const rt = m.responseTime ? ` *(${m.responseTime.toFixed(1)}s)*` : ''
        content += `**助手**${rt}\n\n${m.content}\n\n`
      }
      content += '---\n\n'
    })
  } else {
    content += `对话记录\n`
    content += `导出时间：${new Date().toLocaleString('zh-CN')}\n`
    content += `模型：${model}\n`
    content += '='.repeat(40) + '\n\n'
    chatMessages.forEach(m => {
      const role = m.role === 'user' ? '用户' : '助手'
      const time = m.timestamp ? formatTime(m.timestamp) : ''
      const rt   = m.responseTime ? ` (${m.responseTime.toFixed(1)}s)` : ''
      content += `[${role}${time ? ' ' + time : ''}${rt}]\n`
      content += m.content + '\n'
      content += '-'.repeat(40) + '\n\n'
    })
  }

  const blob     = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url      = URL.createObjectURL(blob)
  const a        = document.createElement('a')
  a.href         = url
  a.download     = `对话记录_${now}.${format}`
  a.click()
  URL.revokeObjectURL(url)
}

// 新开对话：清空显示 + 开新会话，保留 AI 后端记忆
const handleNewConversation = async () => {
  store.messages.splice(0)
  try { localStorage.removeItem('ia_chat_history') } catch {}
  await sessionStore.startNewSession()
  ctxBannerDismissed.value = true
  ElMessage({ message: '已新开对话', type: 'success', duration: 1500 })
}

// 历史面板「新开对话」：先关面板再执行原逻辑
const onNewFromHistory = () => {
  showHistory.value = false
  handleNewConversation()
}

const handleClearChat = async () => {
  const ok = await confirmDialog.confirm(
    '确认清空对话？此操作将同时清除 AI 的对话记忆，无法恢复。',
    { title: '清空对话', confirmText: '清空', danger: true }
  )
  if (!ok) return
  isThinking.value = false
  stopThinkingTimer()
  await store.clearMessages()
  ElMessage({ message: '对话已清空', type: 'success', duration: 1500 })
}

// ── 撤回模式 ──────────────────────────────────────────────
const MAX_RETRACT_BATCH = 50
const retractMode       = ref(false)
const selectedRetractIds = ref(new Set())

const canRetract = (msg) => (msg.role === 'user' || msg.role === 'assistant') && !msg.isRetracted

const toggleRetractMode = () => {
  retractMode.value = !retractMode.value
  if (!retractMode.value) selectedRetractIds.value = new Set()
}

const toggleRetractSelect = (msg) => {
  if (!canRetract(msg) || msg.id == null) return
  const next = new Set(selectedRetractIds.value)
  if (next.has(msg.id)) {
    next.delete(msg.id)
  } else if (next.size < MAX_RETRACT_BATCH) {
    next.add(msg.id)
  }
  selectedRetractIds.value = next
}

const cancelRetractSelection = () => {
  retractMode.value = false
  selectedRetractIds.value = new Set()
}

const confirmRetract = async () => {
  const ids = Array.from(selectedRetractIds.value)
  if (!ids.length) return

  const warningSuffix = ids.length > 1
    ? '\n\n⚠️ 同时撤回多条消息可能造成对话上下文不连贯，请确认这些消息之间没有被后续内容依赖引用。'
    : ''
  const ok = await confirmDialog.confirm(
    `确认撤回${ids.length > 1 ? `这 ${ids.length} 条消息` : '这条消息'}？此操作将从存储中永久删除，无法恢复。${warningSuffix}`,
    { title: '撤回消息', confirmText: '撤回', danger: true }
  )
  if (!ok) return

  try {
    const res = await apiRetractMessages(store.currentSessionId, ids)
    const deletedIds = new Set(res?.deleted_ids || [])
    messages.value.forEach(msg => {
      if (deletedIds.has(msg.id)) {
        msg.content = ''
        msg.isRetracted = true
      }
    })
    if ((res?.deleted ?? 0) < (res?.requested ?? ids.length)) {
      ElMessage({
        message: `部分消息已不存在或删除失败（${res.requested} 条中成功 ${res.deleted} 条）`,
        type: 'warning', duration: 3000,
      })
    } else {
      ElMessage({ message: `已撤回 ${res.deleted} 条消息`, type: 'success', duration: 1500 })
    }
  } catch {
    ElMessage({ message: '撤回失败，请重试', type: 'error', duration: 2000 })
  } finally {
    retractMode.value = false
    selectedRetractIds.value = new Set()
  }
}

// ── 历史会话面板 ──────────────────────────────────────────
const showHistory    = ref(false)
const sessions       = ref([])

// 搜索关键词变化时触发搜索
watch(searchKeyword, doMessageSearch)

// 响应移动端汉堡菜单里的「历史会话」快捷入口
watch(() => store.openHistorySignal, () => toggleHistory())
// 响应侧边栏加号按钮（新开对话）
watch(() => store.newSessionSignal, () => handleNewConversation())
// 响应侧边栏点击历史条目 → 恢复 localSession 消息到 wsStore.messages
watch(() => store.openSessionSignal, () => {
  const msgs = sessionStore.messages
  if (!msgs?.length) return
  store.messages.splice(0)
  msgs.forEach(m => store.messages.push({
    id:        m.id || genId(),
    role:      m.role,
    content:   m.content,
    timestamp: m.timestamp || new Date().toISOString(),
    _backendIdConfirmed: !!m.id,
  }))
  nextTick(scrollToBottom)
  ElMessage({ message: '已加载历史会话', type: 'success', duration: 1500 })
})
const historyLoading = ref(false)

const toggleHistory = async () => {
  showHistory.value = !showHistory.value
  if (showHistory.value) await loadHistory()
}

const loadHistory = async () => {
  historyLoading.value = true
  try {
    const res = await listConversations()
    sessions.value = res?.sessions || []
  } finally {
    historyLoading.value = false
  }
}

const loadSession = async (sessionId) => {
  const res = await getConversation(sessionId)
  const msgs = res?.session?.messages || res?.messages
  if (!msgs?.length) {
    ElMessage({ message: '该会话暂无消息', type: 'warning', duration: 2000 })
    return
  }
  store.messages.splice(0)
  store.currentSessionId = sessionId
  localStorage.setItem('ia_session_id', sessionId)
  msgs.forEach(m => {
    const entry = {
      id: m.id || genId(),
      role: m.role,
      content: m.content,
      timestamp: m.timestamp || new Date().toISOString(),
      _backendIdConfirmed: !!m.id,
    }
    // 还原多模态图片预览（base64 存于 images_b64[0]）
    if (m.images_b64?.length) {
      entry.images_b64 = m.images_b64
      entry.imagePreview = `data:image/jpeg;base64,${m.images_b64[0]}`
    }
    store.messages.push(entry)
  })
  showHistory.value = false
  nextTick(scrollToBottom)
  ElMessage({ message: '已加载历史会话', type: 'success', duration: 1500 })
}

const deleteSession = async (sessionId) => {
  const ok = await confirmDialog.confirm(
    '确认删除该会话记录？删除后不可恢复。',
    { title: '删除会话', confirmText: '删除', danger: true }
  )
  if (!ok) return
  const wasCurrent = store.currentSessionId === sessionId
  await deleteConversation(sessionId)
  sessions.value = sessions.value.filter(s => s.session_id !== sessionId)
  ElMessage({ message: '会话已删除', type: 'success', duration: 1500 })
  if (wasCurrent) {
    const next = sessions.value[0]
    if (next) {
      await loadSession(next.session_id)
    } else {
      handleNewConversation()
    }
  }
}

const renameSession = async (sessionId, title) => {
  try {
    const res = await renameConversation(sessionId, title)
    if (res?.success) {
      const sess = sessions.value.find(s => s.session_id === sessionId)
      if (sess) sess.title = title
      ElMessage({ message: '会话已重命名', type: 'success', duration: 1500 })
    } else {
      ElMessage({ message: res?.message || '重命名失败', type: 'error', duration: 2000 })
    }
  } catch {
    ElMessage({ message: '重命名失败，请重试', type: 'error', duration: 2000 })
  }
}

const exportSession = async (sessionId) => {
  try {
    const res = await exportConversation(sessionId)
    if (!res?.success || !res.session) {
      ElMessage({ message: res?.message || '导出失败', type: 'error', duration: 2000 })
      return
    }
    const blob = new Blob([JSON.stringify(res.session, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = res.filename || `${sessionId}.json`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    ElMessage({ message: '会话已导出为 JSON', type: 'success', duration: 1500 })
  } catch {
    ElMessage({ message: '导出失败，请重试', type: 'error', duration: 2000 })
  }
}

const branchFromMessage = async (index) => {
  const seedMsgs = messages.value.slice(0, index + 1).map(m => {
    const entry = {
      role:         m.role,
      content:      m.content || '',
      timestamp:    m.timestamp || new Date().toISOString(),
      imagePreview: m.imagePreview || null,
      images_b64:   m.images_b64  || null,
    }
    return entry
  })
  try {
    const res = await apiBranchConversation(seedMsgs, store.currentSessionId)
    if (!res?.session_id) throw new Error('no session_id')
    await loadSession(res.session_id)
    ElMessage({ message: '分支对话已创建，继续输入即可', type: 'success', duration: 2000 })
  } catch {
    ElMessage({ message: '创建分支失败，请重试', type: 'error', duration: 2000 })
  }
}

// ── 监听消息列表变化 ───────────────────────────────────────
watch(
  () => messages.value.length,
  () => {
    const last = messages.value[messages.value.length - 1]
    if (last && ['assistant', 'system', 'tool_calls'].includes(last.role)) {
      isThinking.value = false
    }
    scrollToBottom()
  }
)

// thinking 状态变化时启停计时器
watch(isThinking, (val) => {
  if (val) startThinkingTimer()
  else stopThinkingTimer()
})

// 流式开始时关闭 thinking
watch(isStreaming, (val) => {
  if (val) isThinking.value = false
  scrollToBottom()
})

// chat_done / error 到达时（无论是否有 token）确保 thinking 被清除
watch(() => store.chatEndSignal, () => {
  isThinking.value = false
})

// 流式过程中持续滚到底（rAF 节流，不会每个 token 重复 nextTick）
watch(
  () => {
    const idx = store.streamingIndex
    return idx !== -1 && messages.value[idx]
      ? messages.value[idx].content?.length
      : 0
  },
  (len) => { if (len > 0) scrollToBottom() }
)

const handleGlobalKey = (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
    e.preventDefault()
    openSearch()
  }
}

onMounted(async () => {
  // 初始化本地会话：加载历史列表，无活跃会话时新建
  await sessionStore.loadSessions()
  if (!sessionStore.activeId) {
    await sessionStore.startNewSession()
  } else if (sessionStore.messages.length > 0 && store.messages.length === 0) {
    // 刷新页面或重新挂载时，从 localSession 恢复当前会话消息
    sessionStore.messages.forEach(m => {
      const entry = {
        id:        m.id || genId(),
        role:      m.role,
        content:   m.content,
        timestamp: m.timestamp || new Date().toISOString(),
      }
      // 还原多模态图片预览（IndexedDB 中存了 images_b64）
      if (m.images_b64?.length) {
        entry.images_b64  = m.images_b64
        entry.imagePreview = `data:image/jpeg;base64,${m.images_b64[0]}`
      }
      store.messages.push(entry)
    })
  }

  scrollToBottom()
  document.addEventListener('click', closeConfigDropdown)
  document.addEventListener('keydown', handleGlobalKey)
  loadRoleConfig()
})

onUnmounted(() => {
  document.removeEventListener('click', closeConfigDropdown)
  document.removeEventListener('keydown', handleGlobalKey)
  stopThinkingTimer()
  clearTimeout(_searchTimer)
})

</script>

<style scoped>
/* ── 工具调用卡片 ────────────────────────────────────────── */
.tool-calls-title {
  font-weight: 500;
  color: #4a5568;
  margin-bottom: var(--space-2);
  display: flex;
  align-items: center;
  gap: 6px;
}
.tool-calls-title i { color: var(--color-primary); }
.tool-call-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: 6px 0;
  border-top: 1px solid #e2e8f0;
}
.tool-row-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tool-args {
  font-size: 0.78rem; color: var(--color-text-muted);
  font-family: monospace;
  word-break: break-all;
  padding-left: var(--space-1);
}
.tool-name {
  font-weight: 500;
  color: #4a5568;
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 120px;
}
.tool-name i { color: var(--color-primary); font-size: 0.8rem; }
.tool-status { font-size: 0.82rem; }
.tool-call-item.running .tool-status { color: var(--color-primary); }
.tool-running-card {
  max-width: 80%;
  background: #f8f7ff;
  border: 1px dashed #b0bef5;
  border-radius: var(--radius-md);
  padding: 10px 14px;
  font-size: 0.85rem;
  margin: 0 auto;
  opacity: 0.9;
}

/* ── 整体布局 ─────────────────────────────────────────────── */
.chat-view {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--color-bg);
}

/* ── 消息列表 ─────────────────────────────────────────────── */
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* ── 消息行 ───────────────────────────────────────────────── */
.message-row            { display: flex; align-items: flex-end; gap: var(--space-2); }
.message-row.user       { flex-direction: row-reverse; }
.message-row.system     { justify-content: center; }
.message-row.tool_calls { justify-content: center; }

/* ── 头像 ─────────────────────────────────────────────────── */
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--color-primary);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.85rem;
  flex-shrink: 0;
}
.user-avatar               { background: #4a5568; }
.message-row.system .avatar { display: none; }

/* ── 气泡容器 ─────────────────────────────────────────────── */
.bubble-wrap { max-width: 68%; display: flex; flex-direction: column; gap: 3px; position: relative; }
.message-row.user   .bubble-wrap { align-items: flex-end; }
.message-row.system .bubble-wrap { max-width: 90%; align-items: center; }

/* ── 气泡 ─────────────────────────────────────────────────── */
.bubble {
  padding: 10px 14px;
  border-radius: var(--radius-lg);
  font-size: 0.93rem;
  line-height: 1.6;
  word-break: break-word;
}
.bubble.user {
  background: var(--color-primary);
  color: white;
  border-bottom-right-radius: 4px;
}
.bubble.assistant {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  line-height: 1.85;
  border-bottom-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}
.bubble.system {
  background: #fff8e1;
  color: #856404;
  font-size: 0.85rem;
  border-radius: var(--radius-sm);
  border: 1px solid #ffe082;
  padding: 6px var(--space-3);
}
/* 定时任务通知系统消息 */
.bubble.system:has(.md-content),
.bubble.system.notif {
  background: linear-gradient(135deg, #fff3cd, #fff8e1);
  border-color: var(--color-warn);
  border-left: 3px solid #ff9800;
  box-shadow: 0 2px 8px rgba(255,152,0,0.15);
  font-size: 0.9rem;
  padding: 10px 14px;
}

/* ── 思考中动画 ───────────────────────────────────────────── */
.thinking-bubble { display: flex; align-items: center; gap: 5px; padding: var(--space-3) var(--space-4); }
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-text-muted);
  animation: bounce 1.2s infinite ease-in-out;
}
.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.7); opacity: 0.5; }
  40%           { transform: scale(1);   opacity: 1; }
}
.thinking-timer {
  margin-left: 6px;
  font-size: 0.75rem;
  color: #9e9e9e;
  font-variant-numeric: tabular-nums;
  min-width: 2.5em;
}


/* ── 上下文超限 Banner ─────────────────────────────── */
.ctx-warn-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px var(--space-4);
  background: linear-gradient(90deg, #fff3e0 0%, #fce4e4 100%);
  border-top: 1px solid #ffcc80;
  border-bottom: 1px solid #ffcc80;
  font-size: 0.82rem;
  color: #bf360c;
}
.ctx-warn-icon { font-size: 0.9rem; flex-shrink: 0; color: #e65100; }
.ctx-warn-text { flex: 1; }
.ctx-warn-text strong { font-weight: 600; }
.ctx-warn-btn {
  padding: var(--space-1) var(--space-3); border-radius: var(--radius-sm);
  border: 1px solid #e65100; background: #e65100; color: white;
  font-size: 0.8rem; cursor: pointer; white-space: nowrap;
  transition: background 0.15s;
  display: flex; align-items: center; gap: 5px;
}
.ctx-warn-btn:hover { background: #bf360c; }
.ctx-warn-close {
  background: none; border: none; color: #bf360c;
  cursor: pointer; font-size: 0.9rem; padding: 2px var(--space-1);
  opacity: 0.6; flex-shrink: 0;
}
.ctx-warn-close:hover { opacity: 1; }

/* 过渡动画 */
.banner-slide-enter-active,
.banner-slide-leave-active { transition: all 0.25s ease; }
.banner-slide-enter-from,
.banner-slide-leave-to   { opacity: 0; transform: translateY(-8px); }

/* ── 滚动条 ───────────────────────────────────────────────── */
.message-list::-webkit-scrollbar       { width: 4px; }
.message-list::-webkit-scrollbar-thumb { background: #ddd; border-radius: 2px; }

.retract-toolbar {
  position: sticky; bottom: 0; left: 0; right: 0;
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; background: #fff7ed; border-top: 1px solid #fed7aa;
  font-size: 0.85rem; z-index: 5;
}
.retract-count { flex: 1; color: #9a3412; }
.retract-cancel-btn, .retract-confirm-btn {
  padding: 6px 14px; border-radius: 6px; font-size: 0.85rem; cursor: pointer;
}
.retract-cancel-btn { background: #fff; border: 1px solid #d1d5db; color: #374151; }
.retract-confirm-btn { background: #ea580c; border: none; color: #fff; }
.retract-confirm-btn:disabled { background: #fdba74; cursor: not-allowed; }

/* ── 配置条 ───────────────────────────────────────────────── */
.config-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 14px;
  border-top: 1px solid #eef0f4;
  background: var(--color-bg);
  flex-shrink: 0;
  gap: 10px;
}
.config-role, .config-model {
  display: flex;
  align-items: center;
  gap: 5px;
  position: relative;
}
.role-active-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px var(--space-2);
  border-radius: var(--radius-md);
  background: #eef2ff;
  color: #4f46e5;
  font-size: 0.72rem;
  font-weight: 600;
  white-space: nowrap;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  border: 1px solid #c7d2fe;
}
.role-active-badge i { font-size: 0.6rem; color: #6366f1; }
.config-icon { color: var(--color-text-muted); font-size: 0.78rem; flex-shrink: 0; }
.config-select {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 3px 6px;
  font-size: 0.8rem;
  color: var(--color-text-secondary);
  background: var(--color-surface);
  outline: none;
  cursor: pointer;
  max-width: 150px;
}
.config-select:focus { border-color: var(--color-primary); }
.config-select:disabled { opacity: 0.6; cursor: not-allowed; }
.config-model-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: 3px 7px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: 0.8rem;
  color: var(--color-text-secondary);
  cursor: pointer;
  max-width: 180px;
  transition: all 0.15s;
}
.config-model-btn:hover, .config-model-btn.open {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.config-model-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
}
.config-chevron {
  font-size: 0.62rem;
  transition: transform 0.2s;
  flex-shrink: 0;
}
.config-model-btn.open .config-chevron { transform: rotate(180deg); }
.config-model-dropdown {
  position: absolute;
  bottom: calc(100% + 6px);
  right: 0;
  min-width: 200px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: 0 4px 20px rgba(0,0,0,0.1);
  z-index: 50;
  overflow: hidden;
}
.config-dropdown-title {
  padding: var(--space-2) var(--space-3) var(--space-1);
  font-size: 0.74rem;
  color: var(--color-text-muted);
  font-weight: 500;
}
.config-dropdown-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  font-size: 0.82rem;
  color: #444;
  cursor: pointer;
  transition: background 0.15s;
}
.config-dropdown-item:hover     { background: #f5f6ff; }
.config-dropdown-item.active    { color: var(--color-primary); background: var(--color-surface-raised); }
.config-dropdown-item.switching { opacity: 0.6; cursor: not-allowed; }
.config-dropdown-item i:first-child { color: var(--color-text-muted); font-size: 0.78rem; }
.config-dropdown-empty {
  padding: var(--space-3);
  font-size: 0.8rem;
  color: var(--color-text-muted);
  text-align: center;
}

/* ── 移动端（WANT-012）──────────────────────────── */
@media (max-width: 768px) {
  .chat-view         { padding: 0; }
  .message-list      { padding: var(--space-3) var(--space-2); }
  .tool-running-card { max-width: 95% !important; }

  /* 桌面端 config-bar 在移动端隐藏（由角色/模型徽章替代） */
  .config-bar { display: none; }
}

/* RoleModelSheet 内部样式 */
.rms-section-title {
  font-size: 0.72rem;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 10px 20px 4px;
}

.rms-option {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 20px;
  background: none;
  border: none;
  color: var(--color-text);
  font-size: 0.95rem;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
  -webkit-tap-highlight-color: transparent;
}

.rms-option:hover,
.rms-option:active { background: var(--color-surface-raised); }

.rms-option.active { color: var(--color-primary); font-weight: 500; }

.rms-icon {
  width: 20px;
  text-align: center;
  color: var(--color-text-muted);
  font-size: 0.9rem;
  flex-shrink: 0;
}

.rms-option.active .rms-icon { color: var(--color-primary); }

.rms-check {
  margin-left: auto;
  color: var(--color-primary);
  font-size: 0.85rem;
}

.rms-divider {
  height: 1px;
  background: var(--color-border);
  margin: 6px 0;
}

.rms-empty {
  padding: 12px 20px;
  font-size: 0.85rem;
  color: var(--color-text-muted);
  text-align: center;
}
</style>
