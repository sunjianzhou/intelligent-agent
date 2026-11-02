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
    <LoadingState v-if="loading" />

    <!-- 空状态 -->
    <EmptyState
      v-else-if="filteredLogs.length === 0"
      icon="fas fa-clipboard-list"
      title="暂无日志记录"
    />

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
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingState from '@/components/common/LoadingState.vue'

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
  padding: var(--space-4-5); height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: var(--space-3-5); background: var(--color-surface-sunken);
}
.toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--space-2); }
.toolbar-right { display: flex; align-items: center; gap: var(--space-2-5); flex-wrap: wrap; }
.page-desc { font-size: var(--text-sm); color: var(--color-text-secondary); }

.filter-pills { display: flex; gap: var(--space-1-5); flex-wrap: wrap; }
.filter-pill {
  padding: var(--space-1) var(--space-3); border-radius: var(--radius-lg); border: 1px solid var(--color-border);
  background: var(--color-surface); font-size: var(--text-sm-2); cursor: pointer; color: var(--color-text-secondary);
  display: flex; align-items: center; gap: var(--space-1); transition: var(--transition-interactive);
}
.filter-pill:hover { border-color: var(--color-primary); color: var(--color-primary); }
.filter-pill.active { border-color: transparent; color: white; }
.filter-pill.pill-user.active   { background: var(--color-primary); }
.filter-pill.pill-ai.active     { background: var(--color-success); }
.filter-pill.pill-tool.active   { background: var(--color-primary); }
.filter-pill.pill-task.active   { background: var(--color-warn); }
.filter-pill.pill-error.active  { background: var(--color-danger); }
.filter-pill.pill-all.active    { background: var(--color-primary); }

.refresh-btn {
  display: flex; align-items: center; gap: var(--space-1-5); padding: var(--space-1-5) var(--space-3-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface); font-size: var(--text-sm); color: var(--color-text); cursor: pointer;
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }


.timeline { display: flex; flex-direction: column; gap: 0; }
.log-entry {
  display: flex; gap: var(--space-3-5); padding: var(--space-3) var(--space-1);
  border-bottom: 1px solid var(--color-border); position: relative;
}
.log-entry:last-child { border-bottom: none; }

.entry-dot {
  width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-sm-2); color: white; margin-top: var(--space-0-5);
}
.dot-user  { background: var(--color-primary); }
.dot-ai    { background: var(--color-success); }
.dot-tool  { background: var(--color-primary); }
.dot-task  { background: var(--color-warn); }
.dot-error { background: var(--color-danger); }

.entry-body { flex: 1; min-width: 0; }
.entry-header {
  display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; margin-bottom: var(--space-1);
}
.entry-type-badge {
  font-size: var(--text-xs); font-weight: var(--weight-semibold); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-sm);
}
.badge-user  { background: var(--color-surface-raised); color: var(--color-primary); }
.badge-ai    { background: var(--color-success-soft); color: var(--color-success); }
.badge-tool  { background: var(--color-violet-soft); color: var(--color-primary); }
.badge-task  { background: var(--color-warn-soft); color: var(--color-warn); }
.badge-error { background: var(--color-danger-soft); color: var(--color-danger); }

.entry-source { font-size: var(--text-xs); color: var(--color-text-muted); }
.entry-time   { font-size: var(--text-xs); color: var(--color-text-muted); margin-left: auto; }
.entry-content {
  font-size: var(--text-sm); color: var(--color-text); line-height: var(--leading-normal);
  word-break: break-word; white-space: pre-wrap;
}
.entry-meta { font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-1); word-break: break-word; }

.log-footer { font-size: var(--text-xs); color: var(--color-text-muted); text-align: center; padding: var(--space-2) 0; }
</style>
