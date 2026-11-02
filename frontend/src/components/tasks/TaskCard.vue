<template>
  <div class="task-card">
    <!-- 左侧状态条 -->
    <div class="status-bar" :class="task.status" />

    <!-- 任务信息 -->
    <div class="task-body">
      <div class="task-header">
        <div class="task-name-row">
          <span class="task-name">{{ task.name }}</span>
          <span class="status-badge" :class="task.status">
            {{ statusLabel(task.status) }}
          </span>
        </div>
        <div class="task-desc" v-if="task.description">{{ task.description }}</div>
        <div class="task-desc task-prompt" v-if="task.args?.prompt || task.args?.message">
          {{ task.args?.prompt || task.args?.message }}
        </div>
      </div>

      <div class="task-meta-row">
        <span class="meta-item">
          <i class="fas fa-bolt" />
          {{ actionLabel(task.action) }}
        </span>
        <span class="meta-item">
          <i class="fas fa-clock" />
          {{ scheduleLabel(task) }}
        </span>
        <span v-if="task.next_run && task.status === 'pending'" class="meta-item">
          <i class="fas fa-clock" />
          {{ formatCountdown(task.next_run) }}
        </span>
        <span v-else-if="task.status === 'completed'" class="meta-item">
          <i class="fas fa-check" />
          已完成
        </span>
        <span v-else-if="task.status === 'running'" class="meta-item">
          <i class="fas fa-circle-notch fa-spin" />
          执行中
        </span>
        <span class="meta-item">
          <i class="fas fa-redo" />
          已运行 {{ task.run_count }} 次
        </span>
      </div>

      <!-- 最后结果 -->
      <div v-if="task.last_error" class="task-result error">
        <i class="fas fa-exclamation-circle" /> {{ task.last_error }}
      </div>
      <div v-else-if="task.last_result"
           :class="['task-result', isResultError(task.last_result) ? 'error' : 'success']">
        <i :class="isResultError(task.last_result) ? 'fas fa-exclamation-circle' : 'fas fa-check-circle'" />
        {{ extractResultMessage(task.last_result) }}
      </div>
    </div>

    <!-- 操作按钮 -->
    <div class="task-actions">
      <button
        class="action-btn run-btn"
        :disabled="task.status === 'running' || triggeringIds.has(task.id)"
        :title="triggeringIds.has(task.id) ? '触发中...' : '立即执行'"
        @click="emit('run', task.id)"
      >
        <i :class="triggeringIds.has(task.id) ? 'fas fa-circle-notch fa-spin' : 'fas fa-play'" />
      </button>
      <button
        class="action-btn edit-btn"
        title="编辑"
        @click="emit('edit', task)"
      >
        <i class="fas fa-pen" />
      </button>
      <button
        v-if="task.status === 'pending' || task.status === 'running'"
        class="action-btn cancel-btn"
        :title="task.status === 'running' ? '停止（下次不再执行）' : '取消'"
        @click="emit('cancel', task.id)"
      >
        <i :class="task.status === 'running' ? 'fas fa-stop' : 'fas fa-ban'" />
      </button>
      <button
        class="action-btn del-btn"
        title="删除"
        @click="emit('remove', task.id)"
      >
        <i class="fas fa-trash" />
      </button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  task: { type: Object, required: true },
  triggeringIds: { type: Set, default: () => new Set() },
  now: { type: Number, default: Date.now },
})
const emit = defineEmits(['run', 'edit', 'cancel', 'remove'])

const statusLabel = (s) => ({
  pending: '待执行', running: '执行中',
  completed: '已完成', failed: '失败', cancelled: '已取消'
}[s] || s)

const formatDuration = (seconds) => {
  if (!seconds || seconds <= 0) return '-'
  if (seconds < 60)    return `${seconds}秒`
  if (seconds < 3600)  return `${Math.round(seconds / 60)}分钟`
  if (seconds < 86400) return `${Math.round(seconds / 3600)}小时`
  return `${Math.round(seconds / 86400)}天`
}

const scheduleLabel = (task) => {
  if (task.schedule_type === 'delay')    return `${formatDuration(task.delay_seconds)}后执行一次`
  if (task.schedule_type === 'interval') return `每 ${formatDuration(task.interval_seconds)} 循环`
  if (task.schedule_type === 'datetime') return '指定时间'
  if (task.schedule_type === 'cron')     return task.cron_expression ? `Cron: ${task.cron_expression}` : 'Cron（未配置）'
  return task.schedule_type
}

const actionLabel = (action) => ({
  log:          '💬 提醒',
  llm_generate: '🤖 AI生成',
  system_info:  '🖥️ 系统信息',
  test:         '🧪 测试',
}[action] || action)

// last_result 从 Python str(dict) 格式中提取 message 字段
const _parseResult = (result) => {
  if (!result) return null
  if (typeof result === 'object') return result
  try { return JSON.parse(result) } catch {}
  return null
}

const isResultError = (result) => {
  const obj = _parseResult(result)
  if (obj && obj.success === false) return true
  if (typeof result === 'string' && result.includes('"success": false')) return true
  return false
}

const truncate = (s, n = 80) =>
  s && s.length > n ? s.slice(0, n) + '...' : s

const extractResultMessage = (result) => {
  if (!result) return ''
  const obj = _parseResult(result)
  if (obj) {
    if (obj.success === true && obj.length != null && !obj.message) {
      return `AI 已生成（${obj.length} 字符）`
    }
    if (obj.success === false && obj.error) return `失败: ${obj.error}`
    if (obj.message) return truncate(obj.message)
    return truncate(JSON.stringify(obj))
  }
  // Python repr: {'message': '...', ...}
  const m = typeof result === 'string' && result.match(/'message'\s*:\s*'((?:[^'\\]|\\.)*)'/)
  if (m) return truncate(m[1])
  return truncate(String(result))
}

const formatCountdown = (iso) => {
  if (!iso) return '-'
  const diff = new Date(iso) - now
  if (diff <= 0) return '即将执行'
  if (diff < 60000)   return `${Math.floor(diff / 1000)}秒后`
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟后`
  return `${Math.floor(diff / 3600000)}小时后`
}
</script>

<style scoped>
.task-card {
  position: relative;
  display: flex;
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: var(--transition-interactive);
  /* ensure hover action buttons don't push layout */
  padding-bottom: var(--space-2);

  box-shadow: var(--shadow-card);}
.task-card:hover { border-color: var(--color-primary-soft); box-shadow: var(--shadow-glow-primary); }

.status-bar { width: 4px; flex-shrink: 0; }
.status-bar.pending   { background: var(--color-warn); }
.status-bar.running   { background: var(--color-primary); }
.status-bar.completed { background: var(--color-success); }
.status-bar.failed    { background: var(--color-danger); }
.status-bar.cancelled { background: var(--color-text-muted); }

.task-body { flex: 1; padding: var(--space-3-5) var(--space-4); min-width: 0; }
.task-header { margin-bottom: var(--space-2); }
.task-name-row {
  display: flex; align-items: center; gap: var(--space-2-5); margin-bottom: var(--space-1);
}
.task-name { font-size: var(--text-base); font-weight: var(--weight-medium); color: var(--color-text); }
.status-badge {
  font-size: var(--text-2xs); padding: var(--space-0-5) var(--space-2);
  border-radius: var(--radius-sm); font-weight: var(--weight-medium);
}
.status-badge.pending   { background: var(--color-warn-soft); color: var(--color-warn); }
.status-badge.running   { background: var(--color-primary-soft); color: var(--color-primary); }
.status-badge.completed { background: var(--color-success-soft); color: var(--color-success); }
.status-badge.failed    { background: var(--color-danger-soft); color: var(--color-danger); }
.status-badge.cancelled { background: var(--color-surface-sunken); color: var(--color-text-secondary); }

.task-desc {
  font-size: var(--text-sm-2); color: var(--color-text-secondary);
  overflow: hidden; display: -webkit-box;
  -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.task-prompt { color: var(--color-text-secondary); white-space: pre-wrap; word-break: break-word;
  max-height: 3.6em; overflow: hidden; display: -webkit-box;
  -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

.task-meta-row {
  display: flex; flex-wrap: wrap; gap: var(--space-3);
  font-size: var(--text-sm-2); color: var(--color-text-muted);
}
.meta-item { display: flex; align-items: center; gap: var(--space-1); }
.meta-item i { font-size: var(--text-xs); }

.task-result {
  margin-top: var(--space-2);
  font-size: var(--text-sm-2);
  padding: var(--space-1-5) var(--space-2-5);
  border-radius: var(--radius-sm);
  display: flex; align-items: flex-start; gap: var(--space-1-5);
  word-break: break-all;
}
.task-result.error   { background: var(--color-danger-soft); color: var(--color-danger); }
.task-result.success { background: var(--color-success-soft); color: var(--color-success); }

.task-actions {
  position: absolute;
  right: 12px;
  bottom: 12px;
  display: flex;
  flex-direction: row;
  gap: var(--space-1-5);
  opacity: 0;
  transform: translateY(4px);
  transition: opacity var(--duration-base), transform var(--duration-base);
  pointer-events: none;
}
.task-card:hover .task-actions {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}
.action-btn {
  width: 30px; height: 30px; border-radius: var(--radius-sm);
  border: none; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-sm-2); transition: var(--transition-interactive);
}
.run-btn    { background: var(--color-success-soft); color: var(--color-success); }
.run-btn:hover:not(:disabled)    { background: var(--color-success-soft); }
.edit-btn   { background: var(--color-primary-soft); color: var(--color-primary); }
.edit-btn:hover { background: var(--color-primary-soft); }
.cancel-btn { background: var(--color-warn-soft); color: var(--color-warn); }
.cancel-btn:hover { background: var(--color-warn-soft); }
.del-btn    { background: var(--color-danger-soft); color: var(--color-danger); }
.del-btn:hover { background: var(--color-danger-soft); }
.action-btn:disabled { opacity: 0.45; cursor: not-allowed; }
</style>
