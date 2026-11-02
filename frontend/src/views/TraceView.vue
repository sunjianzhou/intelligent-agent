<template>
  <div class="trace-view">
    <div class="toolbar">
      <span class="page-desc">运行追踪 · 每次 Agent 请求的 LLM/工具/记忆耗时与状态</span>
      <div class="toolbar-right">
        <button class="refresh-btn" :class="{ spinning: loading }" @click="load">
          <i class="fas fa-sync-alt" /> 刷新
        </button>
      </div>
    </div>

    <LoadingState v-if="loading" />

    <EmptyState
      v-else-if="traces.length === 0"
      icon="fas fa-route"
      title="暂无运行追踪"
      sub="发起一次聊天后这里会出现记录"
    />

    <div v-else class="trace-list">
      <div
        v-for="trace in traces"
        :key="trace.request_id"
        class="trace-card"
        :class="{ expanded: expanded === trace.request_id, [`status-${trace.status}`]: true }"
        @click="toggle(trace.request_id)"
      >
        <div class="trace-header">
          <span class="status-badge" :class="`badge-${trace.status}`">
            {{ trace.status === 'ok' ? '成功' : '异常' }}
          </span>
          <span class="trace-id">{{ trace.request_id }}</span>
          <span class="trace-model" v-if="trace.model">{{ trace.model }}</span>
          <span class="trace-meta">
            {{ trace.channel || 'web' }} · {{ trace.span_count }} spans · {{ trace.duration_ms }}ms
          </span>
          <span class="trace-time">{{ fmtTime(trace.started_at) }}</span>
        </div>

        <div v-if="expanded === trace.request_id" class="trace-detail">
          <div class="detail-head">
            <span>Spans</span>
            <button class="delete-btn" @click.stop="remove(trace.request_id)">
              <i class="fas fa-trash" /> 删除
            </button>
          </div>
          <div v-if="detailSpans.length === 0" class="no-spans">无 span 数据</div>
          <div v-else class="span-list">
            <div
              v-for="(span, i) in detailSpans"
              :key="i"
              class="span-row"
              :class="`span-${span.status}`"
            >
              <span class="span-icon" :class="`icon-${span.name}`">
                <i :class="spanIcon(span.name)" />
              </span>
              <div class="span-body">
                <div class="span-head">
                  <span class="span-name">{{ spanLabel(span.name) }}</span>
                  <span class="span-status">{{ span.status === 'ok' ? 'ok' : 'error' }}</span>
                  <span class="span-duration">{{ span.duration_ms }}ms</span>
                </div>
                <div v-if="span.details" class="span-details">{{ formatDetails(span.details) }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getTraces, getTrace, deleteTrace } from '@/services/api'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingState from '@/components/common/LoadingState.vue'

const loading   = ref(false)
const traces    = ref([])
const expanded  = ref(null)
const detailSpans = ref([])

const spanIcon = (name) => ({
  llm_call: 'fas fa-microchip',
  tool_call: 'fas fa-wrench',
  rag: 'fas fa-search',
  memory: 'fas fa-brain',
  cache: 'fas fa-bolt',
}[name] || 'fas fa-circle')

const spanLabel = (name) => ({
  llm_call: 'LLM 调用',
  tool_call: '工具调用',
  rag: '记忆检索',
  memory: '记忆写入',
  cache: '缓存命中',
}[name] || name)

const fmtTime = (iso) => {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleString('zh-CN', {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch { return iso }
}

const formatDetails = (details) => {
  try {
    return JSON.stringify(details)
  } catch {
    return String(details || '')
  }
}

const load = async () => {
  loading.value = true
  try {
    const res = await getTraces(50)
    traces.value = res?.traces || []
    expanded.value = null
    detailSpans.value = []
  } finally {
    loading.value = false
  }
}

const toggle = async (requestId) => {
  if (expanded.value === requestId) {
    expanded.value = null
    detailSpans.value = []
    return
  }
  const detail = await getTrace(requestId).catch(() => null)
  if (!detail) return
  expanded.value = requestId
  detailSpans.value = detail.spans || []
}

const remove = async (requestId) => {
  await deleteTrace(requestId)
  await load()
}

onMounted(load)
</script>

<style scoped>
.trace-view {
  padding: var(--space-4-5); height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: var(--space-3-5); background: var(--color-surface-sunken);
}
.toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--space-2); }
.toolbar-right { display: flex; align-items: center; gap: var(--space-2-5); }
.page-desc { font-size: var(--text-sm); color: var(--color-text-secondary); }
.refresh-btn {
  display: flex; align-items: center; gap: var(--space-1-5); padding: var(--space-1-5) var(--space-3-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface); font-size: var(--text-sm); color: var(--color-text); cursor: pointer;
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.trace-list { display: flex; flex-direction: column; gap: var(--space-2-5); }
.trace-card {
  background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  padding: var(--space-3) var(--space-4); cursor: pointer; transition: border-color var(--duration-fast);

  box-shadow: var(--shadow-card);}
.trace-card:hover { border-color: var(--color-primary); }
.trace-card.status-error { border-left: 3px solid var(--color-danger); }
.trace-card.status-ok { border-left: 3px solid var(--color-success); }
.trace-header {
  display: flex; align-items: center; gap: var(--space-2-5); flex-wrap: wrap;
}
.status-badge { font-size: var(--text-xs); font-weight: var(--weight-semibold); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-sm); }
.badge-ok { background: var(--color-success-soft); color: var(--color-success); }
.badge-error { background: var(--color-danger-soft); color: var(--color-danger); }
.trace-id { font-family: monospace; font-size: var(--text-sm-2); color: var(--color-text); }
.trace-model { font-size: var(--text-xs); color: var(--color-primary); background: var(--color-violet-soft); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-sm); }
.trace-meta { font-size: var(--text-xs); color: var(--color-text-muted); }
.trace-time { font-size: var(--text-xs); color: var(--color-text-muted); margin-left: auto; }

.trace-detail { margin-top: var(--space-3); border-top: 1px dashed var(--color-border); padding-top: var(--space-2-5); }
.detail-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: var(--text-sm-2); font-weight: var(--weight-semibold); color: var(--color-text-secondary); margin-bottom: var(--space-2);
}
.delete-btn {
  display: flex; align-items: center; gap: var(--space-1); padding: var(--space-1) var(--space-2-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-danger-border); background: var(--color-surface); color: var(--color-danger); font-size: var(--text-xs); cursor: pointer;
}
.delete-btn:hover { background: var(--color-danger-soft); }
.no-spans { font-size: var(--text-sm-2); color: var(--color-text-muted); padding: var(--space-2) 0; }
.span-list { display: flex; flex-direction: column; gap: var(--space-1-5); }
.span-row {
  display: flex; gap: var(--space-2-5); padding: var(--space-2) var(--space-2-5); border-radius: var(--radius-sm); background: var(--color-surface-sunken);
  border: 1px solid var(--color-border);
}
.span-row.span-error { background: var(--color-danger-soft); border-color: var(--color-danger-border); }
.span-icon {
  width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; font-size: var(--text-xs); color: white;
}
.icon-llm_call { background: var(--color-primary); }
.icon-tool_call { background: var(--color-warn); }
.icon-rag { background: var(--color-primary); }
.icon-memory { background: var(--color-success); }
.icon-cache { background: var(--color-warn); }
.span-body { flex: 1; min-width: 0; }
.span-head { display: flex; align-items: center; gap: var(--space-2); }
.span-name { font-size: var(--text-sm-2); font-weight: var(--weight-semibold); color: var(--color-text); }
.span-status { font-size: var(--text-2xs); color: var(--color-success); }
.span-row.span-error .span-status { color: var(--color-danger); }
.span-duration { font-size: var(--text-xs); color: var(--color-text-muted); margin-left: auto; }
.span-details {
  font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-1);
  word-break: break-all; font-family: monospace; white-space: pre-wrap;
}
</style>
