<template>
  <div class="chat-view">
    <!-- 消息搜索栏（Ctrl+F 触发） -->
    <transition name="search-slide">
      <div v-if="showSearch" class="chat-search-bar">
        <i class="fas fa-search search-bar-icon" />
        <input
          ref="searchInputRef"
          v-model="searchKeyword"
          class="search-bar-input"
          placeholder="搜索聊天记录..."
          @input="doMessageSearch"
          @keydown.esc="closeSearch"
          @keydown.enter="jumpToNext"
        />
        <span v-if="searchMatches.length" class="search-count">
          {{ searchCurrentIdx + 1 }} / {{ searchMatches.length }}
        </span>
        <button class="search-nav-btn" :disabled="!searchMatches.length" @click="jumpToPrev"><i class="fas fa-chevron-up" /></button>
        <button class="search-nav-btn" :disabled="!searchMatches.length" @click="jumpToNext"><i class="fas fa-chevron-down" /></button>
        <button class="search-close-btn" @click="closeSearch"><i class="fas fa-times" /></button>
      </div>
    </transition>

    <!-- 消息列表 -->
    <div class="message-list" ref="messageListRef">
      <!-- 空状态：产品价值引导 -->
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-hero">
          <i class="fas fa-robot empty-icon"></i>
          <h2 class="empty-title">你好，我是智能助手</h2>
          <p class="empty-sub">
            本地 AI · 私有部署 · 支持工具调用
            <span v-if="modelStatus?.includes('dolphin')" class="uncensored-badge">
              🐬 无限制模式
            </span>
          </p>
        </div>

        <!-- 示例提示词卡片 -->
        <div class="suggestion-grid">
          <div class="suggestion-card" @click="fillSuggestion('帮我计算 (23 * 45) + sqrt(169) 的结果')">
            <i class="fas fa-calculator suggestion-icon" />
            <span class="suggestion-label">数学计算</span>
            <span class="suggestion-text">帮我计算 (23 × 45) + √169</span>
          </div>
          <div class="suggestion-card" @click="fillSuggestion('现在几点了？今天是星期几？')">
            <i class="fas fa-clock suggestion-icon" />
            <span class="suggestion-label">时间查询</span>
            <span class="suggestion-text">现在几点？今天星期几？</span>
          </div>
          <div class="suggestion-card" @click="fillSuggestion('帮我写一首关于秋天的五言绝句')">
            <i class="fas fa-pen-nib suggestion-icon" />
            <span class="suggestion-label">创意写作</span>
            <span class="suggestion-text">写一首关于秋天的五言绝句</span>
          </div>
          <div class="suggestion-card" @click="fillSuggestion('每隔30分钟提醒我喝水，帮我创建周期性任务')">
            <i class="fas fa-bell suggestion-icon" />
            <span class="suggestion-label">定时提醒</span>
            <span class="suggestion-text">每隔30分钟提醒我喝水</span>
          </div>
        </div>

        <!-- 等待时间说明 -->
        <p class="empty-notice">
          <i class="fas fa-info-circle" />
          <template v-if="isCloudMode">云端模型推理中，响应速度取决于网络状况</template>
          <template v-else>本地 CPU 推理通常需要 60～300 秒，请耐心等待</template>
        </p>
      </div>

      <!-- 消息气泡 -->
      <div
        v-for="(msg, index) in messages"
        :key="msg.id != null ? msg.id : index"
        class="message-row"
        :class="msg.role"
      >
        <!-- 工具调用卡片 -->
        <template v-if="msg.role === 'tool_calls'">
          <div class="tool-calls-card">
            <div class="tool-calls-title">
              <i class="fas fa-tools" /> 本轮调用了 {{ msg.toolCalls.length }} 个工具
              <!-- 任务创建后显示快捷入口 -->
              <router-link v-if="msg.taskCreated" to="/admin/tasks" class="task-view-link">
                <i class="fas fa-tasks" /> 查看任务管理
              </router-link>
            </div>
            <div
              v-for="(tc, i) in msg.toolCalls"
              :key="i"
              class="tool-call-item"
              :class="tc.success ? 'success' : 'fail'"
            >
              <span class="tool-name"><i class="fas fa-cube" /> {{ tc.tool }}</span>
              <span class="tool-status">
                <i :class="tc.success ? 'fas fa-check' : 'fas fa-times'" />
                {{ tc.success ? '成功' : '失败' }}
              </span>
              <details v-if="tc.result" class="tool-result-details">
                <summary class="tool-result-summary">查看结果</summary>
                <pre class="tool-result">{{ formatToolResult(tc.result) }}</pre>
              </details>
            </div>
          </div>
        </template>

        <!-- 普通消息（头像 + 气泡） -->
        <template v-else>
          <div v-if="msg.role !== 'user'" class="avatar">
            <i :class="msg.notif ? 'fas fa-bell' : msg.role === 'system' ? 'fas fa-info-circle' : 'fas fa-robot'"></i>
          </div>
          <div class="bubble-wrap" :class="{ 'search-match': searchMatches.includes(index), 'search-current': searchMatches[searchCurrentIdx] === index }">
            <div class="bubble" :class="[msg.role, { 'notif': msg.notif }]">
              <div
                v-if="msg.role === 'assistant'"
                class="md-content"
                v-html="renderMarkdown(msg.content, msg.isStreaming) + (msg.isStreaming ? '<span class=\'cursor\'>▍</span>' : '')"
              />
              <span v-else v-html="highlightSearch(msg.content)"></span>
              <!-- 定时通知气泡底部跳转链接 -->
              <router-link v-if="msg.notif" to="/admin/tasks" class="notif-task-link">
                <i class="fas fa-tasks" /> 查看任务管理
              </router-link>
            </div>
            <div class="meta">
              <span class="time">{{ formatTime(msg.timestamp) }}</span>
              <span v-if="msg.responseTime" class="response-time">
                {{ msg.responseTime.toFixed(2) }}s
              </span>
              <!-- ── 点赞/踩（只在助手消息且生成完毕后显示）── -->
              <div v-if="msg.role === 'assistant' && !msg.isStreaming && msg.content"
                  class="feedback-btns">
                  <button
                    class="feedback-btn"
                    :class="{ active: getFeedback(msg, index) === 'like' }"
                    :disabled="!!getFeedback(msg, index)"
                    @click="submitFeedback(msg, index, 'like')"
                  >
                    <i class="fas fa-thumbs-up" />
                  </button>
                  <button
                    class="feedback-btn dislike"
                    :class="{ active: getFeedback(msg, index) === 'dislike' }"
                    :disabled="!!getFeedback(msg, index)"
                    @click="submitFeedback(msg, index, 'dislike')"
                  >
                    <i class="fas fa-thumbs-down" />
                  </button>
              </div>
            </div>
          </div>
          <div v-if="msg.role === 'user'" class="avatar user-avatar">
            <i class="fas fa-user"></i>
          </div>
        </template>
      </div>

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

    <!-- 悬浮导出按钮（仅安全的导出操作） -->
    <div class="export-float" v-if="messages.length > 0">
      <button class="export-float-btn" title="导出对话"
              @click.stop="showExportMenu = !showExportMenu">
        <i class="fas fa-download" />
      </button>
      <div v-if="showExportMenu" class="export-menu" @click.stop>
        <button @click="exportChat('md'); showExportMenu = false">
          <i class="fab fa-markdown" /> Markdown
        </button>
        <button @click="exportChat('txt'); showExportMenu = false">
          <i class="fas fa-file-alt" /> TXT
        </button>
      </div>
    </div>

    <!-- 悬浮清空按钮（独立放置，避免与导出操作混排造成误操作） -->
    <div class="clear-float" v-if="messages.length > 0">
      <button class="clear-float-btn" title="清空对话"
              @click.stop="handleClearChat">
        <i class="fas fa-trash-alt" />
      </button>
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

    <!-- 输入区 -->
    <div class="input-area">
      <div class="input-wrap" :class="{ 'input-wrap-thinking': isThinking || isStreaming, 'input-wrap-disconnected': !isConnected }">
        <textarea
          ref="inputRef"
          v-model="inputText"
          class="chat-input"
          :placeholder="isThinking ? '正在思考，请稍候...' : isStreaming ? '正在生成回答...' : !isConnected ? '未连接到服务器' : '输入消息，Enter 发送，Shift+Enter 换行...'"
          :disabled="!isConnected || isThinking || isStreaming"
          rows="1"
          @keydown="handleKeydown"
          @input="autoResize"
        />
        <!-- 停止生成按钮（流式输出时显示）+ 脉冲动画 -->
        <button v-if="isStreaming || isThinking" class="stop-btn stop-btn-pulse" title="点击停止生成" @click="cancelStreaming">
          <i class="fas fa-stop" />
        </button>
        <button v-else class="send-btn" :disabled="!canSend" @click="sendMessage">
          <i class="fas fa-paper-plane"></i>
        </button>
      </div>
      <div class="input-meta">
        <span v-if="!isConnected" class="hint warn">
          <i class="fas fa-exclamation-circle"></i> 未连接，请检查后端服务
        </span>
        <span v-else-if="isThinking" class="hint">
          <i class="fas fa-circle-notch fa-spin"></i> 正在思考...
        </span>
        <span v-else-if="isStreaming" class="hint">
          <i class="fas fa-circle-notch fa-spin"></i> 正在生成...
          <span class="hint-tip">（点击右侧停止按钮可中止）</span>
        </span>
        <span v-else class="hint">
          已连接 · {{ modelStatus }}
          <span v-if="projectStore.activeProject" class="project-badge" @click="router.push('/project')">
            <i class="fas fa-folder-open" /> {{ projectStore.activeProject.title }}
          </span>
        </span>
        <!-- Token 用量指示（WANT-001） -->
        <span v-if="messages.length > 0" class="token-indicator"
              :style="{ color: tokenColor }"
              :class="{ 'token-warn': tokenWarning }"
              :title="`估算 token 用量: ${estimatedTokens}/${CTX_LIMIT}`">
          <i class="fas fa-database" style="font-size:0.7rem" />
          {{ estimatedTokens }}/{{ CTX_LIMIT }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/core'
import 'highlight.js/styles/github.css'
import DOMPurify from 'dompurify'

// 按需注册常用语言（替代全量 import，大幅减小 chunk）
import langJs   from 'highlight.js/lib/languages/javascript'
import langTs   from 'highlight.js/lib/languages/typescript'
import langPy   from 'highlight.js/lib/languages/python'
import langJava from 'highlight.js/lib/languages/java'
import langBash from 'highlight.js/lib/languages/bash'
import langSql  from 'highlight.js/lib/languages/sql'
import langJson from 'highlight.js/lib/languages/json'
import langXml  from 'highlight.js/lib/languages/xml'
import langCss  from 'highlight.js/lib/languages/css'
import langGo   from 'highlight.js/lib/languages/go'
import langRust from 'highlight.js/lib/languages/rust'
import langCpp  from 'highlight.js/lib/languages/cpp'
import langYaml from 'highlight.js/lib/languages/yaml'
import langMd   from 'highlight.js/lib/languages/markdown'

hljs.registerLanguage('javascript', langJs);  hljs.registerLanguage('js', langJs)
hljs.registerLanguage('typescript', langTs);  hljs.registerLanguage('ts', langTs)
hljs.registerLanguage('python', langPy);      hljs.registerLanguage('py', langPy)
hljs.registerLanguage('java', langJava)
hljs.registerLanguage('bash', langBash);      hljs.registerLanguage('sh', langBash)
hljs.registerLanguage('shell', langBash)
hljs.registerLanguage('sql', langSql)
hljs.registerLanguage('json', langJson)
hljs.registerLanguage('xml', langXml);        hljs.registerLanguage('html', langXml)
hljs.registerLanguage('css', langCss)
hljs.registerLanguage('go', langGo)
hljs.registerLanguage('rust', langRust)
hljs.registerLanguage('cpp', langCpp);        hljs.registerLanguage('c', langCpp)
hljs.registerLanguage('yaml', langYaml);      hljs.registerLanguage('yml', langYaml)
hljs.registerLanguage('markdown', langMd);    hljs.registerLanguage('md', langMd)
import { ElMessage, ElMessageBox } from 'element-plus'
import { useWebSocketStore }    from '@/stores/websocket'
import { useAuthStore }         from '@/stores/auth'
import { useLocalSessionStore } from '@/stores/localSession'
import { useProjectStore }      from '@/stores/project'
import { submitFeedback as apiFeedback } from '@/services/api'
import { formatTime, formatForFilename } from '@/utils/date'
import { genId } from '@/utils/string'

// ── marked 配置 ────────────────────────────────────────────
marked.setOptions({
  highlight: (code, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  },
  breaks: true,
  gfm: true,
})

// ── Store ──────────────────────────────────────────────────
const router       = useRouter()
const store        = useWebSocketStore()
const sessionStore = useLocalSessionStore()
const projectStore = useProjectStore()
const messages     = computed(() => store.messages)
const isConnected  = computed(() => store.isConnected)
const modelStatus  = computed(() => store.modelStatus)
const systemInfo   = computed(() => store.systemInfo)
const isCloudMode  = computed(() => !!systemInfo.value?.cloud_mode)
const isStreaming      = computed(() => store.isStreaming)
const activeToolSteps  = computed(() => store.activeToolSteps)
const cancelStreaming   = () => store.cancelStreaming()

// ── 本地状态 ───────────────────────────────────────────────
const inputText      = ref('')
const isThinking     = ref(false)
const messageListRef = ref(null)

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

// ── 上下文 Token 用量估算（WANT-001）─────────────────────────
// 从 /api/system/resources 拉取实际 num_ctx；回退到 8192（settings.py 默认值）
const CTX_LIMIT = ref(8192)
const fetchCtxLimit = async () => {
  try {
    const { getSystemResources } = await import('@/services/api')
    const res = await getSystemResources()
    if (res?.ollama_num_ctx) CTX_LIMIT.value = res.ollama_num_ctx
  } catch { /* 忽略，用默认值 */ }
}
onMounted(fetchCtxLimit)
const estimatedTokens = computed(() => {
  const allText = messages.value
    .filter(m => m.role === 'user' || m.role === 'assistant')
    .map(m => m.content || '')
    .join(' ')
  const cjk = (allText.match(/[一-龥぀-ヿ]/g) || []).length
  const words = (allText.match(/[a-zA-Z0-9]+/g) || []).length
  return Math.round(cjk * 1.5 + words)
})
const formatToolResult = (result) => {
  if (!result) return ''
  const s = typeof result === 'string' ? result : JSON.stringify(result)
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}

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
const searchInputRef  = ref(null)

// 转义 HTML 特殊字符防 XSS，再插入高亮标记
const escapeHtml = (s) => s
  .replace(/&/g, '&amp;').replace(/</g, '&lt;')
  .replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const highlightSearch = (text) => {
  if (!searchKeyword.value || !text) return escapeHtml(text || '')
  const kw = searchKeyword.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const escaped = escapeHtml(text)
  const kwEscaped = escapeHtml(searchKeyword.value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escaped.replace(new RegExp(kwEscaped, 'gi'),
    m => `<mark class="search-hl">${m}</mark>`)
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
  nextTick(() => searchInputRef.value?.focus())
}
const inputRef       = ref(null)

// ── 计算属性 ───────────────────────────────────────────────
const canSend = computed(() =>
  isConnected.value &&
  !isThinking.value &&
  !isStreaming.value &&
  inputText.value.trim().length > 0
)

// ── 点赞/踩 ───────────────────────────────────────────────
const authStore   = useAuthStore()
// 从 localStorage 恢复已评记录，key 用 request_id 或消息内容 hash
const FEEDBACK_KEY = 'agent_feedback_map'

const loadFeedbackMap = () => {
  try {
    return JSON.parse(localStorage.getItem(FEEDBACK_KEY) || '{}')
  } catch {
    return {}
  }
}

const feedbackMap = ref(loadFeedbackMap())

const saveFeedbackMap = () => {
  localStorage.setItem(FEEDBACK_KEY, JSON.stringify(feedbackMap.value))
}

const submitFeedback = async (msg, index, rating) => {
  const key = getMsgKey(msg)
  if (feedbackMap.value[key]) return

  feedbackMap.value[key] = rating
  saveFeedbackMap()

  const userMsg = [...messages.value]
    .slice(0, index)
    .reverse()
    .find(m => m.role === 'user')

  // 从当前回复前的 tool_calls 消息中收集工具名称（BUG-004）
  const toolsUsed = messages.value
    .slice(0, index + 1)
    .filter(m => m.role === 'tool_calls')
    .flatMap(m => (m.toolCalls || []).map(tc => tc.tool))

  // 截取 response 前 200 字符，过滤掉系统提示词前缀（BUG-003）
  let responseText = msg.content || ''
  const sysPromptMarkers = ['请用中文回答', '你是一个有帮助的AI助手', 'You are a helpful']
  for (const marker of sysPromptMarkers) {
    const idx = responseText.indexOf(marker)
    if (idx !== -1 && idx < 200) {
      // 系统提示词泄漏到响应头部，找到第一个换行后的真实内容
      const realStart = responseText.indexOf('\n\n', idx)
      if (realStart !== -1) responseText = responseText.slice(realStart + 2)
    }
  }
  responseText = responseText.slice(0, 200)

  try {
    await apiFeedback({
      username:        authStore.username || 'admin',
      message:         userMsg?.content  || '',
      response:        responseText,
      rating,
      response_time:   msg.responseTime  || null,
      tools_used:      toolsUsed,
      skill_triggered: null,
      request_id:      null,
    })
  } catch (e) {
    console.error('[Feedback] 提交失败:', e)
  }
}

// ── 工具方法 ───────────────────────────────────────────────
const _MD_ALLOWED = {
  ALLOWED_TAGS: ['p','br','strong','em','code','pre','blockquote','ul','ol','li',
                 'h1','h2','h3','h4','h5','h6','a','img','span','div','table',
                 'thead','tbody','tr','th','td','mark'],
  ALLOWED_ATTR: ['href','src','alt','class','title','target'],
}
// 缓存已完成消息的渲染结果，避免每次 Vue 重渲染重复 parse（流式消息不缓存）
const _mdCache = new Map()
const renderMarkdown = (text, streaming = false) => {
  if (!text) return ''
  // 流式进行时跳过 marked.parse（O(n) per token），只做安全转义显示原始文本
  if (streaming) {
    return DOMPurify.sanitize(text.replace(/</g, '&lt;').replace(/>/g, '&gt;'), _MD_ALLOWED)
  }
  if (_mdCache.has(text)) return _mdCache.get(text)
  const html = DOMPurify.sanitize(marked.parse(text), _MD_ALLOWED)
  if (_mdCache.size > 300) _mdCache.delete(_mdCache.keys().next().value) // LRU 上限
  _mdCache.set(text, html)
  return html
}

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

const autoResize = () => {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

const fillSuggestion = (text) => {
  inputText.value = text
  nextTick(() => {
    inputRef.value?.focus()
    autoResize()
  })
}

// ── 发送逻辑 ───────────────────────────────────────────────
const sendMessage = () => {
  const text = inputText.value.trim()
  if (!text || !canSend.value) return

  const userMsg = { id: genId(), role: 'user', content: text, timestamp: new Date() }
  store.addMessage(userMsg)
  sessionStore.addMessage({ role: 'user', content: text, timestamp: new Date().toISOString() })

  inputText.value = ''
  nextTick(() => { if (inputRef.value) inputRef.value.style.height = 'auto' })

  isThinking.value = true

  const activeTasks = projectStore.activeProject?.task_tree?.root_tasks ?? null
  const ok = store.sendChatMessage(text, true, true, projectStore.activeProjectId, activeTasks)
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

const handleKeydown = (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

const getMsgKey = (msg) =>
  msg.id != null
    ? msg.id
    : (msg.content || '').slice(0, 80) + '_' + (msg.timestamp instanceof Date ? msg.timestamp.getTime() : (msg.timestamp || 0))
const getFeedback = (msg) => feedbackMap.value[getMsgKey(msg)]

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

const showExportMenu = ref(false)

// 新开对话：清空显示 + 开新会话，保留 AI 后端记忆
const handleNewConversation = async () => {
  store.messages.splice(0)
  try { localStorage.removeItem('ia_chat_history') } catch {}
  await sessionStore.startNewSession()
  ctxBannerDismissed.value = true
  ElMessage({ message: '已新开对话', type: 'success', duration: 1500 })
}

const handleClearChat = async () => {
  try {
    await ElMessageBox.confirm('清空后将同时清除 AI 的对话记忆，无法恢复。', '确认清空对话', {
      confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning',
      confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  isThinking.value = false
  stopThinkingTimer()
  await store.clearMessages()
  ElMessage({ message: '对话已清空', type: 'success', duration: 1500 })
}

// 点击其他地方关闭菜单（UX-009：选择器与模板中类名一致）
const closeExportMenu = (e) => {
  if (!e.target.closest('.export-float') && !e.target.closest('.export-menu')) {
    showExportMenu.value = false
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
  if (!sessionStore.activeId) await sessionStore.startNewSession()

  scrollToBottom()
  inputRef.value?.focus()
  document.addEventListener('click', closeExportMenu)
  document.addEventListener('keydown', handleGlobalKey)
})

onUnmounted(() => {
  document.removeEventListener('click', closeExportMenu)
  document.removeEventListener('keydown', handleGlobalKey)
  stopThinkingTimer()
  clearTimeout(_searchTimer)
})

</script>

<style scoped>
/* ── 消息搜索栏 ──────────────────────────────────────────── */
.chat-search-bar {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 12px; background: white;
  border-bottom: 1px solid #e0e3e8;
  flex-shrink: 0;
}
.search-bar-icon { color: #aaa; font-size: 0.85rem; }
.search-bar-input {
  flex: 1; border: none; outline: none;
  font-size: 0.88rem; color: #333;
}
.search-count { font-size: 0.78rem; color: #aaa; white-space: nowrap; }
.search-nav-btn, .search-close-btn {
  background: none; border: none; color: #aaa;
  cursor: pointer; padding: 4px 6px; border-radius: 4px; font-size: 0.8rem;
}
.search-nav-btn:hover, .search-close-btn:hover { background: #f0f0f0; color: #333; }
.search-nav-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.search-slide-enter-active, .search-slide-leave-active { transition: all 0.2s; }
.search-slide-enter-from, .search-slide-leave-to { opacity: 0; transform: translateY(-8px); }

/* 搜索命中的气泡 */
.bubble-wrap.search-match .bubble { outline: 2px solid #ffe082; }
.bubble-wrap.search-current .bubble { outline: 2px solid #f57c00; }
:deep(.search-hl) { background: #fff176; color: #333; border-radius: 2px; padding: 0 1px; }

/* ── 工具调用卡片 ────────────────────────────────────────── */
.tool-calls-card {
  max-width: 80%;
  background: #f0f4ff;
  border: 1px solid #d0d9f5;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 0.85rem;
  margin: 0 auto;
}
.tool-calls-title {
  font-weight: 500;
  color: #4a5568;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.tool-calls-title i { color: #667eea; }
.task-view-link {
  margin-left: auto;
  font-size: 0.8rem;
  color: #667eea;
  text-decoration: none;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid #c7d2f5;
  border-radius: 12px;
  white-space: nowrap;
}
.task-view-link:hover { background: #eef0ff; }
.tool-call-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 0;
  border-top: 1px solid #e2e8f0;
}
.tool-row-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tool-args {
  font-size: 0.78rem; color: #888;
  font-family: monospace;
  word-break: break-all;
  padding-left: 4px;
}
.tool-name {
  font-weight: 500;
  color: #4a5568;
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 120px;
}
.tool-name i { color: #667eea; font-size: 0.8rem; }
.tool-status { font-size: 0.82rem; }
.tool-call-item.success .tool-status { color: #2e7d32; }
.tool-call-item.fail    .tool-status { color: #c62828; }
.tool-call-item.running .tool-status { color: #667eea; }
.tool-running-card {
  max-width: 80%;
  background: #f8f7ff;
  border: 1px dashed #b0bef5;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 0.85rem;
  margin: 0 auto;
  opacity: 0.9;
}
.tool-result {
  width: 100%;
  font-size: 0.8rem;
  color: #718096;
  background: white;
  border-radius: 4px;
  padding: 4px 8px;
  margin-top: 4px;
  word-break: break-all;
}

/* ── 光标动画 ─────────────────────────────────────────────── */
.cursor {
  display: inline-block;
  animation: blink 0.8s step-end infinite;
  color: #667eea;
  font-size: 1rem;
  vertical-align: middle;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
}

/* ── 整体布局 ─────────────────────────────────────────────── */
.chat-view {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f8f9fa;
}

/* ── 消息列表 ─────────────────────────────────────────────── */
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── 空状态（产品引导） ──────────────────────────────────── */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 40px 24px;
  max-width: 700px;
  margin: 0 auto;
  width: 100%;
}
.empty-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.empty-icon  { font-size: 2.8rem; color: #c5c8f0; }
.empty-title { font-size: 1.25rem; font-weight: 600; color: #444; margin: 0; }
.empty-sub   { font-size: 0.88rem; color: #999; display: flex; align-items: center; gap: 8px; }
.uncensored-badge {
  background: linear-gradient(135deg, #f6d365, #fda085);
  color: white;
  padding: 2px 8px;
  border-radius: 20px;
  font-size: 0.78rem;
  font-weight: 600;
}

/* 示例提示词卡片 */
.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  width: 100%;
}
.suggestion-card {
  display: grid;
  grid-template-areas: "icon label" "icon text";
  grid-template-columns: 32px 1fr;
  gap: 2px 8px;
  padding: 12px 14px;
  border: 1px solid #e8eaf0;
  border-radius: 12px;
  background: white;
  cursor: pointer;
  transition: all 0.15s;
  align-items: center;
}
.suggestion-card:hover {
  border-color: #667eea;
  background: #f8f9ff;
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(102,126,234,0.12);
}
.suggestion-icon {
  grid-area: icon;
  font-size: 1.1rem;
  color: #667eea;
  justify-self: center;
}
.suggestion-label {
  grid-area: label;
  font-size: 0.72rem;
  color: #aaa;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.suggestion-text {
  grid-area: text;
  font-size: 0.84rem;
  color: #555;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.empty-notice {
  font-size: 0.78rem;
  color: #bbb;
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0;
}
@media (max-width: 600px) {
  .suggestion-grid { grid-template-columns: 1fr; }
}

/* ── 消息行 ───────────────────────────────────────────────── */
.message-row            { display: flex; align-items: flex-end; gap: 8px; }
.message-row.user       { flex-direction: row-reverse; }
.message-row.system     { justify-content: center; }
.message-row.tool_calls { justify-content: center; }

/* ── 头像 ─────────────────────────────────────────────────── */
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #667eea;
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
.bubble-wrap { max-width: 68%; display: flex; flex-direction: column; gap: 3px; }
.message-row.user   .bubble-wrap { align-items: flex-end; }
.message-row.system .bubble-wrap { max-width: 90%; align-items: center; }

/* ── 气泡 ─────────────────────────────────────────────────── */
.bubble {
  padding: 10px 14px;
  border-radius: 16px;
  font-size: 0.93rem;
  line-height: 1.6;
  word-break: break-word;
}
.bubble.user {
  background: #667eea;
  color: white;
  border-bottom-right-radius: 4px;
}
.bubble.assistant {
  background: white;
  color: #333;
  border: 1px solid #e8eaed;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}
.bubble.system {
  background: #fff8e1;
  color: #856404;
  font-size: 0.85rem;
  border-radius: 8px;
  border: 1px solid #ffe082;
  padding: 6px 12px;
}
/* 定时任务通知系统消息 */
.bubble.system:has(.md-content),
.bubble.system.notif {
  background: linear-gradient(135deg, #fff3cd, #fff8e1);
  border-color: #ffc107;
  border-left: 3px solid #ff9800;
  box-shadow: 0 2px 8px rgba(255,152,0,0.15);
  font-size: 0.9rem;
  padding: 10px 14px;
}

/* ── Markdown 内容 ────────────────────────────────────────── */
.md-content :deep(p)            { margin: 0 0 8px; }
.md-content :deep(p:last-child) { margin-bottom: 0; }
.md-content :deep(pre)          { background: #f6f8fa; border-radius: 8px; padding: 12px; overflow-x: auto; margin: 8px 0; }
.md-content :deep(code)         { font-family: 'Fira Code', Consolas, monospace; font-size: 0.88em; }
.md-content :deep(p > code)     { background: #f0f0f0; padding: 2px 5px; border-radius: 4px; }
.md-content :deep(ul),
.md-content :deep(ol)           { padding-left: 20px; margin: 6px 0; }
.md-content :deep(li)           { margin-bottom: 2px; }
.md-content :deep(blockquote)   { border-left: 3px solid #667eea; margin: 8px 0; padding: 4px 12px; color: #666; background: #f8f8ff; border-radius: 0 6px 6px 0; }
.md-content :deep(table)        { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 0.9em; }
.md-content :deep(th),
.md-content :deep(td)           { border: 1px solid #ddd; padding: 6px 10px; }
.md-content :deep(th)           { background: #f0f0f0; font-weight: 500; }
.md-content :deep(a)            { color: #667eea; }
.md-content :deep(h1),
.md-content :deep(h2),
.md-content :deep(h3)           { margin: 10px 0 6px; font-weight: 500; }

/* ── 思考中动画 ───────────────────────────────────────────── */
.thinking-bubble { display: flex; align-items: center; gap: 5px; padding: 12px 16px; }
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #bbb;
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

/* ── 时间 / 响应时间 ──────────────────────────────────────── */
.meta          { display: flex; align-items: center; gap: 8px; }
.time          { font-size: 0.75rem; color: #aaa; }
.response-time { font-size: 0.75rem; color: #bbb; }

/* ── 输入区 ───────────────────────────────────────────────── */
.input-area { border-top: 1px solid #e8eaed; background: white; padding: 12px 16px; }
.input-wrap {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  background: #f8f9fa;
  border: 1px solid #e0e3e8;
  border-radius: 12px;
  padding: 8px 10px;
  transition: border-color 0.2s;
}
.input-wrap:focus-within { border-color: #667eea; }
.input-wrap-thinking    { border-color: #667eea; background: #faf9ff; }
.input-wrap-disconnected{ border-color: #f0a0a0; background: #fff8f8; }
.chat-input {
  flex: 1;
  border: none;
  background: transparent;
  resize: none;
  font-size: 0.93rem;
  line-height: 1.5;
  color: #333;
  outline: none;
  max-height: 160px;
  overflow-y: auto;
  font-family: inherit;
}
.chat-input::placeholder     { color: #aaa; }
.chat-input:disabled         { opacity: 0.7; cursor: not-allowed; }
.input-wrap-thinking .chat-input::placeholder { color: #667eea; font-style: italic; }
.input-wrap-disconnected .chat-input::placeholder { color: #e57373; }

/* 停止按钮脉冲动画 */
.stop-btn-pulse {
  animation: pulse-red 1.4s infinite;
}
@keyframes pulse-red {
  0%, 100% { box-shadow: 0 0 0 0 rgba(229,57,53,0.4); }
  50%       { box-shadow: 0 0 0 6px rgba(229,57,53,0); }
}
.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  border: none;
  background: #667eea;
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.9rem;
  transition: background 0.2s, transform 0.1s;
  flex-shrink: 0;
}
.send-btn:hover:not(:disabled)  { background: #5a6fd6; }
.send-btn:active:not(:disabled) { transform: scale(0.95); }
.send-btn:disabled              { background: #ccc; cursor: not-allowed; }
.stop-btn {
  width: 36px; height: 36px; border-radius: 8px;
  border: none; background: #e53935; color: white;
  cursor: pointer; font-size: 0.85rem;
  display: flex; align-items: center; justify-content: center;
  transition: background 0.2s;
  flex-shrink: 0;
}
.stop-btn:hover { background: #c62828; }

/* ── 底部提示 ─────────────────────────────────────────────── */
.input-meta { margin-top: 6px; padding: 0 4px; }
.hint       { font-size: 0.78rem; color: #aaa; display: flex; align-items: center; gap: 5px; }
.hint.warn  { color: #e67e22; }
.hint i     { font-size: 0.75rem; }
.hint-tip   { font-size: 0.72rem; color: #ccc; }
.project-badge { background: #e8f4fd; color: #1976d2; border-radius: 4px; padding: 1px 6px; font-size: 0.72rem; display: inline-flex; align-items: center; gap: 3px; cursor: pointer; }
.project-badge:hover { background: #d0eaf9; }
.token-indicator { font-size: 0.72rem; margin-left: auto; display: flex; align-items: center; gap: 4px; }
.token-warn      { animation: blink 1.5s ease-in-out infinite; }

/* ── 上下文超限 Banner ─────────────────────────────── */
.ctx-warn-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 16px;
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
  padding: 4px 12px; border-radius: 6px;
  border: 1px solid #e65100; background: #e65100; color: white;
  font-size: 0.8rem; cursor: pointer; white-space: nowrap;
  transition: background 0.15s;
  display: flex; align-items: center; gap: 5px;
}
.ctx-warn-btn:hover { background: #bf360c; }
.ctx-warn-close {
  background: none; border: none; color: #bf360c;
  cursor: pointer; font-size: 0.9rem; padding: 2px 4px;
  opacity: 0.6; flex-shrink: 0;
}
.ctx-warn-close:hover { opacity: 1; }

/* 过渡动画 */
.banner-slide-enter-active,
.banner-slide-leave-active { transition: all 0.25s ease; }
.banner-slide-enter-from,
.banner-slide-leave-to   { opacity: 0; transform: translateY(-8px); }
@keyframes blink { 50% { opacity: 0.6; } }

/* ── 滚动条 ───────────────────────────────────────────────── */
.message-list::-webkit-scrollbar       { width: 4px; }
.message-list::-webkit-scrollbar-thumb { background: #ddd; border-radius: 2px; }

/* ── 点赞/踩 ── */
.feedback-btns {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 8px;
}
.feedback-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: #ccc;
  font-size: 0.82rem;
  padding: 2px 5px;
  border-radius: 4px;
  transition: color 0.2s, background 0.2s;
  line-height: 1;
}
.feedback-btn:hover:not(:disabled) { color: #43a047; background: #f0f9f0; }
.feedback-btn.dislike:hover:not(:disabled) { color: #e53935; background: #fce4e4; }
.feedback-btn.active { color: #43a047; }
.feedback-btn.dislike.active { color: #e53935; }
.feedback-btn:disabled { cursor: default; opacity: 0.6; }

/* ── 悬浮导出按钮 ── */
.export-float {
  position: absolute;
  bottom: 90px;
  right: 20px;
  z-index: 10;
}
.export-float-btn {
  width: 36px; height: 36px;
  border-radius: 50%;
  border: 1px solid #e0e3e8;
  background: white;
  color: #888;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font-size: 0.85rem;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  transition: all 0.2s;
}
.export-float-btn:hover { border-color: #667eea; color: #667eea; box-shadow: 0 3px 12px rgba(102,126,234,0.3); }
.export-menu {
  position: absolute;
  bottom: calc(100% + 6px);
  right: 0;
  background: white;
  border: 1px solid #e0e3e8;
  border-radius: 10px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.1);
  overflow: hidden;
  z-index: 100;
  min-width: 130px;
}
.export-menu button {
  display: flex; align-items: center; gap: 8px;
  width: 100%; padding: 10px 14px;
  border: none; background: none;
  font-size: 0.88rem; color: #444;
  cursor: pointer; text-align: left;
  transition: background 0.15s;
}
.export-menu button:hover { background: #f5f5f5; }
.export-menu button i { color: #667eea; width: 14px; }

/* 独立清空悬浮按钮 */
.clear-float {
  position: absolute;
  bottom: 46px;
  right: 20px;
  z-index: 10;
}
.clear-float-btn {
  width: 36px; height: 36px;
  border-radius: 50%;
  border: 1px solid #ffd0cd;
  background: #fff;
  color: #e53935;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font-size: 14px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  transition: all 0.2s;
}
.clear-float-btn:hover {
  border-color: #e53935;
  background: #fff5f5;
  box-shadow: 0 3px 12px rgba(229,57,53,0.25);
}

/* 定时通知气泡底部链接 */
.notif-task-link {
  display: inline-flex; align-items: center; gap: 5px;
  margin-top: 8px; font-size: 0.78rem;
  color: #ff9800; text-decoration: none;
  opacity: 0.85; transition: opacity 0.15s;
  border-top: 1px solid rgba(255,152,0,0.2);
  padding-top: 6px; width: 100%;
}
.notif-task-link:hover { opacity: 1; text-decoration: underline; }

/* ── 移动端（WANT-012）──────────────────────────── */
@media (max-width: 768px) {
  .chat-view         { padding: 0; }
  .message-list      { padding: 12px 8px; }
  .bubble-wrap       { max-width: 92% !important; }
  .chat-input        { font-size: 16px !important; } /* 防止 iOS 自动缩放 */
  .input-area        { padding: 8px !important; }
  .message-row.user  { justify-content: flex-end; }
  .export-float      { bottom: 70px; right: 10px; }
  .clear-float       { bottom: 26px; right: 10px; }
  .tool-calls-card,
  .tool-running-card { max-width: 95% !important; }
  .search-bar-input  { font-size: 16px !important; }
}
</style>