<template>
  <div class="stats-view">
    <div class="toolbar">
      <span class="page-desc">对话质量统计分析</span>
      <div class="toolbar-right">
        <!-- 自定义下拉，避免 Element Plus 全局注册依赖 -->
        <div class="filter-dropdown" ref="filterRef">
          <button class="filter-btn" @click="filterOpen = !filterOpen">
            {{ filterLabels[ratingFilter] }} <i class="fas fa-chevron-down" :style="{transform: filterOpen ? 'rotate(180deg)' : ''}"/>
          </button>
          <div v-if="filterOpen" class="filter-menu">
            <div v-for="opt in filterOptions" :key="opt.value"
                 class="filter-item" :class="{active: ratingFilter === opt.value}"
                 @click="selectFilter(opt.value)">
              {{ opt.label }}
            </div>
          </div>
        </div>
        <button class="refresh-btn" :class="{ spinning: loading }" @click="load">
          <i class="fas fa-sync-alt" /> 刷新
        </button>
      </div>
    </div>

    <!-- 概览卡片 -->
    <div class="overview-row" v-if="stats">
      <div class="ov-card">
        <div class="ov-val">{{ stats.total }}</div>
        <div class="ov-label">总对话数</div>
      </div>
      <div class="ov-card like">
        <div class="ov-val">{{ stats.likes }}</div>
        <div class="ov-label">👍 点赞</div>
      </div>
      <div class="ov-card dislike">
        <div class="ov-val">{{ stats.dislikes }}</div>
        <div class="ov-label">👎 点踩</div>
      </div>
      <div class="ov-card rate">
        <div class="ov-val" :class="rateColorClass">{{ stats.like_rate }}%</div>
        <div class="ov-label">满意率</div>
      </div>
      <div class="ov-card time">
        <div class="ov-val" :style="{ color: responseTimeColor }">{{ stats.avg_response_time }}s</div>
        <div class="ov-label">平均响应</div>
      </div>
    </div>

    <!-- R-10 用量/成本概览 -->
    <div class="overview-row usage-row" v-if="usage">
      <div class="ov-card">
        <div class="ov-val">{{ fmtNumber(usage.total_tokens) }}</div>
        <div class="ov-label">本月 Tokens</div>
      </div>
      <div class="ov-card cost">
        <div class="ov-val">¥{{ fmtCny(usage.total_cost_cny) }}</div>
        <div class="ov-label">本月估算成本</div>
      </div>
      <div class="ov-card" v-if="usage.monthly_limit_cny > 0">
        <div class="ov-val">¥{{ fmtCny(usage.monthly_limit_cny) }}</div>
        <div class="ov-label">月限额</div>
      </div>
      <div class="ov-card" v-if="usage.monthly_limit_cny > 0">
        <div class="ov-val" :class="usage.quota_exceeded ? 'rate-bad' : 'rate-good'">
          ¥{{ fmtCny(usage.remaining_cny) }}
        </div>
        <div class="ov-label">{{ usage.quota_exceeded ? '已超限，新请求被拒' : '剩余额度' }}</div>
      </div>
      <div class="ov-card calls">
        <div class="ov-val">{{ usage.calls }}</div>
        <div class="ov-label">本月 LLM 调用</div>
      </div>
    </div>

    <!-- R-10 用量成本明细 -->
    <div class="charts-row" v-if="usage">
      <div class="chart-card">
        <div class="chart-title"><i class="fas fa-coins" /> 按模型用量/成本（{{ usage.month }}）</div>
        <div v-if="!Object.keys(usage.by_model || {}).length" class="empty-tip">暂无数据</div>
        <div v-else class="rank-list">
          <div v-for="(m, name) in usage.by_model" :key="name" class="rank-item">
            <span class="rank-name">{{ name }}</span>
            <div class="rank-bar-wrap">
              <div class="rank-bar cost-bar"
                   :style="{ width: rankBarWidth(m.input_tokens + m.output_tokens, maxModelTokens) + '%' }" />
            </div>
            <span class="rank-count">{{ fmtNumber(m.input_tokens + m.output_tokens) }} tok</span>
            <span class="tool-avg">¥{{ fmtCny(m.cost_cny) }}</span>
          </div>
        </div>
      </div>
      <div class="chart-card">
        <div class="chart-title"><i class="fas fa-chart-line" /> 每日成本趋势</div>
        <div v-if="!usage.daily?.length" class="empty-tip">暂无数据</div>
        <div v-else class="bar-chart">
          <div v-for="d in usage.daily" :key="d.date" class="bar-item">
            <div class="bar-wrap">
              <div class="bar cost-bar"
                   :style="{ height: barHeight(d.cost_cny, maxDailyCost) + '%' }"
                   :title="`${d.date}: ¥${fmtCny(d.cost_cny)}`" />
            </div>
            <div class="bar-label">{{ d.date.slice(5) }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="charts-row">
      <!-- 每日对话趋势 -->
      <div class="chart-card">
        <div class="chart-title"><i class="fas fa-chart-bar" /> 每日对话数</div>
        <div v-if="!stats?.daily_counts?.length" class="empty-tip">暂无数据</div>
        <div v-else class="bar-chart">
          <div v-for="d in stats.daily_counts" :key="d.date" class="bar-item">
            <div class="bar-wrap">
              <div class="bar"
                   :style="{ height: barHeight(d.count, maxDailyCount) + '%' }"
                   :title="`${d.date}: ${d.count} 次`" />
            </div>
            <div class="bar-label">{{ d.date.slice(5) }}</div>
          </div>
        </div>
      </div>

      <!-- 响应时间趋势 -->
      <div class="chart-card">
        <div class="chart-title"><i class="fas fa-bolt" /> 响应时间趋势</div>
        <div v-if="!stats?.response_time_trend?.length" class="empty-tip">暂无数据</div>
        <svg v-else class="line-chart" viewBox="0 0 400 120">
          <polyline
            :points="rtPoints"
            fill="none" stroke="var(--color-primary)" stroke-width="2"
            stroke-linejoin="round" stroke-linecap="round"
          />
          <circle
            v-for="(p, i) in rtPointList" :key="i"
            :cx="p.x" :cy="p.y" r="3" fill="var(--color-primary)"
          >
            <title>{{ stats.response_time_trend[i]?.time }}: {{ stats.response_time_trend[i]?.value }}s</title>
          </circle>
        </svg>
        <div class="rt-range" v-if="stats?.response_time_trend?.length">
          <span>最快 {{ stats.response_time_trend.reduce((a,r)=>Math.min(a,r.value), Infinity).toFixed(1) }}s</span>
          <span>最慢 {{ stats.response_time_trend.reduce((a,r)=>Math.max(a,r.value), -Infinity).toFixed(1) }}s</span>
        </div>
      </div>
    </div>

    <div class="detail-row">
      <!-- 工具调用排行 -->
      <div class="detail-card">
        <div class="detail-title"><i class="fas fa-tools" /> 工具调用排行</div>
        <div v-if="!Object.keys(stats?.tool_usage||{}).length" class="empty-tip">暂无数据</div>
        <div v-else class="rank-list">
          <div v-for="(count, tool) in stats.tool_usage" :key="tool" class="rank-item">
            <span class="rank-name">{{ tool }}</span>
            <div class="rank-bar-wrap">
              <div class="rank-bar"
                   :style="{ width: rankBarWidth(count, maxToolCount) + '%' }" />
            </div>
            <span class="rank-count">{{ count }}</span>
          </div>
        </div>
      </div>

      <!-- Skill 触发排行 -->
      <div class="detail-card">
        <div class="detail-title"><i class="fas fa-magic" /> Skill 触发排行</div>
        <div v-if="!Object.keys(stats?.skill_usage||{}).length" class="empty-tip">暂无数据</div>
        <div v-else class="rank-list">
          <div v-for="(count, skill) in stats.skill_usage" :key="skill" class="rank-item">
            <span class="rank-name">{{ skill }}</span>
            <div class="rank-bar-wrap">
              <div class="rank-bar skill-bar"
                   :style="{ width: rankBarWidth(count, maxSkillCount) + '%' }" />
            </div>
            <span class="rank-count">{{ count }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Skill 触发日志 -->
    <div class="detail-card records-card" v-if="skillStats">
      <div class="detail-title">
        <i class="fas fa-magic" /> Skill 触发日志
        <span class="title-badge">共 {{ skillStats.total }} 次</span>
      </div>

      <!-- Skill 触发排行 -->
      <div class="rank-list" v-if="Object.keys(skillStats.by_skill || {}).length">
        <div v-for="(count, name) in skillStats.by_skill" :key="name" class="rank-item">
          <span class="rank-name">{{ name }}</span>
          <div class="rank-bar-wrap">
            <div class="rank-bar skill-bar"
                :style="{ width: rankBarWidth(count, Math.max(...Object.values(skillStats.by_skill))) + '%' }" />
          </div>
          <span class="rank-count">{{ count }} 次</span>
        </div>
      </div>
      <div v-else class="empty-tip">暂无 Skill 触发记录</div>

      <!-- 最近触发记录 -->
      <div class="divider" style="margin: var(--space-3-5) 0" />
      <div class="detail-title" style="margin-bottom:var(--space-2-5)">
        <i class="fas fa-history" /> 最近触发
      </div>
      <div class="record-list" v-if="skillRecords.length">
        <div v-for="r in skillRecords" :key="r.id" class="record-item skill-record">
          <div class="record-header">
            <span class="skill-badge">{{ r.skill_name }}</span>
            <span class="record-msg">{{ r.message }}</span>
            <span class="record-time">{{ formatISOShort(r.triggered_at) }}</span>
          </div>
          <div class="record-response" v-if="r.tools?.length">
            工具：{{ r.tools.join(', ') }}
          </div>
        </div>
      </div>
      <div v-else class="empty-tip">暂无记录</div>
    </div>

    <!-- 工具调用历史 -->
    <div class="detail-card records-card" v-if="toolStats">
      <div class="detail-title">
        <i class="fas fa-cube" /> 工具调用历史
        <span class="title-badge">共 {{ toolStats.total }} 次 · 成功率 {{ toolStats.success_rate }}%</span>
      </div>
      <div class="rank-list" v-if="Object.keys(toolStats.by_tool || {}).length">
        <div v-for="(info, name) in toolStats.by_tool" :key="name" class="rank-item">
          <span class="rank-name">{{ name }}</span>
          <div class="rank-bar-wrap">
            <div class="rank-bar"
                 :style="{ width: rankBarWidth(info.count, maxToolCallCount) + '%' }"
                 :class="info.success_rate >= 80 ? '' : 'rank-bar-warn'" />
          </div>
          <span class="rank-count">{{ info.count }} 次</span>
          <span class="tool-sr" :class="info.success_rate >= 80 ? 'sr-ok' : 'sr-warn'">
            {{ info.success_rate }}%
          </span>
          <span class="tool-avg">{{ info.avg_ms }}ms</span>
        </div>
      </div>
      <div v-else class="empty-tip">暂无工具调用记录</div>
      <div class="divider" style="margin: var(--space-3-5) 0" />
      <div class="detail-title" style="margin-bottom:var(--space-2-5)"><i class="fas fa-history" /> 最近调用</div>
      <div class="record-list" v-if="toolCalls.length">
        <div v-for="tc in toolCalls" :key="tc.id" class="record-item tool-call-record"
             @click="toggleExpand(tc.id)">
          <div class="record-header">
            <span class="tool-badge" :class="tc.success ? '' : 'tool-badge-fail'">{{ tc.tool_name }}</span>
            <span class="record-rt" :class="tc.success ? 'rt-ok' : 'rt-fail'">
              {{ tc.success ? '✓' : '✗' }} {{ tc.duration_ms }}ms
            </span>
            <span class="record-time">{{ formatISOShort(tc.timestamp) }}</span>
          </div>
          <div v-if="expandedIds.has(tc.id)" class="record-response">
            参数: {{ JSON.stringify(tc.args) }}<br>结果: {{ tc.result_brief }}
          </div>
        </div>
      </div>
      <div v-else class="empty-tip">暂无记录</div>
    </div>

    <!-- 对话记录 -->
    <div class="detail-card records-card">
      <div class="detail-title"><i class="fas fa-list" /> 对话记录</div>
      <div v-if="!records.length" class="empty-tip">暂无记录</div>
      <div v-else class="record-list">
        <div v-for="r in records" :key="r.id" class="record-item"
             @click="toggleExpand(r.id)">
          <div class="record-header">
            <span v-if="r.rating" class="record-rating" :class="r.rating">
              {{ r.rating === 'like' ? '👍' : '👎' }}
            </span>
            <span class="record-msg">{{ r.message || '（无问题）' }}</span>
            <span class="record-time">{{ formatISOShort(r.created_at) }}</span>
            <span v-if="r.response_time" class="record-rt">{{ r.response_time.toFixed(1) }}s</span>
            <i :class="expandedIds.has(r.id) ? 'fas fa-chevron-up' : 'fas fa-chevron-down'"
               style="font-size:var(--text-xs);color:var(--color-text-muted);margin-left:auto" />
          </div>
          <div v-if="expandedIds.has(r.id)" class="record-response">{{ r.response }}</div>
          <div v-else class="record-response collapsed">{{ r.response?.slice(0, 80) }}{{ r.response?.length > 80 ? '…' : '' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getAnalyticsStats, getAnalyticsRecords, getSkillLogs, getSkillStats, getToolCalls, getToolStats, getUsageStats } from '@/services/api'
import { formatISOShort } from '@/utils/date'


const authStore    = useAuthStore()
const username     = computed(() => authStore.username || 'admin')
const loading      = ref(false)
const stats        = ref(null)
const usage        = ref(null)
const records      = ref([])
const ratingFilter = ref('')

// 自定义过滤下拉
const filterRef  = ref(null)
const filterOpen = ref(false)
const filterOptions = [
  { value: '', label: '全部记录' },
  { value: 'like', label: '👍 点赞' },
  { value: 'dislike', label: '👎 点踩' },
]
const filterLabels = { '': '全部记录', like: '👍 点赞', dislike: '👎 点踩' }

const selectFilter = (value) => {
  ratingFilter.value = value
  filterOpen.value = false
  loadRecords()
}

const handleClickOutside = (e) => {
  if (filterRef.value && !filterRef.value.contains(e.target)) filterOpen.value = false
}
onMounted(() => document.addEventListener('click', handleClickOutside))
onUnmounted(() => document.removeEventListener('click', handleClickOutside))

// ── 统计计算 ──────────────────────────────────────────────
const maxDailyCount = computed(() =>
  Math.max(1, ...(stats.value?.daily_counts?.map(d => d.count) || [0]))
)
const maxToolCount = computed(() =>
  Math.max(1, ...Object.values(stats.value?.tool_usage || {0: 0}))
)
const maxSkillCount = computed(() =>
  Math.max(1, ...Object.values(stats.value?.skill_usage || {0: 0}))
)
const maxModelTokens = computed(() => {
  const vals = Object.values(usage.value?.by_model || {}).map(m => (m.input_tokens || 0) + (m.output_tokens || 0))
  return vals.length ? Math.max(1, ...vals) : 1
})
const maxDailyCost = computed(() =>
  Math.max(0.01, ...(usage.value?.daily?.map(d => d.cost_cny) || [0]))
)

const fmtNumber = (v) => {
  const n = Number(v || 0)
  if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M'
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K'
  return String(n)
}
const fmtCny = (v) => Number(v || 0).toFixed(2)

const barHeight      = (val, max) => Math.max(8, Math.round(val / max * 100))
const rankBarWidth   = (val, max) => Math.max(4, Math.round(val / max * 100))

const rateColorClass = computed(() => {
  const r = stats.value?.like_rate ?? 0
  if (r >= 80) return 'rate-good'
  if (r >= 50) return 'rate-warn'
  return 'rate-bad'
})

const responseTimeColor = computed(() => {
  const t = stats.value?.avg_response_time ?? 0
  if (t < 10) return 'var(--color-success)'
  if (t < 60) return 'var(--color-warn)'
  return 'var(--color-danger)'
})

// ── 响应时间折线图 ────────────────────────────────────────
const rtPointList = computed(() => {
  const trend = stats.value?.response_time_trend || []
  if (trend.length < 2) return []
  const W = 400, H = 100, pad = 10
  const vals = trend.map(r => r.value)
  const min  = Math.min(...vals)
  const max  = Math.max(...vals) || 1
  return trend.map((r, i) => ({
    x: pad + (i / (trend.length - 1)) * (W - pad * 2),
    y: H - pad - ((r.value - min) / (max - min || 1)) * (H - pad * 2)
  }))
})
const rtPoints = computed(() =>
  rtPointList.value.map(p => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
)

// ── 数据加载 ──────────────────────────────────────────────
const loadStats = async () => {
  const res = await getAnalyticsStats(username.value)
  if (res?.success) stats.value = res.stats
}

const loadUsage = async () => {
  const res = await getUsageStats(username.value)
  if (res?.success) usage.value = res
}

const loadRecords = async () => {
  const res = await getAnalyticsRecords(
    username.value, 50, ratingFilter.value || null
  )
  if (res?.success) records.value = res.records
}

const load = async () => {
  loading.value = true
  try {
    await Promise.all([loadStats(), loadRecords(), loadSkillData(), loadToolData(), loadUsage()])
  } finally {
    loading.value = false
  }
}

const skillStats   = ref(null)
const skillRecords = ref([])
const toolStats    = ref(null)
const toolCalls    = ref([])
const expandedIds  = ref(new Set())
const toggleExpand = (id) => {
  if (expandedIds.value.has(id)) expandedIds.value.delete(id)
  else expandedIds.value.add(id)
  expandedIds.value = new Set(expandedIds.value) // 触发响应式更新
}
const maxToolCallCount = computed(() => {
  const counts = Object.values(toolStats.value?.by_tool || {}).map(v => v?.count ?? 0)
  return counts.length ? Math.max(1, ...counts) : 1
})

const loadSkillData = async () => {
  const [statsRes, logsRes] = await Promise.all([
    getSkillStats(username.value),
    getSkillLogs(username.value, 20)
  ])
  if (statsRes?.success)  skillStats.value   = statsRes.stats
  if (logsRes?.success)   skillRecords.value = logsRes.records
}

const loadToolData = async () => {
  const [statsRes, callsRes] = await Promise.all([
    getToolStats(),
    getToolCalls(30)
  ])
  if (statsRes?.success)  toolStats.value = statsRes.stats
  if (callsRes?.success)  toolCalls.value = callsRes.records
}

onMounted(load)
</script>

<style scoped>
.stats-view {
  padding: var(--space-4-5); height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: var(--space-3-5); background: var(--color-surface-sunken);
}
.toolbar { display: flex; align-items: center; justify-content: space-between; }
.toolbar-right { display: flex; align-items: center; gap: var(--space-2); }
/* 自定义过滤下拉 */
.filter-dropdown { position: relative; }
.filter-btn {
  display: flex; align-items: center; gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  background: var(--color-surface); font-size: var(--text-sm); color: var(--color-text); cursor: pointer;
  white-space: nowrap; transition: border-color var(--duration-fast);
}
.filter-btn:hover, .filter-btn:focus { border-color: var(--color-primary); outline: none; }
.filter-btn i { font-size: var(--text-2xs); color: var(--color-text-secondary); transition: transform var(--duration-base); }
.filter-menu {
  position: absolute; top: calc(100% + 4px); right: 0;
  min-width: 110px; background: var(--color-surface);
  border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  box-shadow: var(--shadow-raised); z-index: 100; overflow: hidden;
}
.filter-item {
  padding: var(--space-2) var(--space-3-5); font-size: var(--text-sm); color: var(--color-text); cursor: pointer;
  transition: background var(--duration-fast);
}
.filter-item:hover { background: var(--color-primary-soft); }
.filter-item.active { color: var(--color-primary); background: var(--color-primary-soft); font-weight: var(--weight-medium); }
.refresh-btn {
  display: flex; align-items: center; gap: var(--space-1-5); padding: var(--space-1-5) var(--space-3-5);
  border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: var(--color-surface);
  font-size: var(--text-sm); color: var(--color-text); cursor: pointer;
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 概览 ── */
.overview-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: var(--space-3); }
.ov-card {
  background: var(--color-surface); border-radius: var(--radius-md); border: 0.5px solid var(--color-border);
  padding: var(--space-4); text-align: center;
  border-top: 2px solid var(--color-primary);

  box-shadow: var(--shadow-card);}
.ov-val   { font-size: 1.8rem; font-weight: var(--weight-semibold); color: var(--color-text); }
.ov-label { font-size: var(--text-sm-2); color: var(--color-text-secondary); margin-top: var(--space-1); }
.ov-card.like    .ov-val { color: var(--color-success); }
.ov-card.dislike .ov-val { color: var(--color-danger); }
.ov-card.rate    .ov-val { color: var(--color-primary); }
.ov-card.rate .ov-val.rate-good { color: var(--color-success); }
.ov-card.rate .ov-val.rate-warn { color: var(--color-warn); }
.ov-card.rate .ov-val.rate-bad  { color: var(--color-danger); }
.ov-card.time    .ov-val { color: var(--color-warn); }
.ov-card.cost    .ov-val { color: var(--color-danger); }
.ov-card.calls   .ov-val { color: var(--color-primary); }
.ov-val.rate-good { color: var(--color-success); }
.ov-val.rate-bad  { color: var(--color-danger); }

/* ── 图表行 ── */
.charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.chart-card {
  background: var(--color-surface); border-radius: var(--radius-md); border: 0.5px solid var(--color-border); padding: var(--space-4);

  box-shadow: var(--shadow-card);}
.chart-title {
  font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text);
  margin-bottom: var(--space-3); display: flex; align-items: center; gap: var(--space-1-5);
}
.chart-title i { color: var(--color-primary); }

/* 柱状图 */
.bar-chart { display: flex; align-items: flex-end; gap: var(--space-1); height: 100px; }
.bar-item  { flex: 1; display: flex; flex-direction: column; align-items: center; gap: var(--space-1); }
.bar-wrap  { flex: 1; width: 100%; display: flex; align-items: flex-end; }
.bar       { width: 100%; background: var(--color-primary); border-radius: var(--radius-xs) var(--radius-xs) 0 0; min-height: 14px; transition: height var(--duration-slow); }
.bar-label { font-size: var(--text-2xs); color: var(--color-text-muted); white-space: nowrap; }

/* 折线图 */
.line-chart { width: 100%; height: 120px; }
.rt-range   { display: flex; justify-content: space-between; font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-1); }

/* ── 详情行 ── */
.detail-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.detail-card {
  background: var(--color-surface); border-radius: var(--radius-md); border: 0.5px solid var(--color-border); padding: var(--space-4);

  box-shadow: var(--shadow-card);}
.records-card { grid-column: 1 / -1; }
.detail-title {
  font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text);
  margin-bottom: var(--space-3-5); display: flex; align-items: center; gap: var(--space-1-5);
}
.detail-title i { color: var(--color-primary); }
.empty-tip { color: var(--color-text-muted); font-size: var(--text-sm); padding: var(--space-2-5) 0; }

/* 排行 */
.rank-list { display: flex; flex-direction: column; gap: var(--space-2); }
.rank-item { display: flex; align-items: center; gap: var(--space-2); }
.rank-name { font-size: var(--text-sm-2); color: var(--color-text); min-width: 120px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rank-bar-wrap { flex: 1; height: 6px; background: var(--color-border); border-radius: var(--radius-xs); overflow: hidden; }
.rank-bar { height: 100%; background: var(--color-primary); border-radius: var(--radius-xs); transition: width var(--duration-slow); }
.rank-bar.skill-bar { background: var(--color-warn); }
.rank-bar.cost-bar { background: var(--color-warn); }
.rank-count { font-size: var(--text-xs); color: var(--color-text-secondary); min-width: 24px; text-align: right; }

/* 记录列表 */
.record-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 400px; overflow-y: auto; }
.record-item {
  padding: var(--space-2-5) var(--space-3); background: var(--color-surface-sunken); border-radius: var(--radius-sm);
  border-left: 3px solid var(--color-border);
}
.record-item:has(.record-rating.like)    { border-left-color: var(--color-success); }
.record-item:has(.record-rating.dislike) { border-left-color: var(--color-danger); }
.record-header { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-1); }
.record-rating { font-size: var(--text-sm); }
.record-msg    { flex: 1; font-size: var(--text-sm); color: var(--color-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.record-time   { font-size: var(--text-xs); color: var(--color-text-muted); white-space: nowrap; }
.record-rt     { font-size: var(--text-xs); color: var(--color-warn); }
.record-item { cursor: pointer; }
.record-item:hover { background: var(--color-surface-sunken); }
.record-response { font-size: var(--text-xs); color: var(--color-text-secondary); line-height: var(--leading-normal); }
.record-response.collapsed { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

@media (max-width: 768px) {
  .overview-row { grid-template-columns: repeat(3, 1fr); }
  .charts-row   { grid-template-columns: 1fr; }
  .detail-row   { grid-template-columns: 1fr; }
}

.title-badge {
  font-size: var(--text-xs); font-weight: var(--weight-normal);
  background: var(--color-primary-soft); color: var(--color-primary);
  padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-sm); margin-left: var(--space-2);
}
.skill-record { border-left-color: var(--color-primary); }
.skill-badge {
  font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2);
  background: var(--color-primary-soft); color: var(--color-primary);
  border-radius: var(--radius-sm); white-space: nowrap; flex-shrink: 0;
}
.divider { border: none; border-top: 0.5px solid var(--color-border); }

/* 工具调用排行 */
.rank-bar-warn { background: var(--color-warn) !important; }
.tool-sr  { font-size: var(--text-xs); min-width: 36px; text-align: right; }
.sr-ok    { color: var(--color-success); }
.sr-warn  { color: var(--color-warn); }
.tool-avg { font-size: var(--text-xs); color: var(--color-text-muted); min-width: 44px; text-align: right; }
.tool-call-record { border-left-color: var(--color-violet); }
.tool-badge {
  font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2);
  background: var(--color-violet-soft); color: var(--color-primary);
  border-radius: var(--radius-sm); white-space: nowrap; flex-shrink: 0;
}
.tool-badge-fail { background: var(--color-danger-soft); color: var(--color-danger); }
.rt-ok   { color: var(--color-success); font-size: var(--text-xs); }
.rt-fail { color: var(--color-danger); font-size: var(--text-xs); }
</style>
