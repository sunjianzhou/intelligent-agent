<template>
  <div class="log-view">
    <div class="toolbar">
      <span class="page-desc">操作日志 · 用户与 AI 交互记录</span>
      <div class="toolbar-right">
        <div class="filter-pills">
          <button
            v-for="f in FILTERS"
            :key="f.key"
            class="filter-pill"
            :class="{ active: activeFilter === f.key, [`pill-${f.key}`]: true }"
            @click="activeFilter = f.key"
          >
            <i :class="f.icon" /> {{ f.label }}
          </button>
        </div>
        <button class="refresh-btn" :class="{ spinning: loading }" @click="load">
          <i class="fas fa-sync-alt" /> 刷新
        </button>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="empty-state">
      <i class="fas fa-spinner fa-spin" style="font-size:2rem;color:#667eea" />
      <div>加载中…</div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="filteredLogs.length === 0" class="empty-state">
      <i class="fas fa-clipboard-list" style="font-size:2.5rem;color:#dde;display:block;margin-bottom:10px" />
      <div style="color:#bbb;font-size:0.9rem">暂无日志记录</div>
    </div>

    <!-- 日志时间线 -->
    <div v-else class="timeline">
      <div
        v-for="(log, idx) in filteredLogs"
        :key="log.id"
        class="log-entry"
        :class="`entry-${log.type}`"
      >
        <div class="entry-dot" :class="`dot-${log.type}`">
          <i :class="typeIcon(log.type)" />
        </div>
        <div class="entry-body">
          <div class="entry-header">
            <span class="entry-type-badge" :class="`badge-${log.type}`">{{ typeLabel(log.type) }}</span>
            <span class="entry-source" v-if="log.source">{{ log.source }}</span>
            <span class="entry-time">{{ log.time }}</span>
          </div>
          <div class="entry-content">{{ log.content }}</div>
          <div v-if="log.meta" class="entry-meta">{{ log.meta }}</div>
        </div>
      </div>
    </div>

    <div v-if="!loading && logs.length > 0" class="log-footer">
      共 {{ filteredLogs.length }} 条记录（来自最近 {{ logs.length }} 条原始数据）
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listConversations, getConversation, getTasksList } from '@/services/api'

const FILTERS = [
  { key: 'all',   label: '全部',   icon: 'fas fa-list' },
  { key: 'user',  label: '用户',   icon: 'fas fa-user' },
  { key: 'ai',    label: 'AI',     icon: 'fas fa-robot' },
  { key: 'tool',  label: '工具',   icon: 'fas fa-wrench' },
  { key: 'task',  label: '任务',   icon: 'fas fa-tasks' },
  { key: 'error', label: '错误',   icon: 'fas fa-exclamation-circle' },
]

const loading      = ref(false)
const logs         = ref([])
const activeFilter = ref('all')

const filteredLogs = computed(() =>
  activeFilter.value === 'all'
    ? logs.value
    : logs.value.filter(l => l.type === activeFilter.value)
)

const typeIcon = (type) => ({
  user:  'fas fa-user',
  ai:    'fas fa-robot',
  tool:  'fas fa-wrench',
  task:  'fas fa-tasks',
  error: 'fas fa-exclamation-circle',
}[type] || 'fas fa-circle')

const typeLabel = (type) => ({
  user:  '用户',
  ai:    'AI',
  tool:  '工具',
  task:  '任务',
  error: '错误',
}[type] || type)

const fmtTime = (iso) => {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { return iso }
}

const truncate = (str, len = 300) =>
  str && str.length > len ? str.slice(0, len) + '…' : (str || '')

const load = async () => {
  loading.value = true
  logs.value    = []
  try {
    const [convRes, taskRes] = await Promise.allSettled([
      listConversations(),
      getTasksList(null, 30),
    ])

    const entries = []

    // ── 对话消息 ─────────────────────────────────────────
    if (convRes.status === 'fulfilled' && convRes.value?.conversations) {
      const convs = convRes.value.conversations.slice(0, 5)
      for (const conv of convs) {
        const detail = await getConversation(conv.id).catch(() => null)
        const msgs   = detail?.messages || []
        for (const m of msgs) {
          const isUser = m.role === 'user'
          const hasToolCall = Array.isArray(m.tool_calls) && m.tool_calls.length > 0

          if (hasToolCall) {
            for (const tc of m.tool_calls) {
              entries.push({
                id:      `${conv.id}-tc-${tc.id || Math.random()}`,
                type:    'tool',
                time:    fmtTime(m.created_at || conv.created_at),
                ts:      m.created_at || conv.created_at || '',
                source:  `对话 ${conv.id?.slice(0, 8)}`,
                content: `调用工具：${tc.function?.name || tc.name || '未知工具'}`,
                meta:    tc.function?.arguments ? truncate(tc.function.arguments, 120) : null,
              })
            }
          } else if (isUser) {
            entries.push({
              id:      `${conv.id}-msg-u-${m.id || Math.random()}`,
              type:    'user',
              time:    fmtTime(m.created_at || conv.created_at),
              ts:      m.created_at || conv.created_at || '',
              source:  `对话 ${conv.id?.slice(0, 8)}`,
              content: truncate(typeof m.content === 'string' ? m.content : JSON.stringify(m.content)),
              meta:    null,
            })
          } else if (m.role === 'assistant') {
            const content = typeof m.content === 'string' ? m.content : JSON.stringify(m.content)
            entries.push({
              id:      `${conv.id}-msg-a-${m.id || Math.random()}`,
              type:    content?.startsWith('[ERROR]') ? 'error' : 'ai',
              time:    fmtTime(m.created_at || conv.created_at),
              ts:      m.created_at || conv.created_at || '',
              source:  `对话 ${conv.id?.slice(0, 8)}`,
              content: truncate(content),
              meta:    null,
            })
          }
        }
      }
    }

    // ── 任务记录 ─────────────────────────────────────────
    if (taskRes.status === 'fulfilled' && taskRes.value?.tasks) {
      for (const t of taskRes.value.tasks) {
        const isError = t.status === 'failed' || t.status === 'error'
        entries.push({
          id:      `task-${t.id}`,
          type:    isError ? 'error' : 'task',
          time:    fmtTime(t.created_at || t.scheduled_at),
          ts:      t.created_at || t.scheduled_at || '',
          source:  `任务调度`,
          content: t.name || t.description || t.action || '未命名任务',
          meta:    t.status ? `状态：${t.status}${t.result ? ' · ' + truncate(String(t.result), 80) : ''}` : null,
        })
      }
    }

    // 按时间倒序排列
    entries.sort((a, b) => {
      if (!a.ts && !b.ts) return 0
      if (!a.ts) return 1
      if (!b.ts) return -1
      return b.ts.localeCompare(a.ts)
    })

    logs.value = entries
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.log-view {
  padding: 20px; height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: 14px; background: #f8f9fa;
}
.toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.toolbar-right { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.page-desc { font-size: 0.88rem; color: #888; }

.filter-pills { display: flex; gap: 6px; flex-wrap: wrap; }
.filter-pill {
  padding: 4px 12px; border-radius: 20px; border: 1px solid #e0e3e8;
  background: white; font-size: 0.8rem; cursor: pointer; color: #666;
  display: flex; align-items: center; gap: 5px; transition: all 0.15s;
}
.filter-pill:hover { border-color: #667eea; color: #667eea; }
.filter-pill.active { border-color: transparent; color: white; }
.filter-pill.pill-user.active   { background: #2563eb; }
.filter-pill.pill-ai.active     { background: #16a34a; }
.filter-pill.pill-tool.active   { background: #7c3aed; }
.filter-pill.pill-task.active   { background: #ea580c; }
.filter-pill.pill-error.active  { background: #dc2626; }
.filter-pill.pill-all.active    { background: #667eea; }

.refresh-btn {
  display: flex; align-items: center; gap: 6px; padding: 6px 14px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white; font-size: 0.85rem; color: #555; cursor: pointer;
}
.refresh-btn:hover { border-color: #667eea; color: #667eea; }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.empty-state {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 60px 20px; gap: 10px;
}

.timeline { display: flex; flex-direction: column; gap: 0; }
.log-entry {
  display: flex; gap: 14px; padding: 12px 4px;
  border-bottom: 1px solid #f0f0f0; position: relative;
}
.log-entry:last-child { border-bottom: none; }

.entry-dot {
  width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 0.8rem; color: white; margin-top: 2px;
}
.dot-user  { background: #2563eb; }
.dot-ai    { background: #16a34a; }
.dot-tool  { background: #7c3aed; }
.dot-task  { background: #ea580c; }
.dot-error { background: #dc2626; }

.entry-body { flex: 1; min-width: 0; }
.entry-header {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 4px;
}
.entry-type-badge {
  font-size: 0.72rem; font-weight: 600; padding: 2px 8px; border-radius: 10px;
}
.badge-user  { background: #eff6ff; color: #2563eb; }
.badge-ai    { background: #f0fdf4; color: #16a34a; }
.badge-tool  { background: #faf5ff; color: #7c3aed; }
.badge-task  { background: #fff7ed; color: #ea580c; }
.badge-error { background: #fef2f2; color: #dc2626; }

.entry-source { font-size: 0.75rem; color: #aaa; }
.entry-time   { font-size: 0.75rem; color: #bbb; margin-left: auto; }
.entry-content {
  font-size: 0.88rem; color: #333; line-height: 1.5;
  word-break: break-word; white-space: pre-wrap;
}
.entry-meta { font-size: 0.76rem; color: #999; margin-top: 4px; word-break: break-word; }

.log-footer { font-size: 0.78rem; color: #bbb; text-align: center; padding: 8px 0; }

[data-theme="dark"] .log-view { background: #1e1f24; }
[data-theme="dark"] .filter-pill { background: #2c2d32; border-color: #3a3b42; color: #a0a1ab; }
[data-theme="dark"] .refresh-btn { background: #2c2d32; border-color: #3a3b42; color: #a0a1ab; }
[data-theme="dark"] .log-entry { border-color: #2c2d32; }
[data-theme="dark"] .entry-content { color: #c1c2c5; }
[data-theme="dark"] .entry-source { color: #6c6d77; }
[data-theme="dark"] .entry-time { color: #6c6d77; }
</style>
