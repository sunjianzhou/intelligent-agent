<template>
  <div class="header">
    <div class="header-left">
      <h2 class="page-title">
        <i :class="pageIcon" />
        {{ pageTitle }}
      </h2>
    </div>

    <div class="header-right">
      <!-- 连接状态 -->
      <div class="connection-status">
        <span class="status-dot" :class="isConnected ? 'connected' : wasEverConnected ? 'disconnected-sudden' : 'disconnected-init'" />
        <span class="status-text" :class="isConnected ? 'connected' : wasEverConnected ? 'disconnected-sudden' : 'disconnected-init'">
          {{ connectionStatus }}
        </span>
      </div>

      <!-- 暗色主题切换（WANT-011） -->
      <button class="theme-btn" :title="isDark ? '切换浅色主题' : '切换暗色主题'" @click="toggleTheme">
        <i :class="isDark ? 'fas fa-sun' : 'fas fa-moon'" />
      </button>

    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'

import { useWebSocketStore } from '@/stores/websocket'
import { PAGE_CONFIGS } from '@/config/routes.config'

// ── 暗色主题（WANT-011）──────────────────────────────────
const isDark = ref(localStorage.getItem('theme') === 'dark')
const applyTheme = (dark) => {
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
  localStorage.setItem('theme', dark ? 'dark' : 'light')
}
const toggleTheme = () => {
  isDark.value = !isDark.value
  applyTheme(isDark.value)
}

const route = useRoute()
const store = useWebSocketStore()

// ── 页面配置 ──────────────────────────────────────────────
const pageConfig  = computed(() => PAGE_CONFIGS[route.name] || { title: '智能体', icon: 'fas fa-robot' })
const pageTitle   = computed(() => pageConfig.value.title)
const pageIcon    = computed(() => pageConfig.value.icon)

// ── Store 数据 ────────────────────────────────────────────
const isConnected      = computed(() => store.isConnected)
const wasEverConnected = computed(() => store.wasEverConnected)
const connectionStatus = computed(() => store.connectionStatus)

// ── 生命周期 ──────────────────────────────────────────────
// 模型列表由这里统一预加载，供 ChatView 右下角模型切换控件使用
onMounted(() => {
  store.loadModels()
  applyTheme(isDark.value)
})

watch(isConnected, (connected) => {
  if (connected && store.availableModels.length === 0) store.loadModels()
})
</script>

<style scoped>
.header {
  height: 60px;
  background: var(--color-surface);
  border-top: 2px solid var(--color-primary);
  border-bottom: 1px solid var(--color-border);
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.header-left .page-title {
  font-size: var(--text-lg);
  color: var(--color-text);
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}
.header-left .page-title i { color: var(--color-primary); }

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* ── 连接状态 ────────────────────────────────────────── */
.connection-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.88rem;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot.connected           { background: var(--color-success); }
.status-dot.disconnected-sudden { background: var(--color-danger); animation: pulse-dot 1.5s infinite; }
.status-dot.disconnected-init   { background: var(--color-text-muted); }
.status-text.connected           { color: var(--color-success); }
.status-text.disconnected-sudden { color: var(--color-danger); }
.status-text.disconnected-init   { color: var(--color-text-muted); }
@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0.4; }
}

.theme-btn {
  width: 38px; height: 38px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; font-size: 0.95rem;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.theme-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }

@media (max-width: 768px) {
  .header { padding: 0 12px; }
  .page-title { font-size: 1rem; }
}
</style>
