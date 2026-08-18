<template>
  <div class="tasks-view">
    <!-- 统计卡片 -->
    <div class="stats-row">
      <div class="stat-card accent-primary">
        <div class="stat-num">{{ stats.total_tasks ?? '-' }}</div>
        <div class="stat-label">全部任务</div>
      </div>
      <div class="stat-card accent-warn">
        <div class="stat-num pending-color">{{ statusCount('pending') }}</div>
        <div class="stat-label">待执行</div>
      </div>
      <div class="stat-card accent-success">
        <div class="stat-num done-color">{{ statusCount('completed') }}</div>
        <div class="stat-label">已完成</div>
      </div>
      <div class="stat-card accent-danger">
        <div class="stat-num fail-color">{{ statusCount('failed') }}</div>
        <div class="stat-label">失败</div>
      </div>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="filter-tabs">
        <button
          v-for="f in filters"
          :key="f.value"
          class="filter-btn"
          :class="{ active: activeFilter === f.value }"
          @click="activeFilter = f.value; load()"
        >{{ f.label }}</button>
      </div>
      <div class="toolbar-right">
        <button class="refresh-btn" :class="{ spinning: loading }" @click="load">
          <i class="fas fa-sync-alt" />
        </button>
        <button class="create-btn" @click="showCreate = true; loadActions()">
          <i class="fas fa-plus" /> 新建任务
        </button>
      </div>
    </div>

    <!-- 任务列表 -->
    <div v-if="tasks.length > 0" class="task-list">
      <TaskCard
        v-for="task in tasks"
        :key="task.id"
        :task="task"
        :triggering-ids="triggeringIds"
        :now="now"
        @run="runNow"
        @edit="openEdit"
        @cancel="cancel"
        @remove="remove"
      />
    </div>

    <!-- 空状态 -->
    <div v-else-if="!loading" class="empty-state">
      <i class="fas fa-tasks empty-icon" />
      <p class="empty-title">暂无任务</p>
      <p class="empty-sub">点击右上角「新建任务」创建第一个定时任务</p>
    </div>

    <div v-if="loading" class="loading-state">
      <i class="fas fa-circle-notch fa-spin" /><span>加载中...</span>
    </div>

    <!-- 编辑任务弹窗 -->
    <div v-if="showEdit" class="modal-overlay" @click.self="showEdit = false">
      <div class="modal-box">
        <div class="modal-header">
          <span class="modal-title">编辑任务</span>
          <button class="modal-close" @click="showEdit = false">
            <i class="fas fa-times" />
          </button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label>任务名称 <span class="required">*</span></label>
            <input v-model="editForm.name" placeholder="任务名称" />
          </div>
          <div class="form-row">
            <label>描述</label>
            <input v-model="editForm.description" placeholder="可选" />
          </div>

          <!-- interval：间隔秒数 -->
          <div class="form-row" v-if="editForm.schedule_type === 'interval'">
            <label>循环间隔</label>
            <div class="quick-btns">
              <button type="button" v-for="q in quickIntervals" :key="q.s"
                class="quick-btn" :class="{ active: editForm.interval_seconds === q.s }"
                @click="editForm.interval_seconds = q.s">{{ q.label }}</button>
            </div>
            <input v-model.number="editForm.interval_seconds" type="number" min="1" placeholder="自定义秒数" />
          </div>

          <!-- delay：剩余延迟（重设后从现在开始重新计时） -->
          <div class="form-row" v-else-if="editForm.schedule_type === 'delay'">
            <label>延迟时间 <span class="hint-text">（重设后从现在起重新计时）</span></label>
            <div class="quick-btns">
              <button type="button" v-for="q in quickDelays" :key="q.s"
                class="quick-btn" :class="{ active: editForm.delay_seconds === q.s }"
                @click="editForm.delay_seconds = q.s">{{ q.label }}</button>
            </div>
            <input v-model.number="editForm.delay_seconds" type="number" min="1" placeholder="自定义秒数" />
          </div>

          <!-- cron -->
          <div class="form-row" v-else-if="editForm.schedule_type === 'cron'">
            <label>Cron 表达式</label>
            <input v-model="editForm.cron_expression" placeholder="如 0 9 * * 1-5"
              :class="{ 'input-error': editForm.cron_expression && !cronEditHint.valid }" />
            <span v-if="editForm.cron_expression && cronEditHint.desc"
              :class="cronEditHint.valid ? 'cron-ok' : 'cron-err'">
              {{ cronEditHint.valid ? '✓ ' : '✗ ' }}{{ cronEditHint.desc }}
            </span>
            <span v-else class="form-hint">格式：分 时 日 月 星期 &nbsp;|&nbsp; 示例：<code>0 9 * * 1-5</code> 工作日9点 &nbsp;<code>*/30 * * * *</code> 每30分钟</span>
          </div>

          <!-- 执行动作类型 -->
          <div class="form-row">
            <label>执行动作</label>
            <select v-model="editForm.action">
              <option value="log">💬 定时提醒（发送固定文字）</option>
              <option value="llm_generate">🤖 AI 生成（周期调用大模型）</option>
              <option v-for="a in actions.filter(x => !['log','llm_generate'].includes(x))" :key="a" :value="a">{{ a }}</option>
            </select>
          </div>

          <!-- log action 的消息内容 -->
          <div class="form-row" v-if="editForm.action === 'log'">
            <label>提醒内容</label>
            <input v-model="editForm.message" placeholder="要发送的提醒消息" />
          </div>

          <!-- 通知方式（log action） -->
          <div class="form-row" v-if="editForm.action === 'log'">
            <label>通知方式</label>
            <div class="role-selector">
              <label class="role-option" :class="{ active: editForm.role === 'assistant' }">
                <input type="radio" v-model="editForm.role" value="assistant" />
                <i class="fas fa-robot" /> AI 助手气泡
              </label>
              <label class="role-option" :class="{ active: editForm.role === 'system' }">
                <input type="radio" v-model="editForm.role" value="system" />
                <i class="fas fa-info-circle" /> 系统通知条
              </label>
            </div>
          </div>

          <!-- llm_generate action：修改提示词 -->
          <div class="form-row" v-if="editForm.action === 'llm_generate'">
            <label>生成提示词 <span class="hint-text">（每次触发时发给 AI 的 prompt）</span></label>
            <textarea v-model="editForm.prompt" class="form-textarea-sm" rows="3"
              placeholder="如：帮我写一段今日早报、给我讲一个小故事..." />
          </div>

          <div class="schedule-type-hint">
            <i class="fas fa-lock" />
            触发类型（{{ scheduleTypeLabel(editForm.schedule_type) }}）不可更改，如需切换请删除后重建
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showEdit = false">取消</button>
          <button class="btn-confirm" :disabled="!editForm.name || saving" @click="doSave">
            <i v-if="saving" class="fas fa-circle-notch fa-spin" />
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 新建任务弹窗 -->
    <div v-if="showCreate" class="modal-overlay" @click.self="showCreate = false">
      <div class="modal-box">
        <div class="modal-header">
          <span class="modal-title">新建任务</span>
          <button class="modal-close" @click="showCreate = false">
            <i class="fas fa-times" />
          </button>
        </div>

        <div class="modal-body">
          <div class="form-row">
            <label>任务名称 <span class="required">*</span></label>
            <input v-model="form.name" placeholder="例：每日提醒" />
          </div>
          <div class="form-row">
            <label>描述</label>
            <input v-model="form.description" placeholder="可选" />
          </div>
          <div class="form-row">
            <label>触发方式</label>
            <select v-model="form.schedule_type">
              <option value="delay">延迟执行（N 秒后执行一次）</option>
              <option value="interval">定时循环（每隔 N 秒执行）</option>
              <option value="datetime">指定时间执行</option>
              <option value="cron">Cron 表达式（定时规则）</option>
            </select>
          </div>
          <div class="form-row" v-if="form.schedule_type === 'delay'">
            <label>延迟时间</label>
            <div class="quick-btns">
              <button type="button" v-for="q in quickDelays" :key="q.s"
                class="quick-btn" :class="{ active: form.delay_seconds === q.s }"
                @click="form.delay_seconds = q.s">{{ q.label }}</button>
            </div>
            <input v-model.number="form.delay_seconds" type="number" min="1" placeholder="自定义秒数" />
          </div>
          <div class="form-row" v-if="form.schedule_type === 'interval'">
            <label>循环间隔</label>
            <div class="quick-btns">
              <button type="button" v-for="q in quickIntervals" :key="q.s"
                class="quick-btn" :class="{ active: form.interval_seconds === q.s }"
                @click="form.interval_seconds = q.s">{{ q.label }}</button>
            </div>
            <input v-model.number="form.interval_seconds" type="number" min="1" placeholder="自定义秒数" />
          </div>
          <div class="form-row" v-if="form.schedule_type === 'datetime'">
            <label>执行时间</label>
            <input v-model="form.run_at" type="datetime-local" />
          </div>
          <div class="form-row" v-if="form.schedule_type === 'cron'">
            <label>Cron 表达式</label>
            <input v-model="form.cron_expr" placeholder="如 0 9 * * 1-5（工作日早9点）"
              :class="{ 'input-error': form.cron_expr && !cronHint.valid }" />
            <span v-if="form.cron_expr && cronHint.desc"
              :class="cronHint.valid ? 'cron-ok' : 'cron-err'">
              {{ cronHint.valid ? '✓ ' : '✗ ' }}{{ cronHint.desc }}
            </span>
            <span v-else class="form-hint">格式：分 时 日 月 星期 &nbsp;|&nbsp; 示例：<code>0 9 * * 1-5</code> 工作日9点 &nbsp;<code>*/30 * * * *</code> 每30分钟</span>
          </div>
          <div class="form-row">
            <label>执行动作</label>
            <select v-model="form.action">
              <option value="log">💬 定时提醒（发送固定文字）</option>
              <option value="llm_generate">🤖 AI 生成（周期调用大模型）</option>
              <option v-for="a in actions.filter(x => !['log','llm_generate'].includes(x))" :key="a" :value="a">{{ a }}</option>
            </select>
          </div>
          <!-- log action：固定文字提醒 -->
          <div class="form-row" v-if="form.action === 'log'">
            <label>提醒内容</label>
            <input v-model="form.message" placeholder="输入提醒内容，任务触发时发送" />
          </div>
          <div class="form-row" v-if="form.action === 'log'">
            <label>通知方式</label>
            <div class="role-selector">
              <label class="role-option" :class="{ active: form.role === 'assistant' }">
                <input type="radio" v-model="form.role" value="assistant" />
                <i class="fas fa-robot" /> AI 助手气泡
              </label>
              <label class="role-option" :class="{ active: form.role === 'system' }">
                <input type="radio" v-model="form.role" value="system" />
                <i class="fas fa-info-circle" /> 系统通知条
              </label>
            </div>
          </div>
          <!-- llm_generate action：调用大模型 -->
          <div class="form-row" v-if="form.action === 'llm_generate'">
            <label>生成提示词 <span class="hint-text">（每次触发时发给 AI 的 prompt）</span></label>
            <textarea v-model="form.prompt" class="form-textarea-sm" rows="3"
              placeholder="如：帮我写一段今日早报、给我讲一个小故事、用一句话总结今天的天气..." />
          </div>
          <div v-if="form.action === 'llm_generate'" class="action-tip">
            <i class="fas fa-info-circle" />
            任务触发时将调用大模型，生成内容实时推送到聊天窗口。
            注意：生成耗时与模型相关（CPU 推理约 60-300 秒）。
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn-cancel" @click="showCreate = false">取消</button>
          <button class="btn-confirm" :disabled="!form.name || creating" @click="doCreate">
            <i v-if="creating" class="fas fa-circle-notch fa-spin" />
            {{ creating ? '创建中...' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useConfirmDialogStore } from '@/stores/confirmDialog'
import {
  getTasksList, createTask, updateTask, deleteTask,
  cancelTask, executeTaskNow, getTaskStats, getTaskActions
} from '@/services/api'
import TaskCard from '@/components/tasks/TaskCard.vue'

const confirmDialog = useConfirmDialogStore()
const tasks      = ref([])
const stats      = ref({})
const actions     = ref([])
const loading     = ref(false)
const creating    = ref(false)
const saving      = ref(false)
const showCreate  = ref(false)
const showEdit    = ref(false)
const editingTask = ref(null)
const activeFilter  = ref('all')
const triggeringIds = ref(new Set())  // 正在手动触发的任务 ID 集合
const now        = ref(Date.now())

const filters = [
  { label: '全部',   value: 'all' },
  { label: '待执行', value: 'pending' },
  { label: '执行中', value: 'running' },
  { label: '已完成', value: 'completed' },
  { label: '失败',   value: 'failed' },
  { label: '已取消', value: 'cancelled' },
]

const quickDelays    = [
  { label: '1分钟', s: 60 }, { label: '5分钟', s: 300 },
  { label: '30分钟', s: 1800 }, { label: '1小时', s: 3600 },
]
const quickIntervals = [
  { label: '30秒', s: 30 }, { label: '1分钟', s: 60 },
  { label: '5分钟', s: 300 }, { label: '10分钟', s: 600 },
  { label: '30分钟', s: 1800 }, { label: '1小时', s: 3600 },
  { label: '6小时', s: 21600 }, { label: '1天', s: 86400 },
]

const emptyForm = () => ({
  name: '', description: '', action: 'llm_generate',
  schedule_type: 'interval', delay_seconds: 60,
  interval_seconds: 300, cron_expr: '0 9 * * 1-5', run_at: '', message: '',
  role: 'assistant', prompt: '',
})
const form = ref(emptyForm())

const emptyEditForm = () => ({
  name: '', description: '', action: 'log', schedule_type: 'interval',
  interval_seconds: 300, delay_seconds: 60, cron_expression: '', message: '', role: 'assistant', prompt: '',
})
const editForm = ref(emptyEditForm())

const scheduleTypeLabel = (t) => ({
  interval: '定时循环', delay: '延迟执行', datetime: '指定时间', cron: 'Cron 表达式', immediate: '立即执行'
}[t] || t)

// ── Cron 表达式校验与中文解释 ─────────────────────────────
const _parseCron = (expr) => {
  if (!expr?.trim()) return { valid: false, desc: '' }
  const parts = expr.trim().split(/\s+/)
  if (parts.length !== 5) return { valid: false, desc: '需要 5 个字段：分 时 日 月 星期' }
  const [min, hour, day, month, weekday] = parts
  const ok = s => /^(\*|\d+(-\d+)?(,\d+(-\d+)?)*|\*\/\d+)$/.test(s)
  for (const p of parts) {
    if (!ok(p)) return { valid: false, desc: `字段格式不合法：${p}` }
  }
  // 数值范围检测
  const inRange = (s, lo, hi) => {
    if (s === '*' || s.startsWith('*/')) return true
    return s.split(',').every(seg => {
      const [a, b] = seg.split('-').map(Number)
      return a >= lo && a <= hi && (b === undefined || (b >= lo && b <= hi))
    })
  }
  if (!inRange(min, 0, 59)) return { valid: false, desc: '分钟范围 0-59' }
  if (!inRange(hour, 0, 23)) return { valid: false, desc: '小时范围 0-23' }
  if (!inRange(day, 1, 31))  return { valid: false, desc: '日期范围 1-31' }
  if (!inRange(month, 1, 12)) return { valid: false, desc: '月份范围 1-12' }
  if (!inRange(weekday, 0, 7)) return { valid: false, desc: '星期范围 0-7 (0/7=周日)' }

  // 生成中文描述
  const wdNames = ['周日','周一','周二','周三','周四','周五','周六','周日']
  const stepOf  = s => s.startsWith('*/') ? parseInt(s.slice(2)) : null
  const numOf   = s => /^\d+$/.test(s) ? parseInt(s) : null
  const pad     = n => String(n).padStart(2, '0')

  // 时间部分
  let timeDesc = ''
  const hStep = stepOf(hour), mStep = stepOf(min)
  const hNum = numOf(hour), mNum = numOf(min)
  if (min === '*' && hour === '*')        timeDesc = '每分钟'
  else if (mStep && hour === '*')         timeDesc = `每 ${mStep} 分钟`
  else if (mNum === 0 && hStep)          timeDesc = `每 ${hStep} 小时整点`
  else if (hNum !== null && mNum !== null) timeDesc = `${hNum}:${pad(mNum)}`
  else if (hNum !== null && min === '0') timeDesc = `${hNum}:00`
  else                                   timeDesc = `${min}分 ${hour}时`

  // 日期/星期部分
  let dateDesc = ''
  if (weekday !== '*') {
    if (weekday === '1-5')           dateDesc = '工作日'
    else if (['0,6','6,0','6,7'].includes(weekday)) dateDesc = '周末'
    else dateDesc = weekday.split(',').map(w => wdNames[parseInt(w)] || w).join('、')
  } else if (day !== '*') {
    const d = numOf(day)
    dateDesc = d !== null ? `每月${d}日` : `每月${day}日`
  }

  // 月份部分
  let monthDesc = ''
  if (month !== '*') {
    const m = numOf(month)
    monthDesc = m !== null ? `${m}月` : `${month}月`
  }

  const parts2 = [monthDesc, dateDesc, timeDesc].filter(Boolean)
  return { valid: true, desc: parts2.join(' ') || '自定义周期' }
}

const cronHint       = computed(() => _parseCron(form.value.cron_expr))
const cronEditHint   = computed(() => _parseCron(editForm.value.cron_expression))

const statusCount = (s) => tasks.value.filter(t => t.status === s).length

const load = async () => {
  loading.value = true
  try {
    const status = activeFilter.value === 'all' ? undefined : activeFilter.value
    const [listData, statsData] = await Promise.all([
      getTasksList(status, 100),
      getTaskStats()
    ])
    tasks.value = listData?.tasks || []
    stats.value = statsData || {}
  } finally {
    loading.value = false
  }
}

const loadActions = async () => {
  const data = await getTaskActions()
  actions.value = data?.actions || ['log', 'system_info', 'test']
}

const doCreate = async () => {
  if (!form.value.name) return
  if (form.value.schedule_type === 'cron' && !cronHint.value.valid) {
    ElMessage({ message: `Cron 表达式错误：${cronHint.value.desc}`, type: 'error', duration: 3000 })
    return
  }
  creating.value = true
  try {
    const payload = {
      name:             form.value.name,
      description:      form.value.description,
      action:           form.value.action,
      schedule_type:    form.value.schedule_type,
      delay_seconds:    form.value.delay_seconds,
      interval_seconds: form.value.interval_seconds,
      cron_expr:        form.value.schedule_type === 'cron' ? form.value.cron_expr : null,
      run_at:           form.value.run_at
          ? new Date(form.value.run_at).toISOString() : null,
      args: form.value.action === 'log'
          ? { message: `⏰ ${form.value.message || form.value.name}`, role: form.value.role }
          : form.value.action === 'llm_generate'
          ? { prompt: form.value.prompt || form.value.name, role: 'assistant' }
          : {}
    }
    const result = await createTask(payload)
    if (result?.success) {
      showCreate.value = false
      form.value = emptyForm()
      ElMessage({ message: '任务创建成功', type: 'success', duration: 2000 })
      await load()
    } else {
      ElMessage({ message: `创建失败: ${result?.message || '未知错误'}`, type: 'error', duration: 4000 })
    }
  } finally {
    creating.value = false
  }
}

const runNow = async (id) => {
  if (triggeringIds.value.has(id)) return   // 防止双击重复触发
  triggeringIds.value = new Set([...triggeringIds.value, id])
  _fastPoll()   // 立即开始快轮询，不等 HTTP 响应（后端已 fire-and-forget）
  try {
    const result = await executeTaskNow(id)
    if (result?.success === false) {
      ElMessage({ message: `触发失败: ${result.message || '未知错误'}`, type: 'error', duration: 4000 })
    } else {
      ElMessage({ message: '任务已触发，后台执行中', type: 'success', duration: 2000 })
    }
  } finally {
    // 短暂延迟后移除触发状态（让用户看到 spinner 至少 1 秒）
    setTimeout(() => {
      const s = new Set(triggeringIds.value)
      s.delete(id)
      triggeringIds.value = s
    }, 1000)
  }
}

const cancel = async (id) => {
  const task = tasks.value.find(t => t.id === id)
  const isRunning = task?.status === 'running'
  const msg = isRunning
    ? '任务正在执行中，停止后当前本次执行仍会继续到结束，但之后不会再被调度。确认停止？'
    : '确定取消该任务？任务将标记为已取消，不再自动调度。'
  const ok = await confirmDialog.confirm(msg, {
    title: isRunning ? '停止任务' : '取消任务',
    confirmText: isRunning ? '确认停止' : '确认取消',
    danger: true,
  })
  if (!ok) return
  const result = await cancelTask(id)
  if (!result || result?.success === false) {
    ElMessage({ message: `取消失败: ${result?.message || '请求失败'}`, type: 'error', duration: 3000 })
  } else {
    ElMessage({ message: isRunning ? '已标记停止，本次执行结束后生效' : '任务已取消', type: 'success', duration: 2000 })
  }
  await load()
}

const remove = async (id) => {
  const ok = await confirmDialog.confirm('确定删除该任务？此操作不可恢复。', {
    title: '删除任务', confirmText: '删除', danger: true,
  })
  if (!ok) return
  const result = await deleteTask(id)
  if (!result || result?.success === false) {
    ElMessage({ message: `删除失败: ${result?.message || '请求失败'}`, type: 'error', duration: 3000 })
  } else {
    ElMessage({ message: '任务已删除', type: 'success', duration: 2000 })
  }
  await load()
}

const openEdit = (task) => {
  editingTask.value = task
  editForm.value = {
    name:             task.name || '',
    description:      task.description || '',
    action:           task.action || 'log',
    schedule_type:    task.schedule_type || 'interval',
    interval_seconds: task.interval_seconds || 300,
    delay_seconds:    task.delay_seconds || 60,
    cron_expression:  task.cron_expression || '',
    message:          (task.args?.message || '')
                        .replace(/^[⏰🔔]\s*(周期?提醒[:：]\s*)?/, '')
                        .replace(/^提醒[:：]\s*/, ''),
    role:             task.args?.role || 'assistant',
    prompt:           task.args?.prompt || '',
  }
  showEdit.value = true
}

const doSave = async () => {
  if (!editForm.value.name || !editingTask.value) return
  if (editForm.value.schedule_type === 'cron' && !cronEditHint.value.valid) {
    ElMessage({ message: `Cron 表达式错误：${cronEditHint.value.desc}`, type: 'error', duration: 3000 })
    return
  }
  saving.value = true
  try {
    const payload = {
      name:        editForm.value.name,
      description: editForm.value.description,
      action:      editForm.value.action,
    }
    // 调度参数（按 schedule_type 只传对应字段）
    if (editForm.value.schedule_type === 'interval') {
      payload.interval_seconds = editForm.value.interval_seconds
    } else if (editForm.value.schedule_type === 'delay') {
      payload.delay_seconds = editForm.value.delay_seconds
    } else if (editForm.value.schedule_type === 'cron') {
      payload.cron_expression = editForm.value.cron_expression
    }
    // 按 action 类型整体替换 args（切换类型时确保旧 args 不残留）
    if (editForm.value.action === 'log') {
      const msg = editForm.value.message.trim()
      payload.args = { message: msg ? `⏰ ${msg}` : `⏰ ${editForm.value.name}`, role: editForm.value.role }
    } else if (editForm.value.action === 'llm_generate') {
      const prompt = editForm.value.prompt.trim()
      payload.args = { prompt: prompt || editForm.value.name, role: 'assistant' }
    }
    const result = await updateTask(editingTask.value.id, payload)
    if (result?.success) {
      showEdit.value = false
      ElMessage({ message: '任务已更新', type: 'success', duration: 2000 })
      await load()
    } else {
      ElMessage({ message: `更新失败: ${result?.message || '未知错误'}`, type: 'error', duration: 4000 })
    }
  } finally {
    saving.value = false
  }
}

// ── 浏览器通知（WANT-009）────────────────────────────────
const requestNotificationPermission = async () => {
  if (!('Notification' in window)) return
  if (Notification.permission === 'default') {
    await Notification.requestPermission()
  }
}

const sendNotification = (title, body) => {
  if (!('Notification' in window) || Notification.permission !== 'granted') return
  new Notification(title, { body, icon: '/favicon.ico' })
}

// 轮询时对比任务状态，检测新完成的任务
const prevTaskStates = ref({})

const checkTaskCompletions = (newTasks) => {
  newTasks.forEach(t => {
    const prev = prevTaskStates.value[t.id]
    if (prev && prev !== 'completed' && t.status === 'completed') {
      sendNotification(`任务完成：${t.name}`, t.last_result ? `结果: ${String(t.last_result).slice(0, 80)}` : '任务已执行完毕')
    }
    if (prev && prev !== 'failed' && t.status === 'failed') {
      sendNotification(`任务失败：${t.name}`, t.last_error || '执行失败')
    }
    prevTaskStates.value[t.id] = t.status
  })
}

const loadWithNotify = async () => {
  const status = activeFilter.value === 'all' ? undefined : activeFilter.value
  const [listData, statsData] = await Promise.all([
    getTasksList(status, 100),
    getTaskStats()
  ])
  const newTasks = listData?.tasks || []
  if (Object.keys(prevTaskStates.value).length > 0) {
    checkTaskCompletions(newTasks)
  }
  newTasks.forEach(t => { prevTaskStates.value[t.id] = t.status })
  tasks.value = newTasks
  stats.value = statsData || {}
}

// ── 自适应轮询 ────────────────────────────────────────────
// 有 running 任务时 5 秒轮询，否则 30 秒；runNow 后临时 2 秒快轮询
let timer = null
let clockTimer = null

const _hasActiveTask = () => tasks.value.some(t => t.status === 'running')

const _scheduleNext = () => {
  clearTimeout(timer)
  const delay = _hasActiveTask() ? 5000 : 30000
  timer = setTimeout(async () => {
    await loadWithNotify()
    _scheduleNext()
  }, delay)
}

const _fastPoll = (remaining = 8) => {
  // 点击"运行"后每 2 秒刷新，最多 8 次（16 秒），结束后回自适应
  clearTimeout(timer)
  timer = setTimeout(async () => {
    await loadWithNotify()
    if (remaining > 1) _fastPoll(remaining - 1)
    else _scheduleNext()
  }, 2000)
}

onMounted(async () => {
  await requestNotificationPermission()
  load()
  loadActions()
  _scheduleNext()
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => {
  clearTimeout(timer)
  clearInterval(clockTimer)
})

</script>

<style scoped>
.tasks-view {
  height: 100%;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: #f8f9fa;
}

/* ── 统计 ─────────────────────────────────────────────── */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.stat-card {
  background: white;
  border: 0.5px solid #e8eaed;
  border-radius: 12px;
  padding: 14px;
  text-align: center;
}
.stat-card.accent-primary { border-top: 3px solid #667eea; }
.stat-card.accent-warn    { border-top: 3px solid #f57c00; }
.stat-card.accent-success { border-top: 3px solid #43a047; }
.stat-card.accent-danger  { border-top: 3px solid #ef4444; }
.stat-num      { font-size: 22px; font-weight: 500; color: #333; }
.stat-label    { font-size: 12px; color: #888; margin-top: 4px; }
.pending-color { color: #f57c00; }
.done-color    { color: #2e7d32; }
.fail-color    { color: #c62828; }

/* ── 工具栏 ─────────────────────────────────────────────── */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.filter-tabs { display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none; flex-shrink: 1; min-width: 0; }
.filter-tabs::-webkit-scrollbar { display: none; }
.filter-btn {
  padding: 6px 14px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  font-size: 0.85rem; color: #666; cursor: pointer;
  transition: all 0.2s; white-space: nowrap; flex-shrink: 0;
}
.filter-btn:hover  { border-color: #667eea; color: #667eea; }
.filter-btn.active { background: #667eea; border-color: #667eea; color: white; }
.toolbar-right { display: flex; gap: 8px; }
.refresh-btn {
  padding: 8px 10px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  color: #555; cursor: pointer; transition: all 0.2s;
}
.refresh-btn:hover { border-color: #667eea; color: #667eea; }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.create-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 8px;
  border: none; background: #667eea; color: white;
  font-size: 0.88rem; cursor: pointer; transition: background 0.2s;
}
.create-btn:hover { background: #5a6fd6; }

/* ── 任务卡片 ────────────────────────────────────────────── */
.task-list { display: flex; flex-direction: column; gap: 10px; }
/* ── 空 / 加载 ───────────────────────────────────────────── */
.empty-state {
  display: flex; flex-direction: column;
  align-items: center; gap: 8px;
  padding: 60px 20px; background: white;
  border-radius: 12px; border: 0.5px solid #e8eaed;
}
.empty-icon  { font-size: 2.5rem; color: #ddd; }
.empty-title { font-size: 1rem; font-weight: 500; color: #666; margin: 0; }
.empty-sub   { font-size: 0.85rem; color: #aaa; margin: 0; }
.loading-state {
  display: flex; align-items: center;
  justify-content: center; gap: 10px;
  padding: 40px; color: #888; font-size: 0.9rem;
}

/* ── 弹窗 ────────────────────────────────────────────────── */
.modal-overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center;
  z-index: 200;
}
.modal-box {
  background: white;
  border-radius: 14px;
  width: 440px;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
}
.modal-header {
  display: flex; align-items: center;
  justify-content: space-between;
  padding: 18px 20px 14px;
  border-bottom: 0.5px solid #e8eaed;
}
.modal-title  { font-size: 1rem; font-weight: 500; }
.modal-close  {
  background: none; border: none; color: #aaa;
  cursor: pointer; font-size: 1rem; padding: 4px;
}
.modal-close:hover { color: #333; }
.modal-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 14px; }
.form-row { display: flex; flex-direction: column; gap: 5px; }
.form-row label { font-size: 0.85rem; color: #555; font-weight: 500; }
.form-row input,
.form-row select {
  padding: 8px 12px; border: 1px solid #e0e3e8;
  border-radius: 8px; font-size: 0.88rem; outline: none;
  transition: border-color 0.2s;
}
.form-row input:focus,
.form-row select:focus { border-color: #667eea; }
.form-textarea-sm {
  padding: 8px 12px; border: 1px solid #e0e3e8;
  border-radius: 8px; font-size: 0.88rem; outline: none;
  resize: vertical; font-family: inherit; line-height: 1.5;
  transition: border-color 0.2s; width: 100%; box-sizing: border-box;
}
.form-textarea-sm:focus { border-color: #667eea; }
.action-tip {
  font-size: 0.78rem; color: #888;
  background: #f0f4ff; border-radius: 7px;
  padding: 7px 10px; display: flex; align-items: flex-start; gap: 6px;
}
.action-tip i { color: #667eea; margin-top: 1px; flex-shrink: 0; }
.required { color: #f44336; }
.modal-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 14px 20px 18px;
  border-top: 0.5px solid #e8eaed;
}
.btn-cancel {
  padding: 8px 18px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  color: #555; cursor: pointer; font-size: 0.88rem;
}
.btn-confirm {
  padding: 8px 18px; border-radius: 8px;
  border: none; background: #667eea; color: white;
  cursor: pointer; font-size: 0.88rem;
  display: flex; align-items: center; gap: 6px;
  transition: background 0.2s;
}
.btn-confirm:hover:not(:disabled) { background: #5a6fd6; }
.btn-confirm:disabled { opacity: 0.5; cursor: not-allowed; }

.quick-btns { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 6px; }
.quick-btn {
  padding: 4px 10px; border-radius: 6px;
  border: 1px solid #e0e3e8; background: white;
  font-size: 0.8rem; color: #666; cursor: pointer; transition: all 0.15s;
}
.quick-btn:hover  { border-color: #667eea; color: #667eea; }
.quick-btn.active { background: #667eea; border-color: #667eea; color: white; }
.form-hint { font-size: 0.75rem; color: #aaa; margin-top: 4px; }
.form-hint code { background: #f5f5f5; padding: 1px 5px; border-radius: 4px; font-size: 0.78rem; }
.cron-ok  { font-size: 0.75rem; color: #2e7d32; margin-top: 4px; font-weight: 500; }
.cron-err { font-size: 0.75rem; color: #c62828; margin-top: 4px; font-weight: 500; }
.input-error { border-color: #c62828 !important; }
.hint-text { font-weight: 400; color: #aaa; font-size: 0.78rem; }

/* 通知方式单选组 */
.role-selector {
  display: flex; gap: 10px; flex-wrap: wrap;
}
.role-option {
  display: flex; align-items: center; gap: 7px;
  padding: 7px 14px; border-radius: 8px;
  border: 1px solid #e0e3e8; cursor: pointer;
  font-size: 0.85rem; color: #555;
  transition: all 0.15s;
}
.role-option input[type="radio"] { display: none; }
.role-option.active {
  border-color: #667eea; background: #f0f2ff; color: #667eea; font-weight: 500;
}
.role-option i { font-size: 0.85rem; }

/* 触发类型锁定提示 */
.schedule-type-hint {
  font-size: 0.78rem; color: #bbb;
  display: flex; align-items: center; gap: 6px;
  padding: 6px 10px; background: #f8f9fa; border-radius: 7px;
}
.schedule-type-hint i { color: #ccc; }
</style>
