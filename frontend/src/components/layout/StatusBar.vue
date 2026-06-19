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
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
  padding: 0 var(--space-5);
  display: flex;
  align-items: center;
  gap: var(--space-5);
  font-size: var(--text-xs);
}

.status-item {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--color-text-secondary);
}

.status-icon { font-size: 0.7rem; }
.status-label { color: var(--color-text-muted); }
.status-value { color: var(--color-text); font-weight: 500; }
</style>
