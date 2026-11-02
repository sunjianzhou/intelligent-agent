<template>
  <div class="system-view">
    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="page-desc">系统运行状态实时监控</span>
      <div class="toolbar-right">
        <span class="countdown-badge">{{ countdown }}s 后刷新</span>
        <button class="refresh-btn" :class="{ spinning: loading }" @click="refresh">
          <i class="fas fa-sync-alt" /> 刷新
        </button>
      </div>
    </div>

    <!-- 服务状态 -->
    <div class="card-row">
      <div class="status-card">
        <div class="card-label">Client 客户端</div>
        <div class="status-badge ok"><i class="fas fa-check-circle" /> 已连接</div>
        <div class="card-sub">Vue 3 · 当前浏览器</div>
      </div>
      <div class="status-card">
        <div class="card-label">前端服务</div>
        <div class="status-badge ok"><i class="fas fa-check-circle" /> 运行中</div>
        <div class="card-sub">Vite Dev · localhost:3000</div>
      </div>
      <div class="status-card">
        <div class="card-label">Java 后端</div>
        <div class="status-badge" :class="javaOk === null ? 'checking' : javaOk === 'timeout' ? 'timeout' : javaOk ? 'ok' : 'err'">
          <i :class="javaOk === null ? 'fas fa-spinner fa-spin' : javaOk === 'timeout' ? 'fas fa-clock' : javaOk ? 'fas fa-check-circle' : 'fas fa-times-circle'" />
          {{ javaOk === null ? '检测中' : javaOk === 'timeout' ? '检测超时' : javaOk ? '运行中' : '未连接' }}
          <button v-if="javaOk === 'timeout'" class="retry-btn" @click="refresh"><i class="fas fa-redo" /> 重试</button>
        </div>
        <div class="card-sub">Spring Boot · localhost:8080</div>
      </div>

      <!-- 云端模型 or Ollama -->
      <div class="status-card" :class="{ 'cloud-card': cloudMode }">
        <div class="card-label">
          <i v-if="cloudMode" class="fas fa-cloud" style="color:var(--color-primary);margin-right:var(--space-1)" />
          {{ cloudMode ? '云端模型' : 'Ollama' }}
        </div>
        <div class="status-badge ok">
          <i class="fas fa-check-circle" />
          {{ cloudMode ? '已连接' : (ollamaOk ? '已连接' : '未连接') }}
        </div>
        <div class="card-sub" v-if="cloudMode" style="word-break:break-all">
          {{ cloudModel }}
        </div>
        <div class="card-sub" v-else>
          {{ currentModel || '无模型' }}
        </div>
        <div class="cloud-url" v-if="cloudMode">{{ cloudBaseUrl }}</div>
      </div>
    </div>

    <!-- 图表行 -->
    <div class="chart-row">
      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-microchip" /> CPU</span>
          <span class="chart-val" :class="colorClass(resources.cpu_percent)">
            {{ resources.cpu_percent ?? '-' }}%
          </span>
        </div>
        <SparkLine :data="history.cpu" :color="lineColor(resources.cpu_percent)" />
        <div class="chart-detail">
          <span>{{ resources.cpu_count ?? '-' }} 核心</span>
          <span>占用约 {{ resources.cpu_used_cores ?? '-' }} 核</span>
        </div>
        <div class="core-grid" v-if="resources.cpu_per_core?.length">
          <div v-for="(v, i) in resources.cpu_per_core" :key="i"
               class="core-bar-wrap" :title="`核心${i+1}: ${v}%`">
            <div class="core-bar" :style="{ height: v + '%' }" :class="colorClass(v)" />
          </div>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-memory" /> 系统内存</span>
          <span class="chart-val" :class="colorClass(resources.memory_percent)">
            {{ resources.memory_percent ?? '-' }}%
          </span>
        </div>
        <SparkLine :data="history.memory" :color="lineColor(resources.memory_percent)" />
        <div class="chart-detail">
          <span>已用 {{ resources.memory_used_gb ?? '-' }} GB</span>
          <span>共 {{ resources.memory_total_gb ?? '-' }} GB</span>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-tv" /> GPU</span>
          <span class="chart-val" :class="colorClass(resources.gpu?.util_percent)">
            {{ resources.gpu?.util_percent ?? '-' }}%
          </span>
        </div>
        <SparkLine :data="history.gpu" :color="lineColor(resources.gpu?.util_percent)" />
        <template v-if="resources.gpu">
          <div class="chart-detail">
            <span>{{ resources.gpu.temperature }}°C</span>
            <span>显存 {{ resources.gpu.mem_used_mb }}/{{ resources.gpu.mem_total_mb }} MB</span>
          </div>
          <div class="mini-progress-wrap">
            <div class="mini-progress-bar gpu-bar"
                 :style="{ width: resources.gpu.mem_percent + '%' }" />
          </div>
          <div class="gpu-name">{{ resources.gpu.name }}</div>
        </template>
        <div v-else-if="resources.gpu === null || resources.gpu === undefined" class="gpu-empty">
          <i class="fas fa-info-circle" style="font-size:var(--text-md);color:var(--color-text-muted);display:block;margin-bottom:var(--space-1)" />
          未检测到独立 GPU
          <div class="gpu-empty-sub">集成显卡或无显卡环境，LLM 使用 CPU 推理</div>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-bolt" /> 响应时间</span>
          <span class="chart-val" :class="rtColorClass(lastRt)">
            {{ lastRt != null ? lastRt.toFixed(1) + 's' : '-' }}
          </span>
        </div>
        <SparkLine :data="rtHistory" :color="rtColor" />
        <div class="chart-detail">
          <span>最近 {{ responseTimes.length }} 次</span>
          <span v-if="avgRt">均值 {{ avgRt }}s</span>
        </div>
      </div>
    </div>

    <!-- 详情行 -->
    <div class="detail-row">
      <!-- 内存分布（Agent 服务 + 其他进程对齐系统用量） -->
      <div class="detail-card">
        <div class="detail-title"><i class="fas fa-layer-group" /> 内存分布</div>
        <div v-if="Object.keys(processes).length === 0" class="empty-tip">暂无进程数据</div>
        <div v-else class="process-list">
          <div v-for="(info, name) in processesSorted" :key="name"
               class="process-item" :class="{ 'process-other': name === '其他进程' }">
            <div class="process-header">
              <span class="process-name">
                <i :class="processIcon(name)" />
                {{ name }}
                <span v-if="name === '其他进程'" class="other-tip">OS + 其他应用</span>
              </span>
              <div style="display:flex;align-items:center;gap:var(--space-2)">
                <span class="process-mem">{{ info.mem_mb }} MB</span>
                <button v-if="name === '其他进程' && topOthers.length"
                        class="expand-btn" @click="showOthers = !showOthers">
                  {{ showOthers ? '收起' : '展开详情' }}
                </button>
              </div>
            </div>
            <div class="process-bar-wrap">
              <div class="process-bar"
                   :style="{ width: processBarWidth(info.mem_mb) + '%' }"
                   :class="processBarColor(info.mem_mb, name)" />
            </div>
            <!-- 展开：Top-10 其他进程 -->
            <div v-if="name === '其他进程' && showOthers" class="other-detail">
              <div v-for="p in topOthers" :key="p.name" class="other-row">
                <span class="other-name">{{ p.name }}{{ p.count > 1 ? ` ×${p.count}` : '' }}</span>
                <span class="other-bar-wrap">
                  <span class="other-bar" :style="{ width: processBarWidth(p.mem_mb) + '%' }" />
                </span>
                <span class="other-mem">{{ p.mem_mb }} MB</span>
              </div>
              <div class="other-row other-row-hint">
                <span class="other-name" style="color:var(--color-text-muted)">（其余小进程未列出）</span>
                <span></span><span></span>
              </div>
            </div>
          </div>
          <div class="process-total">
            <span>Agent 服务合计</span>
            <span class="total-val">{{ agentTotalMb }} MB</span>
          </div>
          <div class="process-total mem-breakdown-hint">
            <span>系统已用合计</span>
            <span class="total-val">{{ Math.round((resources.memory_used_gb || 0) * 1024) }} MB</span>
          </div>
        </div>
      </div>

      <!-- Ollama 已加载模型 + 磁盘 -->
      <div class="detail-card">
        <!-- 云端模型信息 or Ollama 模型 -->
        <div v-if="cloudMode" class="cloud-info-box">
          <div class="detail-title"><i class="fas fa-cloud" /> 云端模型信息</div>
          <div class="cloud-info-item">
            <span class="ci-label">模型名称</span>
            <span class="ci-val model-name-highlight">{{ cloudModel }}</span>
          </div>
          <div class="cloud-info-item">
            <span class="ci-label">接入地址</span>
            <span class="ci-val">{{ cloudBaseUrl }}</span>
          </div>
          <div class="cloud-info-item">
            <span class="ci-label">状态</span>
            <span class="status-badge ok" style="font-size:var(--text-xs);padding:var(--space-0-5) var(--space-2-5)">
              <i class="fas fa-check-circle" /> 已连接
            </span>
          </div>
        </div>
        <div v-else>
          <div class="detail-title"><i class="fas fa-brain" /> Ollama 已加载模型</div>
          <div v-if="ollamaModels.length === 0" class="empty-tip">当前无模型加载在显存中</div>
          <div v-else class="ollama-model-list">
            <div v-for="m in ollamaModels" :key="m.name" class="ollama-model-item">
              <div class="ollama-model-row">
                <span class="ollama-model-name">{{ m.name }}</span>
                <span class="ollama-model-size">{{ m.size_gb }} GB</span>
              </div>
              <div class="ollama-model-meta">
                <span class="vram-badge">显存 {{ m.vram_gb }} GB</span>
                <span class="expires-badge" v-if="m.expires_at">
                  {{ formatExpires(m.expires_at) }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="divider" />

        <div class="detail-title" style="margin-top:0">
          <i class="fas fa-hdd" /> 磁盘使用
        </div>
        <div v-if="disks.length === 0" class="empty-tip">暂无磁盘数据</div>
        <div v-else class="disk-list">
          <div v-for="disk in disks" :key="disk.mountpoint" class="disk-item"
               :class="{ 'disk-warn': disk.percent >= 85 }">
            <div class="disk-header">
              <span class="disk-mount">{{ disk.mountpoint }}</span>
              <span class="disk-usage">{{ disk.used_gb }} / {{ disk.total_gb }} GB</span>
              <span class="disk-pct" :class="colorClass(disk.percent)">{{ disk.percent }}%</span>
              <span v-if="disk.percent >= 90" class="disk-alert-badge danger">空间严重不足</span>
              <span v-else-if="disk.percent >= 85" class="disk-alert-badge warn">空间紧张</span>
            </div>
            <div class="disk-bar-wrap">
              <div class="disk-bar" :style="{ width: disk.percent + '%' }"
                   :class="diskBarColor(disk.percent)" />
            </div>
            <div class="disk-free">剩余 {{ disk.free_gb }} GB</div>
          </div>
        </div>

        <div class="sys-info-row">
          <span>Java {{ sysInfo.java_version || '-' }}</span>
          <span>工具 {{ sysInfo.tools_count ?? '-' }} 个</span>
          <span>{{ lastUpdated }}</span>
        </div>
      </div>
    </div>

    <!-- 资源用量面板 -->
    <div class="detail-card resource-config-card">
      <div class="detail-title">
        <i class="fas fa-chart-bar" /> 实时用量
        <span class="rc-tip">各资源当前消耗</span>
        <router-link to="/admin/mcp" class="rc-config-link">
          <i class="fas fa-cog" /> 调整参数上限
        </router-link>
      </div>
      <div class="rc-usage-grid">
        <div class="rc-usage-item">
          <span class="ru-label">推理并发</span>
          <div class="ru-bar-wrap">
            <div class="ru-bar" :style="{ width: usagePct(rcUsage.active_inferences, rcCfg.inference_concurrency) + '%' }" />
          </div>
          <span class="ru-val">{{ rcUsage.active_inferences ?? 0 }} / {{ rcCfg.inference_concurrency }}</span>
        </div>
        <div class="rc-usage-item">
          <span class="ru-label">等待队列</span>
          <div class="ru-bar-wrap">
            <div class="ru-bar ru-bar-queue" :style="{ width: usagePct(queueUsed, queueMax) + '%' }" />
          </div>
          <span class="ru-val">已用 {{ queueUsed }} / {{ queueMax || '—' }}</span>
        </div>
        <div class="rc-usage-item">
          <span class="ru-label">精确缓存</span>
          <div class="ru-bar-wrap">
            <div class="ru-bar ru-bar-cache" :style="{ width: usagePct(rcUsage.l1_cache_entries, rcCfg.response_cache_max_size) + '%' }" />
          </div>
          <span class="ru-val">{{ rcUsage.l1_cache_entries ?? 0 }} / {{ rcCfg.response_cache_max_size }}</span>
        </div>
        <div class="rc-usage-item">
          <span class="ru-label">语义缓存</span>
          <div class="ru-bar-wrap">
            <div class="ru-bar ru-bar-cache" :style="{ width: usagePct(rcUsage.l2_cache_entries, rcCfg.semantic_cache_max_entries) + '%' }" />
          </div>
          <span class="ru-val">{{ rcUsage.l2_cache_entries ?? 0 }} / {{ rcCfg.semantic_cache_max_entries }}</span>
        </div>
        <div class="rc-usage-item">
          <span class="ru-label">短期记忆</span>
          <div class="ru-bar-wrap">
            <div class="ru-bar ru-bar-mem" :style="{ width: usagePct(rcUsage.short_term_entries, rcCfg.short_term_max_size) + '%' }" />
          </div>
          <span class="ru-val">{{ rcUsage.short_term_entries ?? 0 }} / {{ rcCfg.short_term_max_size }}</span>
        </div>
        <div class="rc-usage-item">
          <span class="ru-label">长期记忆</span>
          <div class="ru-bar-wrap">
            <div class="ru-bar ru-bar-mem" style="background:var(--color-violet)" :style="{ width: Math.min(100, (rcUsage.long_term_entries ?? 0) / 200 * 100) + '%' }" />
          </div>
          <span class="ru-val">{{ rcUsage.long_term_entries ?? 0 }} 条</span>
        </div>
      </div>
    </div>

    <!-- 模型管理与云端服务商已迁移至模型管理页 -->
    <div class="redirect-hint">
      <i class="fas fa-robot" />
      可用模型列表、云端服务商配置和激活请前往
      <router-link to="/admin/models" class="redirect-link">模型管理</router-link>
      页面操作
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, defineComponent, h } from 'vue'
import { useWebSocketStore } from '@/stores/websocket'
import {
  getJavaHealth, getSystemInfo, getModels, getSystemResources,
  getRuntimeConfig,
} from '@/services/api'

// ── SparkLine ─────────────────────────────────────────────
const SparkLine = defineComponent({
  props: {
    data:  { type: Array,  default: () => [] },
    color: { type: String, default: 'var(--color-primary)' },
  },
  setup(props) {
    return () => {
      const W = 200, H = 40, pad = 2
      const pts = props.data
      if (pts.length < 2)
        return h('svg', { viewBox: `0 0 ${W} ${H}`, style: 'width:100%;height:40px' })
      const min = Math.min(...pts)
      const max = Math.max(...pts) || 1
      const xs  = pts.map((_, i) => pad + (i / (pts.length - 1)) * (W - pad * 2))
      const ys  = pts.map(v => H - pad - ((v - min) / (max - min || 1)) * (H - pad * 2))
      const d   = xs.map((x, i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${ys[i].toFixed(1)}`).join(' ')
      const area = `${d} L${xs[xs.length-1].toFixed(1)},${H} L${xs[0].toFixed(1)},${H} Z`
      const gid  = `g${props.color.replace('#', '')}`
      return h('svg', { viewBox: `0 0 ${W} ${H}`, style: 'width:100%;height:40px;display:block' }, [
        h('defs', [
          h('linearGradient', { id: gid, x1:'0', y1:'0', x2:'0', y2:'1' }, [
            h('stop', { offset: '0%',   'stop-color': props.color, 'stop-opacity': '0.3' }),
            h('stop', { offset: '100%', 'stop-color': props.color, 'stop-opacity': '0.02' }),
          ])
        ]),
        h('path', { d: area, fill: `url(#${gid})` }),
        h('path', { d, fill: 'none', stroke: props.color,
                    'stroke-width': '1.5', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
        h('circle', { cx: xs[xs.length-1].toFixed(1), cy: ys[ys.length-1].toFixed(1),
                      r: '3', fill: props.color }),
      ])
    }
  }
})

// ── Store ─────────────────────────────────────────────────
const wsStore         = useWebSocketStore()
const responseTimes = computed(() => wsStore.responseTimes || [])

// ── 状态 ─────────────────────────────────────────────────
const loading      = ref(false)
const showOthers   = ref(false)
const showMemTips  = ref(false)
const topOthers    = ref([])
const javaOk       = ref(null)   // null=检测中, true=ok, false=失败, 'timeout'=超时
const ollamaOk     = ref(false)
const currentModel = ref('')
const cloudModel   = ref('')
const cloudBaseUrl = ref('')
const cloudMode    = ref(false)
const sysInfo      = ref({})
const resources    = ref({})
const processes    = ref({})
const disks        = ref([])
const ollamaModels = ref([])
const lastUpdated  = ref('-')
const countdown    = ref(10)

// ── 资源配置（只读用量展示） ──────────────────────────────
const rcCfg   = ref({})
const rcUsage = ref({})

const usagePct = (used, total) => total > 0 ? Math.min(100, Math.round((used ?? 0) / total * 100)) : 0

// 等待队列：后端暂未上报排队人数（queue_slots），缺失时按 0 使用、上限缺失显示 —，避免 NaN
const queueMax = computed(() => rcCfg.value.inference_queue_size ?? 0)
const queueUsed = computed(() => Math.max(0, queueMax.value - (rcUsage.value.queue_slots ?? queueMax.value)))

const loadRuntimeConfig = async () => {
  const data = await getRuntimeConfig()
  if (!data) return
  rcCfg.value   = data.config || {}
  rcUsage.value = data.usage  || {}
}

const MAX_POINTS = 30
const history = ref({ cpu: [], memory: [], gpu: [] })

const push = (key, val) => {
  if (val == null) return
  history.value[key].push(val)
  if (history.value[key].length > MAX_POINTS) history.value[key].shift()
}

// ── 响应时间 ──────────────────────────────────────────────
const rtHistory    = computed(() => responseTimes.value.map(r => r.time))
const lastRt       = computed(() => {
  const arr = responseTimes.value
  return arr.length ? arr[arr.length - 1].time : null
})
const avgRt = computed(() => {
  if (!responseTimes.value.length) return null
  return (responseTimes.value.reduce((s, r) => s + r.time, 0) / responseTimes.value.length).toFixed(1)
})
const rtColor      = computed(() => lastRt.value == null ? 'var(--color-primary)' :
  lastRt.value > 30 ? 'var(--color-danger)' : lastRt.value > 15 ? 'var(--color-warn)' : 'var(--color-success)')
const rtColorClass = (v) => v == null ? '' : v > 30 ? 'val-danger' : v > 15 ? 'val-warn' : 'val-ok'

// ── 进程 ─────────────────────────────────────────────────
const processesSorted = computed(() =>
  Object.fromEntries(
    Object.entries(processes.value).sort(([nameA, a], [nameB, b]) => {
      if (nameA === '其他进程') return 1
      if (nameB === '其他进程') return -1
      return b.mem_mb - a.mem_mb
    })
  )
)
const agentTotalMb    = computed(() =>
  Object.values(processes.value).reduce((s, v) => s + v.mem_mb, 0).toFixed(1)
)
const totalMemMb      = computed(() => (resources.value.memory_total_gb || 1) * 1024)

const processBarWidth = (mb) => Math.min(100, Math.round(mb / totalMemMb.value * 100))
const processBarColor = (mb, name) => {
  if (name === '其他进程') return 'bar-other'
  const pct = mb / totalMemMb.value * 100
  return pct >= 30 ? 'bar-danger' : pct >= 15 ? 'bar-warn' : 'bar-ok'
}
const processIcon = (name) => ({
  'Ollama':       'fas fa-robot',
  'Java 后端':    'fab fa-java',
  '前端(Node)':   'fab fa-node-js',
  '前端(Vite)':   'fas fa-bolt',
  '其他进程':     'fas fa-layer-group',
}[name] || 'fas fa-cog')

// ── 磁盘 ─────────────────────────────────────────────────
const diskBarColor = (pct) => pct >= 90 ? 'bar-danger' : pct >= 70 ? 'bar-warn' : 'bar-ok'

// ── Ollama 模型过期 ───────────────────────────────────────
const formatExpires = (iso) => {
  if (!iso) return ''
  const diff = new Date(iso) - Date.now()
  if (diff <= 0)          return '已卸载'
  if (diff < 60000)       return `${Math.floor(diff / 1000)}s 后卸载`
  if (diff < 3600000)     return `${Math.floor(diff / 60000)}min 后卸载`
  return `${Math.floor(diff / 3600000)}h 后卸载`
}

// ── 颜色 ─────────────────────────────────────────────────
const colorClass = (v) => {
  if (v == null) return ''
  return v >= 80 ? 'val-danger' : v >= 60 ? 'val-warn' : 'val-ok'
}
const lineColor = (v) => {
  if (v == null) return 'var(--color-primary)'
  return v >= 80 ? 'var(--color-danger)' : v >= 60 ? 'var(--color-warn)' : 'var(--color-success)'
}

/** 给 Promise 套一个 8s 超时，超时 reject 带 {timedOut: true} 标记 */
const withTimeout = (promise, ms = 8000) =>
  Promise.race([
    promise,
    new Promise((_, rej) => setTimeout(() => rej({ timedOut: true }), ms))
  ])

// ── 数据加载 ──────────────────────────────────────────────
const refresh = async () => {
  loading.value   = true
  countdown.value = 10
  try {
    const [javaRes, sys, modelData, res] = await Promise.allSettled([
      withTimeout(getJavaHealth()),
      getSystemInfo(), getModels(), getSystemResources()
    ])

    const java   = javaRes.status   === 'fulfilled' ? javaRes.value   : null

    if (javaRes.status === 'rejected' && javaRes.reason?.timedOut)     javaOk.value = 'timeout'
    else javaOk.value   = java != null ? java.status === 'UP' : false

    const sysd       = sys.status      === 'fulfilled' ? sys.value       : null
    const modelData2 = modelData.status === 'fulfilled' ? modelData.value : null
    const resData    = res.status      === 'fulfilled' ? res.value       : null

    if (sysd) {
      sysInfo.value      = sysd
      ollamaOk.value     = sysd.ollama_available === true
      cloudMode.value    = !!sysd.cloud_mode
      cloudBaseUrl.value = sysd.cloud_base_url || ''
      cloudModel.value   = sysd.cloud_model || modelData2?.cloud_model || ''
    }

    if (modelData2?.available_models) {
      currentModel.value = modelData2.current_model || sysd?.agent_model || ''
    } else if (sysd?.agent_model) {
      currentModel.value = sysd.agent_model
    }

    if (resData) {
      resources.value    = resData
      processes.value    = resData.processes            || {}
      topOthers.value    = resData.top_other_processes  || []
      disks.value        = resData.disks                || []
      ollamaModels.value = resData.ollama_models        || []
      push('cpu',    resData.cpu_percent)
      push('memory', resData.memory_percent)
      push('gpu',    resData.gpu?.util_percent ?? null)
    }

    lastUpdated.value = new Date().toLocaleTimeString('zh-CN')

    // 同步刷新资源配置用量
    loadRuntimeConfig()
  } finally {
    loading.value = false
  }
}

let timer = null, clockTimer = null
onMounted(() => {
  refresh()
  loadRuntimeConfig()
  timer      = setInterval(refresh, 10000)
  clockTimer = setInterval(() => { countdown.value = Math.max(0, countdown.value - 1) }, 1000)
})
onUnmounted(() => { clearInterval(timer); clearInterval(clockTimer) })
</script>

<style scoped>
.cloud-model-item { border-color: var(--color-primary-soft); background: linear-gradient(135deg, var(--color-surface-raised), var(--color-surface)); }
.cloud-badge { background: var(--color-primary); }
.model-local-badge {
  font-size: var(--text-xs); background: var(--color-surface-sunken); color: var(--color-text-muted);
  padding: var(--space-px) var(--space-1-5); border-radius: var(--radius-sm);
}
.system-view {
  padding: var(--space-4-5); height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: var(--space-3-5); background: var(--color-surface-sunken);
}
.toolbar { display: flex; align-items: center; justify-content: space-between; }
.toolbar-right { display: flex; align-items: center; gap: var(--space-2-5); }
.countdown-badge { font-size: var(--text-xs); color: var(--color-text-muted); background: var(--color-surface-sunken); padding: var(--space-1) var(--space-2-5); border-radius: var(--radius-sm); }
.refresh-btn {
  display: flex; align-items: center; gap: var(--space-1-5); padding: var(--space-1-5) var(--space-3-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface); font-size: var(--text-sm); color: var(--color-text); cursor: pointer;
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.card-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); }
.status-card {
  background: var(--color-surface); border-radius: var(--radius-md); border: 0.5px solid var(--color-border);
  padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-2);

  box-shadow: var(--shadow-card);}
.cloud-card { border-color: var(--color-primary-soft); background: linear-gradient(135deg, var(--color-surface) 80%, var(--color-surface-raised)); }
.card-label { font-size: var(--text-sm-2); color: var(--color-text-secondary); font-weight: var(--weight-medium); }
.card-sub   { font-size: var(--text-sm-2); color: var(--color-text-muted); }
.cloud-url  { font-size: var(--text-xs); color: var(--color-text-muted); word-break: break-all; }
.status-badge {
  display: inline-flex; align-items: center; gap: var(--space-1);
  font-size: var(--text-sm); font-weight: var(--weight-medium); padding: var(--space-1) var(--space-2-5); border-radius: var(--radius-lg); width: fit-content;
}
.status-badge.ok       { background: var(--color-success-soft); color: var(--color-success); }
.status-badge.err      { background: var(--color-danger-soft); color: var(--color-danger); }
.status-badge.checking { background: var(--color-surface-sunken); color: var(--color-text-muted); }
.status-badge.timeout  { background: var(--color-warn-soft); color: var(--color-warn); }
.retry-btn {
  margin-left: var(--space-2);
  padding: var(--space-0-5) var(--space-2);
  font-size: var(--text-xs);
  border: 1px solid currentColor;
  border-radius: var(--radius-xs);
  background: transparent;
  color: inherit;
  cursor: pointer;
  opacity: 0.8;
}
.retry-btn:hover { opacity: 1; }

.chart-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); }
.chart-card { background: var(--color-surface); border-radius: var(--radius-md); border: 0.5px solid var(--color-border); padding: var(--space-3-5); 
  box-shadow: var(--shadow-card);}
.chart-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-1-5); }
.chart-title  { font-size: var(--text-sm-2); color: var(--color-text-secondary); display: flex; align-items: center; gap: var(--space-1); }
.chart-title i { color: var(--color-primary); }
.chart-val    { font-size: var(--text-xl); font-weight: var(--weight-medium); }
.chart-detail { display: flex; justify-content: space-between; font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-1); }
.val-ok { color: var(--color-success); } .val-warn { color: var(--color-warn); } .val-danger { color: var(--color-danger); }

.core-grid { display: flex; gap: var(--space-0-5); align-items: flex-end; height: 24px; margin-top: var(--space-1-5); }
.core-bar-wrap { flex: 1; height: 100%; display: flex; align-items: flex-end; background: var(--color-surface-sunken); border-radius: var(--radius-xs); overflow: hidden; }
.core-bar { width: 100%; border-radius: var(--radius-xs); transition: height var(--duration-slow) var(--ease-standard); min-height: 2px; }
.core-bar.val-ok { background: var(--color-success); } .core-bar.val-warn { background: var(--color-warn); } .core-bar.val-danger { background: var(--color-danger); }

.mini-progress-wrap { height: 4px; background: var(--color-border); border-radius: var(--radius-xs); overflow: hidden; margin-top: var(--space-1-5); }
.mini-progress-bar  { height: 100%; border-radius: var(--radius-xs); transition: width var(--duration-slow); }
.gpu-bar  { background: var(--color-primary); }
.gpu-name  { font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-1); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.gpu-empty { font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-2); text-align: center; }
.gpu-empty-sub { font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-1); }

.detail-row  { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.detail-card { background: var(--color-surface); border-radius: var(--radius-md); border: 0.5px solid var(--color-border); padding: var(--space-4); 
  box-shadow: var(--shadow-card);}
.redirect-hint {
  background: var(--color-surface-raised); border: 1px solid var(--color-primary-soft); border-radius: var(--radius-sm);
  padding: var(--space-3) var(--space-4); color: var(--color-text); font-size: var(--text-sm); display: flex; align-items: center; gap: var(--space-2);
}
.redirect-hint i { color: var(--color-primary); }
.redirect-link { color: var(--color-primary); text-decoration: none; font-weight: var(--weight-semibold); }
.redirect-link:hover { text-decoration: underline; }
.detail-title {
  font-size: var(--text-base); font-weight: var(--weight-medium); color: var(--color-text);
  margin-bottom: var(--space-3-5); display: flex; align-items: center; gap: var(--space-2);
}
.detail-title i { color: var(--color-primary); }
.empty-tip  { color: var(--color-text-muted); font-size: var(--text-sm); padding: var(--space-2-5) 0; }
.divider    { border: none; border-top: 0.5px solid var(--color-border); margin: var(--space-3-5) 0; }

/* ── 云端模型信息 ── */
.cloud-info-box { margin-bottom: var(--space-1); }
.cloud-info-item {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-1-5) 0; border-bottom: 0.5px solid var(--color-surface-sunken); font-size: var(--text-sm);
}
.cloud-info-item:last-child { border-bottom: none; }
.ci-label { color: var(--color-text-muted); min-width: 64px; flex-shrink: 0; font-size: var(--text-sm-2); }
.ci-val   { color: var(--color-text); font-weight: var(--weight-medium); word-break: break-all; }
.model-name-highlight { color: var(--color-primary); font-size: var(--text-base); }

/* ── 进程内存 ── */
.process-list { display: flex; flex-direction: column; gap: var(--space-2-5); }
.process-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-1); }
.process-name { font-size: var(--text-sm); color: var(--color-text); display: flex; align-items: center; gap: var(--space-1-5); }
.process-name i { color: var(--color-text-muted); font-size: var(--text-sm-2); width: 14px; text-align: center; }
.process-mem  { font-size: var(--text-sm-2); font-weight: var(--weight-medium); color: var(--color-text); }
.process-bar-wrap { height: 5px; background: var(--color-border); border-radius: var(--radius-xs); overflow: hidden; }
.process-bar { height: 100%; border-radius: var(--radius-xs); transition: width var(--duration-slow) var(--ease-standard); }
.bar-ok { background: var(--color-success); } .bar-warn { background: var(--color-warn); } .bar-danger { background: var(--color-danger); }
.bar-other { background: var(--color-text-secondary); }
.process-other { opacity: 0.85; }
.process-other .process-name { color: var(--color-text-secondary); }
.other-tip { font-size: var(--text-xs); color: var(--color-text-muted); margin-left: var(--space-1); font-weight: var(--weight-normal); }

.expand-btn {
  font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border-strong); background: var(--color-surface); color: var(--color-primary);
  cursor: pointer; white-space: nowrap;
}
.expand-btn:hover { background: var(--color-surface-raised); }

.other-detail {
  margin-top: var(--space-1-5); padding: var(--space-2) var(--space-2-5);
  background: var(--color-surface-sunken); border-radius: var(--radius-sm);
  display: flex; flex-direction: column; gap: var(--space-1);
}
.other-row {
  display: grid; grid-template-columns: 140px 1fr 70px;
  align-items: center; gap: var(--space-2); font-size: var(--text-xs);
}
.other-name { color: var(--color-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.other-bar-wrap { height: 4px; background: var(--color-border); border-radius: var(--radius-xs); overflow: hidden; }
.other-bar { display: block; height: 100%; background: var(--color-text-secondary); border-radius: var(--radius-xs); min-width: 2px; }
.other-mem { color: var(--color-text-secondary); text-align: right; font-variant-numeric: tabular-nums; }
.other-row-hint { opacity: 0.5; }
.process-total { display: flex; justify-content: space-between; padding-top: var(--space-2); border-top: 0.5px solid var(--color-border); font-size: var(--text-sm); color: var(--color-text-secondary); }
.process-total.mem-breakdown-hint { border-top: none; padding-top: var(--space-0-5); color: var(--color-text-muted); font-size: var(--text-sm-2); }
.total-val { font-weight: var(--weight-medium); color: var(--color-text); }

/* ── Ollama 模型 ── */
.ollama-model-list { display: flex; flex-direction: column; gap: var(--space-2-5); margin-bottom: var(--space-1); }
.ollama-model-item { padding: var(--space-2-5) var(--space-3); background: var(--color-surface-sunken); border-radius: var(--radius-sm); }
.ollama-model-row  { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-1); }
.ollama-model-name { font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text); }
.ollama-model-size { font-size: var(--text-sm-2); color: var(--color-text-secondary); }
.ollama-model-meta { display: flex; gap: var(--space-2); }
.vram-badge    { font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); background: var(--color-primary-soft); color: var(--color-primary); border-radius: var(--radius-sm); }
.expires-badge { font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); background: var(--color-warn-soft); color: var(--color-warn); border-radius: var(--radius-sm); }

/* ── 磁盘 ── */
.disk-list { display: flex; flex-direction: column; gap: var(--space-3); }
.disk-header { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-1); }
.disk-mount  { font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text); min-width: 36px; }
.disk-usage  { font-size: var(--text-sm-2); color: var(--color-text-secondary); flex: 1; }
.disk-pct    { font-size: var(--text-sm-2); font-weight: var(--weight-medium); }
.disk-bar-wrap { height: 5px; background: var(--color-border); border-radius: var(--radius-xs); overflow: hidden; margin-bottom: var(--space-1); }
.disk-bar    { height: 100%; border-radius: var(--radius-xs); transition: width var(--duration-slow); }
.disk-free   { font-size: var(--text-xs); color: var(--color-text-muted); }
.disk-warn   { background: var(--color-warn-soft); border-radius: var(--radius-sm); padding: var(--space-1-5); margin: -6px; }
.disk-alert-badge {
  font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-sm); font-weight: var(--weight-medium);
}
.disk-alert-badge.warn   { background: var(--color-warn-soft); color: var(--color-warn); }
.disk-alert-badge.danger { background: var(--color-danger-soft); color: var(--color-danger); }

/* ── 模型列表 ── */
.model-list { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.model-item {
  display: flex; align-items: center; gap: var(--space-2); padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm); border: 1px solid var(--color-border); font-size: var(--text-sm); color: var(--color-text);
}
.model-item.active { border-color: var(--color-primary-soft); background: var(--color-surface-raised); color: var(--color-primary); }
.model-item i      { color: var(--color-text-muted); font-size: var(--text-sm-2); }
.model-item.active i { color: var(--color-primary); }
.model-badge { font-size: var(--text-xs); background: var(--color-primary); color: var(--color-text-on-primary); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-sm); }

.sys-info-row {
  display: flex; justify-content: space-between; font-size: var(--text-xs); color: var(--color-text-muted);
  margin-top: var(--space-3-5); padding-top: var(--space-2-5); border-top: 0.5px solid var(--color-border);
}

/* ── 内存优化建议 ── */
.mem-tips-card { grid-column: 1 / -1; }
.tips-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-4); }
.tip-group { background: var(--color-surface-sunken); border-radius: var(--radius-sm); padding: var(--space-3-5) var(--space-4); border: 0.5px solid var(--color-border); }
.tip-group-title { font-size: var(--text-sm-2); font-weight: var(--weight-semibold); color: var(--color-text); margin-bottom: var(--space-2-5); display: flex; align-items: center; gap: var(--space-1-5); }
.tip-item { display: flex; gap: var(--space-2); align-items: flex-start; font-size: var(--text-sm-2); color: var(--color-text); margin-bottom: var(--space-2); line-height: var(--leading-normal); }
.tip-item:last-child { margin-bottom: 0; }
.tip-item code { background: var(--color-surface-sunken); padding: var(--space-px) var(--space-1); border-radius: var(--radius-xs); font-size: var(--text-xs); color: var(--color-text); word-break: break-all; }
.tip-tag { flex-shrink: 0; font-size: var(--text-2xs); padding: var(--space-0-5) var(--space-1-5); border-radius: var(--radius-sm); font-weight: var(--weight-semibold); margin-top: var(--space-px); }
.tip-high .tip-tag, .tip-tag.high { background: var(--color-danger-soft); color: var(--color-danger); }
.tip-tag.mid  { background: var(--color-success-soft); color: var(--color-success); }
.tip-tag.low  { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
@media (max-width: 1100px) { .tips-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 700px)  { .tips-grid { grid-template-columns: 1fr; } }

/* ── 资源配置面板 ── */
.resource-config-card { grid-column: 1 / -1; }

.detail-title .rc-tip {
  font-size: var(--text-xs); color: var(--color-text-muted); font-weight: var(--weight-normal); margin-left: var(--space-2);
}
.rc-config-link {
  margin-left: auto; padding: var(--space-1) var(--space-3-5); background: var(--color-surface-raised); color: var(--color-primary);
  border: 1px solid var(--color-primary-soft); border-radius: var(--radius-sm); font-size: var(--text-sm-2);
  text-decoration: none; display: flex; align-items: center; gap: var(--space-1-5); transition: background var(--duration-base);
}
.rc-config-link:hover { background: var(--color-primary-soft); }

/* 用量列 */
.rc-section-label {
  font-size: var(--text-sm-2); font-weight: var(--weight-semibold); color: var(--color-primary); text-transform: uppercase;
  letter-spacing: var(--tracking-wide); margin-bottom: var(--space-1); display: flex; align-items: center; gap: var(--space-1-5);
}
.rc-usage-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: var(--space-2-5); }
.rc-usage-item { display: flex; flex-direction: column; gap: var(--space-1); }
.ru-label { font-size: var(--text-xs); color: var(--color-text-secondary); }
.ru-bar-wrap { height: 5px; background: var(--color-border); border-radius: var(--radius-xs); overflow: hidden; }
.ru-bar { height: 100%; background: var(--color-primary); border-radius: var(--radius-xs); transition: width var(--duration-slow); min-width: 2px; }
.ru-bar-queue { background: var(--color-warn); }
.ru-bar-cache { background: var(--color-success); }
.ru-bar-mem   { background: var(--color-violet); }
.ru-val { font-size: var(--text-xs); color: var(--color-text-muted); text-align: right; }

/* 配置列 */
.rc-form-col { display: flex; flex-direction: column; gap: var(--space-3); }
.rc-form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-3-5); }

.rc-group {
  background: var(--color-surface-sunken); border-radius: var(--radius-sm); padding: var(--space-3) var(--space-3-5);
  border: 0.5px solid var(--color-border);
}
.rc-group-title { font-size: var(--text-xs); font-weight: var(--weight-semibold); color: var(--color-text); margin-bottom: var(--space-2-5); }
.rc-field { margin-bottom: var(--space-2-5); }
.rc-field:last-child { margin-bottom: 0; }
.rc-field label { font-size: var(--text-xs); color: var(--color-text-secondary); display: block; margin-bottom: var(--space-1); }
.rc-range { color: var(--color-text-muted); font-weight: var(--weight-normal); }
.rc-hint-text { font-size: var(--text-xs); color: var(--color-text-muted); margin-bottom: var(--space-1); line-height: var(--leading-snug); }
.rc-slider-row { display: flex; align-items: center; gap: var(--space-2); }
.rc-slider-row input[type="range"] {
  flex: 1; height: 4px; accent-color: var(--color-primary);
  cursor: pointer;
}
.rc-num {
  width: 72px; padding: var(--space-1) var(--space-1-5); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  font-size: var(--text-sm-2); text-align: right; color: var(--color-text); outline: none;
}
.rc-num:focus { border-color: var(--color-primary); }

@media (max-width: 1200px) {
  .rc-form-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 900px) {
  .rc-body { grid-template-columns: 1fr; }
  .rc-form-grid { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .card-row  { grid-template-columns: repeat(2, 1fr); }
  .chart-row { grid-template-columns: repeat(2, 1fr); }
  .detail-row { grid-template-columns: 1fr; }
}

</style>
