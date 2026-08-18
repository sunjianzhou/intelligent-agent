<template>
  <div class="modal-overlay" @click.self="emit('close')">
    <div class="modal-box">
      <div class="modal-header">
        <span class="modal-title">编辑任务</span>
        <button class="modal-close" @click="emit('close')">
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
        <button class="btn-cancel" @click="emit('close')">取消</button>
        <button class="btn-confirm" :disabled="!editForm.name || saving" @click="doSave">
          <i v-if="saving" class="fas fa-circle-notch fa-spin" />
          {{ saving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateTask } from '@/services/api'

const props = defineProps({
  task:    { type: Object, required: true },
  actions: { type: Array, default: () => [] },
})
const emit = defineEmits(['close', 'saved'])

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

const emptyEditForm = () => ({
  name: '', description: '', action: 'log', schedule_type: 'interval',
  interval_seconds: 300, delay_seconds: 60, cron_expression: '', message: '', role: 'assistant', prompt: '',
})
const editForm = ref(emptyEditForm())
const saving = ref(false)

const buildForm = (task) => {
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
}

watch(() => props.task, (task) => { if (task) buildForm(task) }, { immediate: true })

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

  const wdNames = ['周日','周一','周二','周三','周四','周五','周六','周日']
  const stepOf  = s => s.startsWith('*/') ? parseInt(s.slice(2)) : null
  const numOf   = s => /^\d+$/.test(s) ? parseInt(s) : null
  const pad     = n => String(n).padStart(2, '0')

  let timeDesc = ''
  const hStep = stepOf(hour), mStep = stepOf(min)
  const hNum = numOf(hour), mNum = numOf(min)
  if (min === '*' && hour === '*')        timeDesc = '每分钟'
  else if (mStep && hour === '*')         timeDesc = `每 ${mStep} 分钟`
  else if (mNum === 0 && hStep)          timeDesc = `每 ${hStep} 小时整点`
  else if (hNum !== null && mNum !== null) timeDesc = `${hNum}:${pad(mNum)}`
  else if (hNum !== null && min === '0') timeDesc = `${hNum}:00`
  else                                   timeDesc = `${min}分 ${hour}时`

  let dateDesc = ''
  if (weekday !== '*') {
    if (weekday === '1-5')           dateDesc = '工作日'
    else if (['0,6','6,0','6,7'].includes(weekday)) dateDesc = '周末'
    else dateDesc = weekday.split(',').map(w => wdNames[parseInt(w)] || w).join('、')
  } else if (day !== '*') {
    const d = numOf(day)
    dateDesc = d !== null ? `每月${d}日` : `每月${day}日`
  }

  let monthDesc = ''
  if (month !== '*') {
    const m = numOf(month)
    monthDesc = m !== null ? `${m}月` : `${month}月`
  }

  const parts2 = [monthDesc, dateDesc, timeDesc].filter(Boolean)
  return { valid: true, desc: parts2.join(' ') || '自定义周期' }
}

const cronEditHint = computed(() => _parseCron(editForm.value.cron_expression))

const doSave = async () => {
  if (!editForm.value.name || !props.task) return
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
    const result = await updateTask(props.task.id, payload)
    if (result?.success) {
      ElMessage({ message: '任务已更新', type: 'success', duration: 2000 })
      emit('saved')
    } else {
      ElMessage({ message: `更新失败: ${result?.message || '未知错误'}`, type: 'error', duration: 4000 })
    }
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
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
