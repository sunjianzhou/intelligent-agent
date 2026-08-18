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
    <TaskEditModal
      v-if="showEdit"
      :task="editingTask"
      :actions="actions"
      @close="showEdit = false"
      @saved="onTaskSaved"
    />

    <!-- 新建任务弹窗 -->
    <TaskCreateModal
      v-if="showCreate"
      :actions="actions"
      @close="showCreate = false"
      @created="onTaskCreated"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useConfirmDialogStore } from '@/stores/confirmDialog'
import {
  getTasksList, deleteTask,
  cancelTask, executeTaskNow, getTaskStats, getTaskActions
} from '@/services/api'
import TaskCard from '@/components/tasks/TaskCard.vue'
import TaskCreateModal from '@/components/tasks/TaskCreateModal.vue'
import TaskEditModal   from '@/components/tasks/TaskEditModal.vue'

const confirmDialog = useConfirmDialogStore()
const tasks      = ref([])
const stats      = ref({})
const actions     = ref([])
const loading     = ref(false)
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
  showEdit.value = true
}

const onTaskSaved = async () => {
  showEdit.value = false
  await load()
}

const onTaskCreated = async () => {
  showCreate.value = false
  await load()
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

</style>
