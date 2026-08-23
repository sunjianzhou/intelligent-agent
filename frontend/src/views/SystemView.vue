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
          <i v-if="cloudMode" class="fas fa-cloud" style="color:#667eea;margin-right:4px" />
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
          <i class="fas fa-info-circle" style="font-size:1rem;color:#aaa;display:block;margin-bottom:4px" />
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
              <div style="display:flex;align-items:center;gap:8px">
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
                <span class="other-name" style="color:#bbb">（其余小进程未列出）</span>
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
            <span class="status-badge ok" style="font-size:0.78rem;padding:2px 10px">
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
            <div class="ru-bar ru-bar-mem" style="background:#a78bfa" :style="{ width: Math.min(100, (rcUsage.long_term_entries ?? 0) / 200 * 100) + '%' }" />
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
    color: { type: String, default: '#667eea' },
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
const rtColor      = computed(() => lastRt.value == null ? '#667eea' :
  lastRt.value > 30 ? '#e53935' : lastRt.value > 15 ? '#f57c00' : '#43a047')
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
  if (v == null) return '#667eea'
  return v >= 80 ? '#e53935' : v >= 60 ? '#f57c00' : '#43a047'
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
.cloud-model-item { border-color: #c5caf5; background: linear-gradient(135deg, #f0f2ff, #fff); }
.cloud-badge { background: #667eea; }
.model-local-badge {
  font-size: 0.72rem; background: #f5f5f5; color: #999;
  padding: 1px 7px; border-radius: 8px;
}
.system-view {
  padding: 20px; height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: 14px; background: #f8f9fa;
}
.toolbar { display: flex; align-items: center; justify-content: space-between; }
.toolbar-right { display: flex; align-items: center; gap: 10px; }
.countdown-badge { font-size: 0.78rem; color: #aaa; background: #f0f0f0; padding: 4px 10px; border-radius: 10px; }
.refresh-btn {
  display: flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white; font-size: 0.88rem; color: #555; cursor: pointer;
}
.refresh-btn:hover { border-color: #667eea; color: #667eea; }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.card-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.status-card {
  background: white; border-radius: 12px; border: 0.5px solid #e8eaed;
  padding: 16px; display: flex; flex-direction: column; gap: 8px;
}
.cloud-card { border-color: #c5caf5; background: linear-gradient(135deg, #fff 80%, #f0f2ff); }
.card-label { font-size: 0.82rem; color: #888; font-weight: 500; }
.card-sub   { font-size: 0.78rem; color: #aaa; }
.cloud-url  { font-size: 0.72rem; color: #bbb; word-break: break-all; }
.status-badge {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 0.88rem; font-weight: 500; padding: 4px 10px; border-radius: 20px; width: fit-content;
}
.status-badge.ok       { background: #e8f5e9; color: #2e7d32; }
.status-badge.err      { background: #fce4e4; color: #c62828; }
.status-badge.checking { background: #f5f5f5; color: #999; }
.status-badge.timeout  { background: #fff3e0; color: #e65100; }
.retry-btn {
  margin-left: 8px;
  padding: 2px 8px;
  font-size: 0.75rem;
  border: 1px solid currentColor;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  opacity: 0.8;
}
.retry-btn:hover { opacity: 1; }

.chart-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.chart-card { background: white; border-radius: 12px; border: 0.5px solid #e8eaed; padding: 14px; }
.chart-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.chart-title  { font-size: 0.82rem; color: #888; display: flex; align-items: center; gap: 5px; }
.chart-title i { color: #667eea; }
.chart-val    { font-size: 1.2rem; font-weight: 500; }
.chart-detail { display: flex; justify-content: space-between; font-size: 0.75rem; color: #aaa; margin-top: 4px; }
.val-ok { color: #43a047; } .val-warn { color: #f57c00; } .val-danger { color: #e53935; }

.core-grid { display: flex; gap: 2px; align-items: flex-end; height: 24px; margin-top: 6px; }
.core-bar-wrap { flex: 1; height: 100%; display: flex; align-items: flex-end; background: #f5f5f5; border-radius: 2px; overflow: hidden; }
.core-bar { width: 100%; border-radius: 2px; transition: height 0.4s ease; min-height: 2px; }
.core-bar.val-ok { background: #43a047; } .core-bar.val-warn { background: #f57c00; } .core-bar.val-danger { background: #e53935; }

.mini-progress-wrap { height: 4px; background: #f0f0f0; border-radius: 2px; overflow: hidden; margin-top: 6px; }
.mini-progress-bar  { height: 100%; border-radius: 2px; transition: width 0.4s; }
.gpu-bar  { background: #667eea; }
.gpu-name  { font-size: 0.72rem; color: #bbb; margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.gpu-empty { font-size: 0.78rem; color: #bbb; margin-top: 8px; text-align: center; }
.gpu-empty-sub { font-size: 0.72rem; color: #ccc; margin-top: 3px; }

.detail-row  { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.detail-card { background: white; border-radius: 12px; border: 0.5px solid #e8eaed; padding: 18px; }
.redirect-hint {
  background: #f0f2ff; border: 1px solid #c5caf5; border-radius: 10px;
  padding: 12px 16px; color: #555; font-size: 0.88rem; display: flex; align-items: center; gap: 8px;
}
.redirect-hint i { color: #667eea; }
.redirect-link { color: #667eea; text-decoration: none; font-weight: 600; }
.redirect-link:hover { text-decoration: underline; }
.detail-title {
  font-size: 0.95rem; font-weight: 500; color: #333;
  margin-bottom: 14px; display: flex; align-items: center; gap: 8px;
}
.detail-title i { color: #667eea; }
.empty-tip  { color: #aaa; font-size: 0.88rem; padding: 10px 0; }
.divider    { border: none; border-top: 0.5px solid #f0f0f0; margin: 14px 0; }

/* ── 云端模型信息 ── */
.cloud-info-box { margin-bottom: 4px; }
.cloud-info-item {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 0; border-bottom: 0.5px solid #f5f5f5; font-size: 0.88rem;
}
.cloud-info-item:last-child { border-bottom: none; }
.ci-label { color: #aaa; min-width: 64px; flex-shrink: 0; font-size: 0.82rem; }
.ci-val   { color: #333; font-weight: 500; word-break: break-all; }
.model-name-highlight { color: #667eea; font-size: 0.95rem; }

/* ── 进程内存 ── */
.process-list { display: flex; flex-direction: column; gap: 10px; }
.process-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.process-name { font-size: 0.85rem; color: #555; display: flex; align-items: center; gap: 6px; }
.process-name i { color: #aaa; font-size: 0.8rem; width: 14px; text-align: center; }
.process-mem  { font-size: 0.82rem; font-weight: 500; color: #333; }
.process-bar-wrap { height: 5px; background: #f0f0f0; border-radius: 3px; overflow: hidden; }
.process-bar { height: 100%; border-radius: 3px; transition: width 0.5s ease; }
.bar-ok { background: #43a047; } .bar-warn { background: #f57c00; } .bar-danger { background: #e53935; }
.bar-other { background: #9ca3af; }
.process-other { opacity: 0.85; }
.process-other .process-name { color: #888; }
.other-tip { font-size: 0.72rem; color: #bbb; margin-left: 4px; font-weight: 400; }

.expand-btn {
  font-size: 0.72rem; padding: 2px 8px; border-radius: 6px;
  border: 1px solid #d1d5db; background: white; color: #667eea;
  cursor: pointer; white-space: nowrap;
}
.expand-btn:hover { background: #f0f2ff; }

.other-detail {
  margin-top: 6px; padding: 8px 10px;
  background: #fafafa; border-radius: 8px;
  display: flex; flex-direction: column; gap: 5px;
}
.other-row {
  display: grid; grid-template-columns: 140px 1fr 70px;
  align-items: center; gap: 8px; font-size: 0.78rem;
}
.other-name { color: #666; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.other-bar-wrap { height: 4px; background: #e5e7eb; border-radius: 2px; overflow: hidden; }
.other-bar { display: block; height: 100%; background: #9ca3af; border-radius: 2px; min-width: 2px; }
.other-mem { color: #888; text-align: right; font-variant-numeric: tabular-nums; }
.other-row-hint { opacity: 0.5; }
.process-total { display: flex; justify-content: space-between; padding-top: 8px; border-top: 0.5px solid #f0f0f0; font-size: 0.85rem; color: #666; }
.process-total.mem-breakdown-hint { border-top: none; padding-top: 2px; color: #aaa; font-size: 0.8rem; }
.total-val { font-weight: 500; color: #333; }

/* ── Ollama 模型 ── */
.ollama-model-list { display: flex; flex-direction: column; gap: 10px; margin-bottom: 4px; }
.ollama-model-item { padding: 10px 12px; background: #f8f9fa; border-radius: 8px; }
.ollama-model-row  { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.ollama-model-name { font-size: 0.88rem; font-weight: 500; color: #333; }
.ollama-model-size { font-size: 0.82rem; color: #888; }
.ollama-model-meta { display: flex; gap: 8px; }
.vram-badge    { font-size: 0.75rem; padding: 2px 8px; background: #e3f2fd; color: #1976d2; border-radius: 8px; }
.expires-badge { font-size: 0.75rem; padding: 2px 8px; background: #fff3e0; color: #f57c00; border-radius: 8px; }

/* ── 磁盘 ── */
.disk-list { display: flex; flex-direction: column; gap: 12px; }
.disk-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.disk-mount  { font-size: 0.88rem; font-weight: 500; color: #333; min-width: 36px; }
.disk-usage  { font-size: 0.82rem; color: #888; flex: 1; }
.disk-pct    { font-size: 0.82rem; font-weight: 500; }
.disk-bar-wrap { height: 5px; background: #f0f0f0; border-radius: 3px; overflow: hidden; margin-bottom: 3px; }
.disk-bar    { height: 100%; border-radius: 3px; transition: width 0.5s; }
.disk-free   { font-size: 0.75rem; color: #bbb; }
.disk-warn   { background: #fffde7; border-radius: 8px; padding: 6px; margin: -6px; }
.disk-alert-badge {
  font-size: 0.72rem; padding: 2px 8px; border-radius: 8px; font-weight: 500;
}
.disk-alert-badge.warn   { background: #fff3e0; color: #f57c00; }
.disk-alert-badge.danger { background: #fce4e4; color: #c62828; }

/* ── 模型列表 ── */
.model-list { display: flex; flex-wrap: wrap; gap: 8px; }
.model-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border-radius: 8px; border: 1px solid #f0f0f0; font-size: 0.88rem; color: #555;
}
.model-item.active { border-color: #c5caf5; background: #f0f2ff; color: #667eea; }
.model-item i      { color: #aaa; font-size: 0.8rem; }
.model-item.active i { color: #667eea; }
.model-badge { font-size: 0.75rem; background: #667eea; color: white; padding: 2px 8px; border-radius: 10px; }

.sys-info-row {
  display: flex; justify-content: space-between; font-size: 0.75rem; color: #bbb;
  margin-top: 14px; padding-top: 10px; border-top: 0.5px solid #f0f0f0;
}

/* ── 内存优化建议 ── */
.mem-tips-card { grid-column: 1 / -1; }
.tips-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.tip-group { background: #fafafa; border-radius: 10px; padding: 14px 16px; border: 0.5px solid #f0f0f0; }
.tip-group-title { font-size: 0.82rem; font-weight: 600; color: #444; margin-bottom: 10px; display: flex; align-items: center; gap: 6px; }
.tip-item { display: flex; gap: 8px; align-items: flex-start; font-size: 0.82rem; color: #555; margin-bottom: 8px; line-height: 1.5; }
.tip-item:last-child { margin-bottom: 0; }
.tip-item code { background: #f0f0f0; padding: 1px 5px; border-radius: 4px; font-size: 0.78rem; color: #333; word-break: break-all; }
.tip-tag { flex-shrink: 0; font-size: 0.7rem; padding: 2px 7px; border-radius: 8px; font-weight: 600; margin-top: 1px; }
.tip-high .tip-tag, .tip-tag.high { background: #fce4e4; color: #c62828; }
.tip-tag.mid  { background: #e8f5e9; color: #2e7d32; }
.tip-tag.low  { background: #f5f5f5; color: #888; }
@media (max-width: 1100px) { .tips-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 700px)  { .tips-grid { grid-template-columns: 1fr; } }

/* ── 资源配置面板 ── */
.resource-config-card { grid-column: 1 / -1; }

.detail-title .rc-tip {
  font-size: 0.75rem; color: #aaa; font-weight: 400; margin-left: 8px;
}
.rc-config-link {
  margin-left: auto; padding: 5px 14px; background: #f0f2ff; color: #667eea;
  border: 1px solid #c5caf5; border-radius: 8px; font-size: 0.82rem;
  text-decoration: none; display: flex; align-items: center; gap: 6px; transition: background 0.2s;
}
.rc-config-link:hover { background: #e0e3ff; }

/* 用量列 */
.rc-section-label {
  font-size: 0.8rem; font-weight: 600; color: #667eea; text-transform: uppercase;
  letter-spacing: 0.04em; margin-bottom: 4px; display: flex; align-items: center; gap: 6px;
}
.rc-usage-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.rc-usage-item { display: flex; flex-direction: column; gap: 3px; }
.ru-label { font-size: 0.78rem; color: #888; }
.ru-bar-wrap { height: 5px; background: #f0f0f0; border-radius: 3px; overflow: hidden; }
.ru-bar { height: 100%; background: #667eea; border-radius: 3px; transition: width 0.4s; min-width: 2px; }
.ru-bar-queue { background: #f59e0b; }
.ru-bar-cache { background: #10b981; }
.ru-bar-mem   { background: #8b5cf6; }
.ru-val { font-size: 0.75rem; color: #aaa; text-align: right; }

/* 配置列 */
.rc-form-col { display: flex; flex-direction: column; gap: 12px; }
.rc-form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }

.rc-group {
  background: #fafafa; border-radius: 10px; padding: 12px 14px;
  border: 0.5px solid #f0f0f0;
}
.rc-group-title { font-size: 0.78rem; font-weight: 600; color: #555; margin-bottom: 10px; }
.rc-field { margin-bottom: 10px; }
.rc-field:last-child { margin-bottom: 0; }
.rc-field label { font-size: 0.78rem; color: #777; display: block; margin-bottom: 5px; }
.rc-range { color: #bbb; font-weight: 400; }
.rc-hint-text { font-size: 0.72rem; color: #999; margin-bottom: 4px; line-height: 1.4; }
.rc-slider-row { display: flex; align-items: center; gap: 8px; }
.rc-slider-row input[type="range"] {
  flex: 1; height: 4px; accent-color: #667eea;
  cursor: pointer;
}
.rc-num {
  width: 72px; padding: 3px 7px; border: 1px solid #e0e3e8; border-radius: 6px;
  font-size: 0.82rem; text-align: right; color: #333; outline: none;
}
.rc-num:focus { border-color: #667eea; }

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
