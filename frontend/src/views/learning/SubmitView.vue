<template>
  <div class="submit-view">
    <div v-if="loading" class="loading">加载中...</div>

    <template v-else-if="plan && !gradeResult">
      <!-- 题目卡片 -->
      <div class="plan-header">
        <h2>{{ plan.is_weekend ? '周末实操' : '今日练习' }} · {{ plan.topic }}</h2>
        <div v-if="!plan.is_weekend" class="progress-bar">
          <span>已完成 {{ answeredCount }} / {{ plan.questions.length }}</span>
          <el-progress
            :percentage="Math.round(answeredCount / plan.questions.length * 100)"
            :show-text="false"
            style="width: 200px; margin-left: 12px"
          />
        </div>
      </div>

      <!-- 周末实操模式 -->
      <div v-if="plan.is_weekend" class="weekend-commands">
        <h3>本周实操命令</h3>
        <ul>
          <li v-for="cmd in plan.commands" :key="cmd">{{ cmd }}</li>
        </ul>
      </div>

      <!-- 答题模式 -->
      <template v-else>
        <div
          v-for="(q, idx) in plan.questions"
          :key="q.id"
          class="question-card"
        >
          <p class="question-text"><b>Q{{ idx + 1 }}.</b> {{ q.text }}</p>

          <!-- 选择题 -->
          <el-radio-group
            v-if="q.type === 'choice'"
            v-model="answers[q.id]"
          >
            <el-radio
              v-for="(text, key) in q.options"
              :key="key"
              :label="key"
              style="display: block; margin: 4px 0"
            >
              {{ key }}. {{ text }}
            </el-radio>
          </el-radio-group>

          <!-- 填空题 -->
          <el-input
            v-else-if="q.type === 'fill'"
            v-model="answers[q.id]"
            placeholder="填写答案"
            style="max-width: 400px"
          />

          <!-- 简答题 -->
          <el-input
            v-else-if="q.type === 'short_answer'"
            v-model="answers[q.id]"
            type="textarea"
            :rows="3"
            placeholder="输入简答内容"
          />
        </div>

        <el-button
          type="primary"
          :disabled="answeredCount === 0"
          @click="submit"
          style="margin-top: 16px"
        >
          提交答案
        </el-button>
      </template>
    </template>

    <!-- 批改结果 -->
    <div v-else-if="gradeResult" class="grade-result">
      <h2>批改结果：{{ gradeResult.score }} / {{ gradeResult.total }}</h2>

      <div
        v-for="r in gradeResult.results"
        :key="r.question_id"
        class="result-card"
        :class="r.correct ? 'correct' : 'wrong'"
      >
        <p>
          <b>{{ r.question_id }}</b>
          <el-tag :type="r.correct ? 'success' : 'danger'" size="small" style="margin-left: 8px">
            {{ r.correct ? '✓ 正确' : '✗ 错误' }}
          </el-tag>
        </p>
        <p>你的答案：{{ r.user_answer }}　正确答案：{{ r.correct_answer }}</p>
        <p class="explanation">📖 {{ r.explanation }}</p>
      </div>

      <div class="export-actions" style="margin-top: 16px">
        <el-button @click="copyMarkdown">复制全部解析</el-button>
        <el-button @click="downloadMarkdown">导出 .md</el-button>
        <el-button type="primary" @click="reset">再练一次</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({ topic: { type: String, default: 'k8s' } })

const loading = ref(true)
const plan = ref(null)
const answers = ref({})
const gradeResult = ref(null)

const answeredCount = computed(
  () => Object.values(answers.value).filter(v => v && v.trim()).length
)

onMounted(async () => {
  try {
    const res = await fetch(`/api/teaching/daily-plan?topic=${props.topic}`)
    plan.value = await res.json()
    plan.value.questions.forEach(q => { answers.value[q.id] = '' })
  } catch (e) {
    ElMessage.error('加载题目失败')
  } finally {
    loading.value = false
  }
})

async function submit() {
  const payload = {
    user_id: 'default',
    topic: props.topic,
    answers: Object.entries(answers.value)
      .filter(([, v]) => v && v.trim())
      .map(([question_id, user_answer]) => ({ question_id, user_answer })),
  }
  try {
    const res = await fetch('/api/teaching/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    gradeResult.value = await res.json()
  } catch (e) {
    ElMessage.error('提交失败，请重试')
  }
}

function buildMarkdown() {
  if (!gradeResult.value) return ''
  const lines = [`# 批改结果 ${new Date().toLocaleDateString()}`, '']
  for (const r of gradeResult.value.results) {
    lines.push(`## ${r.question_id}  ${r.correct ? '✓' : '✗'}`)
    lines.push(`- 你的答案：${r.user_answer}`)
    lines.push(`- 正确答案：${r.correct_answer}`)
    lines.push(`- 解析：${r.explanation}`)
    lines.push('')
  }
  return lines.join('\n')
}

function copyMarkdown() {
  navigator.clipboard.writeText(buildMarkdown())
  ElMessage.success('已复制到剪贴板')
}

function downloadMarkdown() {
  const md = buildMarkdown()
  const blob = new Blob([md], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `grade-${Date.now()}.md`
  a.click()
  URL.revokeObjectURL(url)
}

function reset() {
  gradeResult.value = null
  answers.value = {}
  plan.value.questions.forEach(q => { answers.value[q.id] = '' })
}
</script>

<style scoped>
.submit-view { max-width: 800px; margin: 0 auto; padding: 24px; }
.plan-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.progress-bar { display: flex; align-items: center; font-size: 14px; color: #666; }
.question-card { background: #f9f9f9; border-radius: 8px; padding: 16px; margin-bottom: 12px; }
.question-text { font-size: 15px; margin-bottom: 8px; }
.result-card { border-left: 4px solid #ccc; padding: 12px; margin-bottom: 10px; border-radius: 4px; }
.result-card.correct { border-color: #67c23a; background: #f0f9eb; }
.result-card.wrong { border-color: #f56c6c; background: #fef0f0; }
.explanation { color: #555; font-size: 13px; margin-top: 6px; }
.weekend-commands li { margin: 6px 0; }
</style>
