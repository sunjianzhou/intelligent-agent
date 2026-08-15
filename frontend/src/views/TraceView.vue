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

    <div v-if="loading" class="empty-state">
      <i class="fas fa-spinner fa-spin" style="font-size:2rem;color:#667eea" />
      <div>加载中…</div>
    </div>

    <div v-else-if="traces.length === 0" class="empty-state">
      <i class="fas fa-route" style="font-size:2.5rem;color:#dde;display:block;margin-bottom:10px" />
      <div style="color:#bbb;font-size:0.9rem">暂无运行追踪（发起一次聊天后这里会出现记录）</div>
    </div>

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
  padding: 20px; height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: 14px; background: #f8f9fa;
}
.toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.toolbar-right { display: flex; align-items: center; gap: 10px; }
.page-desc { font-size: 0.88rem; color: #888; }
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
.trace-list { display: flex; flex-direction: column; gap: 10px; }
.trace-card {
  background: white; border: 1px solid #e0e3e8; border-radius: 10px;
  padding: 12px 16px; cursor: pointer; transition: border-color 0.15s;
}
.trace-card:hover { border-color: #667eea; }
.trace-card.status-error { border-left: 3px solid #dc2626; }
.trace-card.status-ok { border-left: 3px solid #16a34a; }
.trace-header {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
}
.status-badge { font-size: 0.72rem; font-weight: 600; padding: 2px 8px; border-radius: 10px; }
.badge-ok { background: #f0fdf4; color: #16a34a; }
.badge-error { background: #fef2f2; color: #dc2626; }
.trace-id { font-family: monospace; font-size: 0.82rem; color: #444; }
.trace-model { font-size: 0.75rem; color: #7c3aed; background: #faf5ff; padding: 2px 8px; border-radius: 10px; }
.trace-meta { font-size: 0.78rem; color: #999; }
.trace-time { font-size: 0.75rem; color: #bbb; margin-left: auto; }

.trace-detail { margin-top: 12px; border-top: 1px dashed #e5e7eb; padding-top: 10px; }
.detail-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 0.8rem; font-weight: 600; color: #666; margin-bottom: 8px;
}
.delete-btn {
  display: flex; align-items: center; gap: 4px; padding: 4px 10px; border-radius: 6px;
  border: 1px solid #fecaca; background: #fff; color: #dc2626; font-size: 0.75rem; cursor: pointer;
}
.delete-btn:hover { background: #fef2f2; }
.no-spans { font-size: 0.8rem; color: #aaa; padding: 8px 0; }
.span-list { display: flex; flex-direction: column; gap: 6px; }
.span-row {
  display: flex; gap: 10px; padding: 8px 10px; border-radius: 8px; background: #fafafa;
  border: 1px solid #f0f0f0;
}
.span-row.span-error { background: #fef2f2; border-color: #fecaca; }
.span-icon {
  width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; font-size: 0.75rem; color: white;
}
.icon-llm_call { background: #7c3aed; }
.icon-tool_call { background: #ea580c; }
.icon-rag { background: #2563eb; }
.icon-memory { background: #16a34a; }
.icon-cache { background: #ca8a04; }
.span-body { flex: 1; min-width: 0; }
.span-head { display: flex; align-items: center; gap: 8px; }
.span-name { font-size: 0.82rem; font-weight: 600; color: #333; }
.span-status { font-size: 0.7rem; color: #16a34a; }
.span-row.span-error .span-status { color: #dc2626; }
.span-duration { font-size: 0.72rem; color: #999; margin-left: auto; }
.span-details {
  font-size: 0.72rem; color: #888; margin-top: 4px;
  word-break: break-all; font-family: monospace; white-space: pre-wrap;
}

[data-theme="dark"] .trace-view { background: #1e1f24; }
[data-theme="dark"] .refresh-btn { background: #2c2d32; border-color: #3a3b42; color: #a0a1ab; }
[data-theme="dark"] .trace-card { background: #26272c; border-color: #34353c; }
[data-theme="dark"] .trace-id { color: #c1c2c5; }
[data-theme="dark"] .span-row { background: #2c2d32; border-color: #3a3b42; }
[data-theme="dark"] .span-name { color: #d1d2d5; }
[data-theme="dark"] .span-details { color: #8c8d96; }
[data-theme="dark"] .detail-head { color: #a0a1ab; }
[data-theme="dark"] .delete-btn { background: #2c2d32; border-color: #5c3030; }
</style>
