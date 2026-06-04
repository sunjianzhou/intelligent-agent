<template>
  <!-- 仅在有响应时间时显示，否则隐藏；避免与 Header 信息重复 -->
  <div v-if="hasActivity" class="status-bar">
    <div class="status-item">
      <i class="fas fa-bolt status-icon" />
      <span class="status-label">响应时间:</span>
      <span class="status-value">{{ responseTime }}</span>
    </div>
    <div class="status-item">
      <i class="fas fa-comments status-icon" />
      <span class="status-label">消息数:</span>
      <span class="status-value">{{ messageCount }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useWebSocketStore } from '@/stores/websocket'

const websocketStore = useWebSocketStore()

const hasActivity = computed(() => websocketStore.messages.length > 0)

const responseTime = computed(() => {
  if (websocketStore.lastResponseTime) {
    return `${websocketStore.lastResponseTime.toFixed(2)}s`
  }
  return '-'
})

const messageCount = computed(() => websocketStore.messages.length)
</script>

<style scoped>
.status-bar {
  height: 32px;
  background: #fafbfc;
  border-top: 1px solid #f0f2f5;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 24px;
  font-size: 0.78rem;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #999;
}

.status-icon { font-size: 0.7rem; }
.status-label { color: #bbb; }
.status-value { color: #666; font-weight: 500; }
</style>
