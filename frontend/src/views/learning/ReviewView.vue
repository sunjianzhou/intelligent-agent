<template>
  <div class="review-view">
    <div class="toolbar">
      <h2>错题本</h2>
      <div class="filters">
        <el-select v-model="topic" @change="load" style="width: 140px">
          <el-option label="K8s" value="k8s" />
          <el-option label="K8s 复习" value="k8s_review" />
          <el-option label="LLM" value="llm" />
          <el-option label="Agent" value="agent" />
        </el-select>
        <el-checkbox v-model="includeResolved" @change="load" style="margin-left: 12px">
          显示已掌握
        </el-checkbox>
      </div>
    </div>

    <div v-if="loading" class="loading">加载中...</div>

    <div v-else-if="records.length === 0" class="empty">
      🎉 暂无错题
    </div>

    <div
      v-else
      v-for="r in records"
      :key="r.question_id"
      class="wrong-card"
      :class="{ resolved: r.resolved }"
    >
      <div class="card-header">
        <span class="qid">{{ r.question_id }}</span>
        <el-tag
          v-if="r.wrong_count >= 3"
          type="danger"
          size="small"
          style="margin-left: 8px"
        >
          高频错题 × {{ r.wrong_count }}
        </el-tag>
        <el-tag
          v-else-if="r.wrong_count > 1"
          type="warning"
          size="small"
          style="margin-left: 8px"
        >
          错 {{ r.wrong_count }} 次
        </el-tag>
        <el-tag v-if="r.resolved" type="success" size="small" style="margin-left: 8px">
          ✓ 已掌握
        </el-tag>
      </div>

      <p>上次错误：<b>{{ r.user_answer }}</b>　正确答案：<b>{{ r.correct_answer }}</b></p>
      <p class="time">最近错误时间：{{ formatTime(r.last_wrong_time) }}</p>

      <el-button
        v-if="!r.resolved"
        size="small"
        type="success"
        @click="markResolved(r)"
      >
        标记已掌握
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

const topic = ref('k8s')
const includeResolved = ref(false)
const loading = ref(true)
const records = ref([])

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({
      topic: topic.value,
      include_resolved: includeResolved.value,
    })
    const res = await fetch(`/api/teaching/wrong-book?${params}`)
    const data = await res.json()
    records.value = data.records
  } catch (e) {
    ElMessage.error('加载错题本失败')
  } finally {
    loading.value = false
  }
}

async function markResolved(record) {
  try {
    await fetch(`/api/teaching/wrong-book/${record.question_id}/resolve?topic=${topic.value}`, {
      method: 'POST',
    })
    record.resolved = true
    ElMessage.success('已标记为掌握')
    if (!includeResolved.value) {
      records.value = records.value.filter(r => !r.resolved)
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

function formatTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN')
}

onMounted(load)
</script>

<style scoped>
.review-view { max-width: 800px; margin: 0 auto; padding: 24px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.filters { display: flex; align-items: center; }
.wrong-card { background: #fff8f8; border: 1px solid #fbc4c4; border-radius: 8px; padding: 14px; margin-bottom: 12px; }
.wrong-card.resolved { background: #f0f9eb; border-color: #b3e19d; }
.card-header { display: flex; align-items: center; margin-bottom: 8px; }
.qid { font-weight: bold; font-size: 14px; }
.time { font-size: 12px; color: #999; margin: 4px 0 8px; }
.empty { text-align: center; padding: 48px; color: #999; font-size: 16px; }
</style>
