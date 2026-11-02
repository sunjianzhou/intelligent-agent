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

    <!-- 执行计划卡片（G6 planning 前置） -->
    <template v-else-if="!msg.isRetracted && msg.role === 'plan'">
      <div class="plan-card">
        <div class="plan-title">
          <i class="fas fa-list-check" /> 执行计划
        </div>
        <ol class="plan-steps">
          <li v-for="(step, i) in msg.steps" :key="i" class="plan-step">
            <span class="plan-step-title">{{ step.title }}</span>
            <span v-if="step.detail" class="plan-step-detail">{{ step.detail }}</span>
          </li>
        </ol>
      </div>
    </template>

    <!-- 审批卡片（G6 HITL） -->
    <template v-else-if="!msg.isRetracted && msg.role === 'approval'">
      <div class="approval-card" :class="msg.status">
        <div class="approval-title">
          <i class="fas fa-user-shield" /> 需要你的确认
        </div>
        <div class="approval-tool">工具：<code>{{ msg.approval.tool }}</code></div>
        <pre v-if="approvalArgsText(msg)" class="approval-args">{{ approvalArgsText(msg) }}</pre>
        <div v-if="msg.status === 'pending'" class="approval-actions">
          <button class="approve-btn" @click="decideApprovalMsg(msg, true)">
            <i class="fas fa-check" /> 批准
          </button>
          <button class="deny-btn" @click="decideApprovalMsg(msg, false)">
            <i class="fas fa-times" /> 拒绝
          </button>
        </div>
        <div v-else class="approval-result" :class="msg.status">
          {{ msg.status === 'approved' ? '已批准，继续执行' : '已拒绝，取消执行' }}
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
          <!-- R-05 知识问答引用列表（可点击跳转知识库） -->
          <div v-if="msg.role === 'assistant' && msg.citations && msg.citations.length"
               class="citations">
            <div class="citations-title">
              <i class="fas fa-book-open" /> 引用来源
            </div>
            <router-link
              v-for="(c, i) in msg.citations"
              :key="i"
              :to="{ path: '/knowledge', query: { file: c.file_id } }"
              class="citation-item"
              :title="c.label"
            >
              <i class="fas fa-file-alt" /> {{ c.label }}
            </router-link>
          </div>
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
import { decideApproval as apiDecideApproval } from '@/services/api'
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

const approvalArgsText = (msg) => {
  const args = msg.approval?.args
  if (!args || !Object.keys(args).length) return ''
  try { return JSON.stringify(args, null, 2) } catch { return String(args) }
}

const decideApprovalMsg = async (msg, approved) => {
  try {
    await apiDecideApproval(msg.approval.approval_id, approved)
    msg.status = approved ? 'approved' : 'denied'
  } catch {
    ElMessage({ message: '审批提交失败，请重试', type: 'error', duration: 2500 })
  }
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
.bubble-wrap.search-match .bubble { outline: 2px solid var(--color-highlight-border); }
.bubble-wrap.search-current .bubble { outline: 2px solid var(--color-warn); }
:deep(.search-hl) { background: var(--color-highlight-bg); color: var(--color-text); border-radius: var(--radius-xs); padding: 0 var(--space-px); }

/* ── 工具调用卡片 ────────────────────────────────────────── */
.tool-calls-card {
  max-width: 80%;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-md);
  padding: var(--space-2-5) var(--space-3-5);
  font-size: var(--text-sm);
  margin: 0 auto;
}
.tool-calls-title {
  font-weight: var(--weight-medium);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-2);
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
}
.tool-calls-title i { color: var(--color-primary); }
.task-view-link {
  margin-left: auto;
  font-size: var(--text-sm-2);
  color: var(--color-primary);
  text-decoration: none;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-0-5) var(--space-2);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-md);
  white-space: nowrap;
}
.task-view-link:hover { background: var(--color-primary-soft); }
.tool-call-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-1-5) 0;
  border-top: 1px solid var(--color-border);
}
.tool-row-top {
  display: flex;
  align-items: center;
  gap: var(--space-2-5);
}
.tool-args {
  font-size: var(--text-xs); color: var(--color-text-muted);
  font-family: monospace;
  word-break: break-all;
  padding-left: var(--space-1);
}
.tool-name {
  font-weight: var(--weight-medium);
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: var(--space-1);
  min-width: 120px;
}
.tool-name i { color: var(--color-primary); font-size: var(--text-sm-2); }
.tool-status { font-size: var(--text-sm-2); }
.tool-call-item.success .tool-status { color: var(--color-success); }
.tool-call-item.fail    .tool-status { color: var(--color-danger); }
.tool-call-item.running .tool-status { color: var(--color-primary); }
.tool-result {
    width: 100%;
    font-size: var(--text-sm-2);
    color: var(--color-text-secondary);
    background: var(--color-surface);
    border-radius: var(--radius-xs);
    padding: var(--space-1) var(--space-2);
  margin-top: var(--space-1);
  word-break: break-all;
}

/* ── 执行计划卡片 ────────────────────────────────────────── */
.plan-card {
  max-width: 80%;
  background: var(--color-success-soft);
  border: 1px solid var(--color-success-border);
  border-radius: var(--radius-md);
  padding: var(--space-2-5) var(--space-3-5);
  font-size: var(--text-sm);
  margin: 0 auto;
}
.plan-title {
  font-weight: var(--weight-medium);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-2);
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
}
.plan-title i { color: var(--color-success); }
.plan-steps {
  margin: 0;
  padding-left: var(--space-4-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.plan-step-title { color: var(--color-text-secondary); font-weight: var(--weight-medium); }
.plan-step-detail {
  color: var(--color-text-muted);
  font-size: var(--text-sm-2);
  margin-left: var(--space-1-5);
}

/* ── 审批卡片（G6 HITL） ─────────────────────────────────── */
.approval-card {
  max-width: 80%;
  background: var(--color-warn-soft);
  border: 1px solid var(--color-warn-border);
  border-radius: var(--radius-md);
  padding: var(--space-2-5) var(--space-3-5);
  font-size: var(--text-sm);
  margin: 0 auto;
}
.approval-card.approved { background: var(--color-success-soft); border-color: var(--color-success-border); }
.approval-card.denied  { background: var(--color-danger-soft); border-color: var(--color-danger-border); }
.approval-title {
  font-weight: var(--weight-medium);
  color: var(--color-warn);
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  margin-bottom: var(--space-1-5);
}
.approval-title i { color: var(--color-warn); }
.approval-tool { color: var(--color-text-secondary); margin-bottom: var(--space-1); }
  .approval-args {
    font-size: var(--text-xs);
    color: var(--color-text-secondary);
  background: var(--color-surface);
  border-radius: var(--radius-xs);
  padding: var(--space-1-5) var(--space-2);
  word-break: break-all;
}
.approval-actions { display: flex; gap: var(--space-2); margin-top: var(--space-2); }
.approve-btn, .deny-btn {
  border: none;
  border-radius: var(--radius-md);
  padding: var(--space-1) var(--space-3-5);
  font-size: var(--text-sm-2);
  cursor: pointer;
  color: var(--color-text-on-primary);
}
.approve-btn { background: var(--color-success); }
.deny-btn    { background: var(--color-danger); }
.approve-btn:hover, .deny-btn:hover { opacity: 0.85; }
.approval-result { margin-top: var(--space-1-5); font-size: var(--text-sm-2); color: var(--color-text-secondary); }

/* ── 光标动画 ─────────────────────────────────────────────── */
.cursor {
  display: inline-block;
  animation: blink 0.8s step-end infinite;
  color: var(--color-primary);
  font-size: var(--text-md);
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
  color: var(--color-text-on-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-sm);
  flex-shrink: 0;
}
.user-avatar               { background: var(--color-primary); color: var(--color-text-on-primary); }
.message-row.system .avatar { display: none; }

/* ── 气泡容器 ─────────────────────────────────────────────── */
.bubble-wrap { max-width: min(68%, var(--measure-prose)); display: flex; flex-direction: column; gap: var(--space-1); position: relative; }
.message-row.user   .bubble-wrap { align-items: flex-end; }
.message-row.system .bubble-wrap { max-width: min(90%, var(--measure-prose)); align-items: center; }

/* ── 气泡 ─────────────────────────────────────────────────── */
.bubble {
  padding: var(--space-2-5) var(--space-3-5);
  border-radius: var(--radius-lg);
  font-size: var(--text-base);
  line-height: var(--leading-relaxed);
  word-break: break-word;
}
.bubble.user {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border-bottom-right-radius: 4px;
}
.bubble.assistant {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  line-height: var(--leading-prose);
  border-bottom-left-radius: 4px;
  box-shadow: var(--shadow-card);
}
.bubble.system {
  background: var(--color-warn-soft);
  color: var(--color-warn);
  font-size: var(--text-sm);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-highlight-border);
  padding: var(--space-1-5) var(--space-3);
}
/* 定时任务通知系统消息 */
.bubble.system:has(.md-content),
.bubble.system.notif {
  background: linear-gradient(135deg, var(--color-warn-soft), var(--color-warn-soft));
  border-color: var(--color-warn);
  border-left: 3px solid var(--color-warn);
  box-shadow: var(--shadow-glow-warn);
  font-size: var(--text-sm);
  padding: var(--space-2-5) var(--space-3-5);
}

/* ── Markdown 内容 ────────────────────────────────────────── */
.md-content :deep(p)            { margin: 0 0 var(--space-2); }
.md-content :deep(p:last-child) { margin-bottom: 0; }
.md-content :deep(pre)          { background: var(--color-surface-sunken); border-radius: var(--radius-sm); padding: var(--space-3); overflow-x: auto; margin: var(--space-2) 0; }
.md-content :deep(code)         { font-family: 'Fira Code', Consolas, monospace; font-size: 0.88em; }
.md-content :deep(p > code)     { background: var(--color-surface-sunken); padding: var(--space-0-5) var(--space-1); border-radius: var(--radius-xs); }
.md-content :deep(ul),
.md-content :deep(ol)           { padding-left: var(--space-4-5); margin: var(--space-1-5) 0; }
.md-content :deep(li)           { margin-bottom: var(--space-0-5); }
.md-content :deep(blockquote)   { border-left: 3px solid var(--color-primary); margin: var(--space-2) 0; padding: var(--space-1) var(--space-3); color: var(--color-text-secondary); background: var(--color-primary-soft); border-radius: 0 var(--radius-sm) var(--radius-sm) 0; }
.md-content :deep(table)        { border-collapse: collapse; width: 100%; margin: var(--space-2) 0; font-size: 0.9em; }
.md-content :deep(th),
.md-content :deep(td)           { border: 1px solid var(--color-text-muted); padding: var(--space-1-5) var(--space-2-5); }
.md-content :deep(th)           { background: var(--color-surface-sunken); font-weight: var(--weight-medium); }
.md-content :deep(a)            { color: var(--color-primary); }
.md-content :deep(h1),
.md-content :deep(h2),
.md-content :deep(h3)           { margin: var(--space-2-5) 0 var(--space-1-5); font-weight: var(--weight-medium); }

/* R-05 引用来源列表 */
.citations {
  margin-top: var(--space-2);
  border-top: 1px dashed var(--color-border);
  padding-top: var(--space-2);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.citations-title {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}
.citations-title i { color: var(--color-primary); }
.citation-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--text-xs);
  color: var(--color-primary);
  text-decoration: none;
  background: var(--color-primary-soft);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2);
  width: fit-content;
  transition: background var(--duration-fast);
}
.citation-item:hover { background: var(--color-primary-soft); }

/* ── 时间 / 响应时间 ──────────────────────────────────────── */
.meta          { display: flex; align-items: center; gap: var(--space-2); }
.time          { font-size: var(--text-xs); color: var(--color-text-muted); }
.response-time { font-size: var(--text-xs); color: var(--color-text-muted); }

/* 气泡内图片缩略图 */
.msg-img-thumb {
  display: block;
  max-height: 200px;
  max-width: 100%;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border-on-dark);
  margin-bottom: var(--space-1-5);
  object-fit: contain;
}

/* ── 气泡悬停操作栏（复制/点赞/踩）── */
.bubble-actions {
  display: flex;
  align-items: center;
  gap: var(--space-0-5);
  opacity: 0;
  transform: translateY(-3px);
  transition: opacity var(--duration-fast), transform var(--duration-fast);
  pointer-events: none;
}
.message-row.retract-mode { cursor: pointer; }
.retract-checkbox {
  display: flex; align-items: center; padding: 0 var(--space-1-5); color: var(--color-primary, var(--color-primary));
  font-size: var(--text-md); flex-shrink: 0;
}
.retracted-placeholder {
  color: var(--color-text-secondary); font-style: italic; font-size: var(--text-sm); padding: var(--space-1-5) var(--space-3);
  display: flex; align-items: center; gap: var(--space-1-5);
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
  font-size: var(--text-sm-2);
  padding: var(--space-1) var(--space-1-5);
  border-radius: var(--radius-xs);
  transition: color var(--duration-fast), background var(--duration-fast);
  line-height: var(--leading-none);
}
.bact-btn:hover:not(:disabled) { color: var(--color-primary); background: var(--color-surface-raised); }
.bact-btn.dislike:hover:not(:disabled) { color: var(--color-danger); background: var(--color-danger-soft); }
.bact-btn.active { color: var(--color-accent); }
.bact-btn.dislike.active { color: var(--color-danger); }
.bact-btn:disabled { cursor: default; opacity: 0.5; }

/* 定时通知气泡底部链接 */
.notif-task-link {
  display: inline-flex; align-items: center; gap: var(--space-1);
  margin-top: var(--space-2); font-size: var(--text-xs);
  color: var(--color-warn); text-decoration: none;
  opacity: 0.85; transition: opacity var(--duration-fast);
  border-top: 1px solid var(--color-warn-border);
  padding-top: var(--space-1-5); width: 100%;
}
.notif-task-link:hover { opacity: 1; text-decoration: underline; }

/* ── CoT 思维过程块 ──────────────────────────────────────────*/
.cot-block {
  margin-bottom: var(--space-2-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-primary-soft);
}
.cot-summary {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3);
  cursor: pointer;
  font-size: var(--text-sm-2);
  font-weight: var(--weight-medium);
  color: var(--color-primary);
  background: var(--color-primary-soft);
  list-style: none;
  user-select: none;
}
.cot-summary::-webkit-details-marker { display: none; }
.cot-summary::before {
  content: '▶';
  font-size: var(--text-2xs);
  transition: transform var(--duration-base);
}
details[open] .cot-summary::before { transform: rotate(90deg); }
.cot-icon { font-size: var(--text-sm-2); }
.cot-spin { font-size: var(--text-xs); color: var(--color-primary); }
.cot-len  { margin-left: auto; font-size: var(--text-xs); color: var(--color-text-muted); font-weight: var(--weight-normal); }
.cot-content {
  padding: var(--space-2-5) var(--space-3-5);
  font-size: var(--text-sm-2);
  line-height: var(--leading-relaxed);
  color: var(--color-text-secondary);
  max-height: 320px;
  overflow-y: auto;
}
.cot-content p { margin: 0 0 var(--space-1-5); }

@media (max-width: 768px) {
  .bubble-wrap       { max-width: 92% !important; }
  .message-row.user  { justify-content: flex-end; }
  .tool-calls-card   { max-width: 95% !important; }
}
</style>
