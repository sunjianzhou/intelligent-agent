<template>
  <div
    class="message-row"
    :class="[msg.role, { 'retract-mode': retractMode && canRetract(msg) }]"
    @click="retractMode && canRetract(msg) ? $emit('toggle-retract', msg) : null"
  >
    <!-- 撤回模式勾选框 -->
    <div v-if="retractMode && canRetract(msg)" class="retract-checkbox" @click.stop="$emit('toggle-retract', msg)">
      <i :class="selectedRetractIds.has(msg.id) ? 'fas fa-check-square' : 'far fa-square'" />
    </div>
    <!-- 已撤回占位条 -->
    <div v-if="msg.isRetracted" class="retracted-placeholder">
      <i class="fas fa-rotate-left" /> 该消息已被撤回
    </div>
    <!-- 工具调用卡片 -->
    <template v-if="!msg.isRetracted && msg.role === 'tool_calls'">
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
    <template v-else-if="!msg.isRetracted">
      <div v-if="msg.role !== 'user'" class="avatar">
        <i :class="msg.notif ? 'fas fa-bell' : msg.role === 'system' ? 'fas fa-info-circle' : 'fas fa-robot'"></i>
      </div>
      <div class="bubble-wrap" :class="{ 'search-match': isSearchMatch, 'search-current': isSearchCurrent }">
        <div class="bubble" :class="[msg.role, { 'notif': msg.notif }]">
          <!-- CoT 思维过程（仅 assistant 气泡，有 thinkingText 时才显示）-->
          <details
            v-if="msg.role === 'assistant' && msg.thinkingText"
            class="cot-block"
            :open="msg.isStreaming"
          >
            <summary class="cot-summary">
              <i class="fas fa-brain cot-icon" />
              <span>思维过程</span>
              <i v-if="msg.isStreaming" class="fas fa-circle-notch fa-spin cot-spin" />
              <span v-else class="cot-len">{{ msg.thinkingText.length }} 字</span>
            </summary>
            <div
              class="cot-content"
              v-html="renderMarkdown(msg.thinkingText, msg.isStreaming)"
            />
          </details>
          <!-- 正式回答 -->
          <div
            v-if="msg.role === 'assistant'"
            class="md-content"
            v-html="renderMarkdown(msg.content, msg.isStreaming) + (msg.isStreaming ? '<span class=\'cursor\'>▍</span>' : '')"
          />
          <!-- user 气泡图片预览（多模态消息） -->
          <img
            v-if="msg.role === 'user' && msg.imagePreview"
            :src="msg.imagePreview"
            class="msg-img-thumb"
            alt="附图"
          />
          <span v-if="msg.role !== 'assistant'" v-html="highlightSearch(msg.content, searchKeyword)"></span>
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
        </div>
        <!-- ── 悬停操作栏（复制/点赞/踩）── -->
        <div v-if="msg.role === 'assistant' && !msg.isStreaming && msg.content"
             class="bubble-actions">
          <button class="bact-btn" title="复制" @click="copyMessage(msg.content)">
            <i class="fas fa-copy" />
          </button>
          <button
            class="bact-btn"
            title="有帮助"
            :class="{ active: getFeedback(msg) === 'like' }"
            :disabled="!!getFeedback(msg)"
            @click="submitFeedback(msg, index, 'like')"
          >
            <i class="fas fa-thumbs-up" />
          </button>
          <button
            class="bact-btn dislike"
            title="没帮助"
            :class="{ active: getFeedback(msg) === 'dislike' }"
            :disabled="!!getFeedback(msg)"
            @click="submitFeedback(msg, index, 'dislike')"
          >
            <i class="fas fa-thumbs-down" />
          </button>
          <button
            class="bact-btn"
            title="从此处分支对话"
            @click="$emit('branch', index)"
          >
            <i class="fas fa-code-branch" />
          </button>
        </div>
      </div>
      <div v-if="msg.role === 'user'" class="avatar user-avatar">
        <i class="fas fa-user"></i>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { renderMarkdown, highlightSearch } from '@/utils/markdown'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { submitFeedback as apiFeedback } from '@/services/api'
import { formatTime } from '@/utils/date'

const props = defineProps({
  msg:               { type: Object,  required: true },
  index:             { type: Number,  required: true },
  retractMode:       { type: Boolean, default: false },
  selectedRetractIds: { type: Set,    default: () => new Set() },
  searchKeyword:     { type: String,  default: '' },
  isSearchMatch:     { type: Boolean, default: false },
  isSearchCurrent:   { type: Boolean, default: false },
  messages:          { type: Array,   default: () => [] },
})

defineEmits(['toggle-retract', 'branch'])

const authStore = useAuthStore()

const canRetract = (msg) => (msg.role === 'user' || msg.role === 'assistant') && !msg.isRetracted

const formatToolResult = (result) => {
  if (!result) return ''
  const s = typeof result === 'string' ? result : JSON.stringify(result)
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}

const copyMessage = async (content) => {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage({ message: '已复制', type: 'success', duration: 1200 })
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

// ── 点赞/踩（localStorage 记录，key 用 request_id 或消息内容 hash）──
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

const getMsgKey = (msg) =>
  msg.id != null
    ? msg.id
    : (msg.content || '').slice(0, 80) + '_' + (msg.timestamp instanceof Date ? msg.timestamp.getTime() : (msg.timestamp || 0))

const getFeedback = (msg) => feedbackMap.value[getMsgKey(msg)]

const submitFeedback = async (msg, index, rating) => {
  const key = getMsgKey(msg)
  if (feedbackMap.value[key]) return

  feedbackMap.value[key] = rating
  saveFeedbackMap()

  const userMsg = [...props.messages]
    .slice(0, index)
    .reverse()
    .find(m => m.role === 'user')

  // 从当前回复前的 tool_calls 消息中收集工具名称（BUG-004）
  const toolsUsed = props.messages
    .slice(0, index + 1)
    .filter(m => m.role === 'tool_calls')
    .flatMap(m => (m.toolCalls || []).map(tc => tc.tool))

  // 截取 response 前 200 字符，过滤掉系统提示词前缀（BUG-003）
  let responseText = msg.content || ''
  const sysPromptMarkers = ['请用中文回答', '你是一个有帮助的AI助手', 'You are a helpful']
  for (const marker of sysPromptMarkers) {
    const idx = responseText.indexOf(marker)
    if (idx !== -1 && idx < 200) {
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
    ElMessage({ message: '反馈提交失败，请重试', type: 'error', duration: 2500 })
    feedbackMap.value[key] = null  // 重置按钮状态，允许用户再次提交
  }
}
</script>

<style scoped>
/* 搜索命中的气泡 */
.bubble-wrap.search-match .bubble { outline: 2px solid #ffe082; }
.bubble-wrap.search-current .bubble { outline: 2px solid #f57c00; }
:deep(.search-hl) { background: #fff176; color: var(--color-text); border-radius: 2px; padding: 0 1px; }

/* ── 工具调用卡片 ────────────────────────────────────────── */
.tool-calls-card {
  max-width: 80%;
  background: #f0f4ff;
  border: 1px solid #d0d9f5;
  border-radius: var(--radius-md);
  padding: 10px 14px;
  font-size: 0.85rem;
  margin: 0 auto;
}
.tool-calls-title {
  font-weight: 500;
  color: #4a5568;
  margin-bottom: var(--space-2);
  display: flex;
  align-items: center;
  gap: 6px;
}
.tool-calls-title i { color: var(--color-primary); }
.task-view-link {
  margin-left: auto;
  font-size: 0.8rem;
  color: var(--color-primary);
  text-decoration: none;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px var(--space-2);
  border: 1px solid #c7d2f5;
  border-radius: var(--radius-md);
  white-space: nowrap;
}
.task-view-link:hover { background: #eef0ff; }
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
.tool-call-item.success .tool-status { color: var(--color-success); }
.tool-call-item.fail    .tool-status { color: var(--color-danger); }
.tool-call-item.running .tool-status { color: var(--color-primary); }
.tool-result {
  width: 100%;
  font-size: 0.8rem;
  color: #718096;
  background: var(--color-surface);
  border-radius: 4px;
  padding: var(--space-1) var(--space-2);
  margin-top: var(--space-1);
  word-break: break-all;
}

/* ── 光标动画 ─────────────────────────────────────────────── */
.cursor {
  display: inline-block;
  animation: blink 0.8s step-end infinite;
  color: var(--color-primary);
  font-size: 1rem;
  vertical-align: middle;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
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

/* ── Markdown 内容 ────────────────────────────────────────── */
.md-content :deep(p)            { margin: 0 0 var(--space-2); }
.md-content :deep(p:last-child) { margin-bottom: 0; }
.md-content :deep(pre)          { background: #f6f8fa; border-radius: var(--radius-sm); padding: var(--space-3); overflow-x: auto; margin: var(--space-2) 0; }
.md-content :deep(code)         { font-family: 'Fira Code', Consolas, monospace; font-size: 0.88em; }
.md-content :deep(p > code)     { background: #f0f0f0; padding: 2px 5px; border-radius: 4px; }
.md-content :deep(ul),
.md-content :deep(ol)           { padding-left: 20px; margin: 6px 0; }
.md-content :deep(li)           { margin-bottom: 2px; }
.md-content :deep(blockquote)   { border-left: 3px solid var(--color-primary); margin: var(--space-2) 0; padding: var(--space-1) var(--space-3); color: var(--color-text-secondary); background: #f8f8ff; border-radius: 0 var(--radius-sm) var(--radius-sm) 0; }
.md-content :deep(table)        { border-collapse: collapse; width: 100%; margin: var(--space-2) 0; font-size: 0.9em; }
.md-content :deep(th),
.md-content :deep(td)           { border: 1px solid #ddd; padding: 6px 10px; }
.md-content :deep(th)           { background: #f0f0f0; font-weight: 500; }
.md-content :deep(a)            { color: var(--color-primary); }
.md-content :deep(h1),
.md-content :deep(h2),
.md-content :deep(h3)           { margin: 10px 0 6px; font-weight: 500; }

/* ── 时间 / 响应时间 ──────────────────────────────────────── */
.meta          { display: flex; align-items: center; gap: var(--space-2); }
.time          { font-size: 0.75rem; color: var(--color-text-muted); }
.response-time { font-size: 0.75rem; color: var(--color-text-muted); }

/* 气泡内图片缩略图 */
.msg-img-thumb {
  display: block;
  max-height: 200px;
  max-width: 100%;
  border-radius: var(--radius-sm);
  border: 1px solid rgba(255,255,255,0.3);
  margin-bottom: 6px;
  object-fit: contain;
}

/* ── 气泡悬停操作栏（复制/点赞/踩）── */
.bubble-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transform: translateY(-3px);
  transition: opacity 0.15s, transform 0.15s;
  pointer-events: none;
}
.message-row.retract-mode { cursor: pointer; }
.retract-checkbox {
  display: flex; align-items: center; padding: 0 6px; color: var(--color-primary, #3b82f6);
  font-size: 1rem; flex-shrink: 0;
}
.retracted-placeholder {
  color: #9ca3af; font-style: italic; font-size: 0.85rem; padding: 6px 12px;
  display: flex; align-items: center; gap: 6px;
}
.bubble-wrap:hover .bubble-actions {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}
.bact-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  padding: 3px 6px;
  border-radius: 4px;
  transition: color 0.15s, background 0.15s;
  line-height: 1;
}
.bact-btn:hover:not(:disabled) { color: var(--color-primary); background: var(--color-surface-raised); }
.bact-btn.dislike:hover:not(:disabled) { color: #e53935; background: #fce4e4; }
.bact-btn.active { color: var(--color-accent); }
.bact-btn.dislike.active { color: #e53935; }
.bact-btn:disabled { cursor: default; opacity: 0.5; }

/* 定时通知气泡底部链接 */
.notif-task-link {
  display: inline-flex; align-items: center; gap: 5px;
  margin-top: var(--space-2); font-size: 0.78rem;
  color: #ff9800; text-decoration: none;
  opacity: 0.85; transition: opacity 0.15s;
  border-top: 1px solid rgba(255,152,0,0.2);
  padding-top: 6px; width: 100%;
}
.notif-task-link:hover { opacity: 1; text-decoration: underline; }

/* ── CoT 思维过程块 ──────────────────────────────────────────*/
.cot-block {
  margin-bottom: 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: #f8f9ff;
}
.cot-summary {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px var(--space-3);
  cursor: pointer;
  font-size: 0.82rem;
  font-weight: 500;
  color: #5569d0;
  background: #eef0ff;
  list-style: none;
  user-select: none;
}
.cot-summary::-webkit-details-marker { display: none; }
.cot-summary::before {
  content: '▶';
  font-size: 0.65rem;
  transition: transform 0.2s;
}
details[open] .cot-summary::before { transform: rotate(90deg); }
.cot-icon { font-size: 0.82rem; }
.cot-spin { font-size: 0.75rem; color: var(--color-primary); }
.cot-len  { margin-left: auto; font-size: 0.72rem; color: var(--color-text-muted); font-weight: 400; }
.cot-content {
  padding: 10px 14px;
  font-size: 0.82rem;
  line-height: 1.6;
  color: var(--color-text-secondary);
  max-height: 320px;
  overflow-y: auto;
}
.cot-content p { margin: 0 0 6px; }

[data-theme="dark"] .cot-block { border-color: #3a3b42; background: #252630; }
[data-theme="dark"] .cot-summary { background: #2a2b38; color: #9ea8f0; }
[data-theme="dark"] .cot-content { color: #8e8f9a; }

@media (max-width: 768px) {
  .bubble-wrap       { max-width: 92% !important; }
  .message-row.user  { justify-content: flex-end; }
  .tool-calls-card   { max-width: 95% !important; }
}
</style>
