<template>
  <div class="app">
    <!-- 未登录直接显示登录组件 -->
    <LoginView v-if="!authStore.isLoggedIn" />

    <!-- 已登录显示主布局 -->
    <div v-else class="main-layout">
      <Sidebar />
      <div class="main-content">
        <Header />
        <InstallPrompt />
        <div v-if="isMockMode" class="mock-mode-indicator">
          <i class="fas fa-exclamation-triangle"></i>
          当前为模拟模式 - WebSocket连接失败，正在使用模拟数据
        </div>
        <div class="page-container">
          <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
        <StatusBar />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, computed, watch } from 'vue'
import { useWebSocketStore } from '@/stores/websocket'
import { useAuthStore } from '@/stores/auth'
import { useErrorBusStore } from '@/stores/errorBus'
import { useProjectStore } from '@/stores/project'
import Sidebar from '@/components/layout/Sidebar.vue'
import Header from '@/components/layout/Header.vue'
import StatusBar from '@/components/layout/StatusBar.vue'
import LoginView from '@/views/LoginView.vue'
import InstallPrompt from '@/components/InstallPrompt.vue'

const websocketStore = useWebSocketStore()
const authStore      = useAuthStore()
const projectStore   = useProjectStore()
useErrorBusStore()   // 激活 store，确保 api.js 首次调用前已初始化
const isMockMode     = computed(() => websocketStore.isMockMode)
const isLoginPage    = computed(() => !authStore.isLoggedIn)

const _doConnect = () => {
  if (websocketStore.isConnected) return
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  websocketStore.connect(`${protocol}//${window.location.host}/ws`)
}

// 登录状态变化时立即响应：登录后连 WS + 加载项目列表，退出后断连
// immediate:true 确保页面刷新时（已有 token）同样建立连接
watch(
  () => authStore.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      _doConnect()
      projectStore.loadProjects().then(() => {
        // 刷新后恢复激活项目的完整数据（含 spec 未同步重试）
        const stored = projectStore.activeProjectId
        if (stored) projectStore.activateProject(stored)
      })
    } else {
      websocketStore.disconnect()
    }
  },
  { immediate: true }
)

onMounted(() => {
  // 首屏已登录且 WS 尚未建立时兜底连接（watch immediate 通常已覆盖）
  if (authStore.isLoggedIn && !websocketStore.isConnected) _doConnect()
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  height: 100vh;
  overflow: hidden;
}

.app {
  width: 100vw;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 20px;
}

/* 手机端去掉 padding 和圆角，全屏显示 */
@media (max-width: 768px) {
  .app {
    padding: 0;
  }
}

.main-layout {
  width: 100%;
  max-width: 1400px;
  height: 100%;
  max-height: 900px;
  background: white;
  border-radius: 20px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  overflow: hidden;
  display: flex;
}

/* 手机端全屏，去掉圆角和阴影 */
@media (max-width: 768px) {
  .main-layout {
    max-width: 100%;
    max-height: 100%;
    border-radius: 0;
    box-shadow: none;
  }
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.mock-mode-indicator {
  background: #ffc107;
  color: #856404;
  padding: 8px 20px;
  font-size: 0.9rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-bottom: 1px solid #ffeaa7;
}

.page-container {
  flex: 1;
  overflow: hidden;
  background: #f8f9fa;
}

/* 页面切换动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ── 移动端响应式优化（WANT-012）────────────────────────────── */
@media (max-width: 768px) {
  /* 页面整体：去掉固定高度限制 */
  .main-layout { max-height: 100dvh; }

  /* 各视图页：减少内边距，适合小屏 */
  .tools-view, .memory-view, .tasks-view,
  .stats-view, .system-view { padding: 12px !important; gap: 10px !important; }

  /* 表格/卡片行：强制单列 */
  .card-row, .chart-row, .detail-row,
  .stats-row, .overview-row { grid-template-columns: 1fr 1fr !important; }

  /* 卡片字体缩小 */
  .stat-num { font-size: 18px !important; }
  .ov-val   { font-size: 1.4rem !important; }

  /* 工具卡片网格 */
  .tools-grid { grid-template-columns: 1fr !important; }

  /* 任务弹窗：满屏 */
  .modal-box { width: 95vw !important; max-height: 90vh !important; }

  /* 工具/记忆工具栏：竖排 */
  .toolbar { flex-wrap: wrap !important; gap: 8px !important; }

  /* 内存导入按钮在工具栏中不换行 */
  .import-btn, .refresh-btn { flex-shrink: 0; }

  /* 配置面板：单列 */
  .config-grid, .param-grid { grid-template-columns: 1fr !important; }
}

/* ── 暗色主题（WANT-011）────────────────────────────── */
[data-theme="dark"] body {
  background: linear-gradient(135deg, #2d3561 0%, #1a1b2e 100%);
}

[data-theme="dark"] .main-layout {
  background: #1e1f23;
  box-shadow: 0 20px 60px rgba(0,0,0,0.7);
}

/* 通用卡片/背景覆盖 */
[data-theme="dark"] .page-container       { background: #25262b !important; }
[data-theme="dark"] .chat-view            { background: #1e1f23 !important; }
[data-theme="dark"] .tools-view,
[data-theme="dark"] .memory-view,
[data-theme="dark"] .tasks-view,
[data-theme="dark"] .stats-view,
[data-theme="dark"] .system-view          { background: #25262b !important; }

/* 白色卡片 → 深色卡片 */
[data-theme="dark"] .stat-card,
[data-theme="dark"] .status-card,
[data-theme="dark"] .chart-card,
[data-theme="dark"] .detail-card,
[data-theme="dark"] .tool-card,
[data-theme="dark"] .memory-card,
[data-theme="dark"] .task-card,
[data-theme="dark"] .ov-card,
[data-theme="dark"] .config-card,
[data-theme="dark"] .model-card           { background: #2c2d32 !important; border-color: #373a40 !important; }

/* 文字颜色 */
[data-theme="dark"] .stat-num,
[data-theme="dark"] .task-name,
[data-theme="dark"] .tool-name,
[data-theme="dark"] .memory-content,
[data-theme="dark"] .detail-title,
[data-theme="dark"] .chart-title,
[data-theme="dark"] .page-desc,
[data-theme="dark"] .ov-val              { color: #c1c2c5 !important; }

[data-theme="dark"] .stat-label,
[data-theme="dark"] .tool-description,
[data-theme="dark"] .card-label,
[data-theme="dark"] .card-sub,
[data-theme="dark"] .empty-title,
[data-theme="dark"] .mem-time,
[data-theme="dark"] .ov-label            { color: #909296 !important; }

/* 输入框/搜索框暗化 */
[data-theme="dark"] .search-input,
[data-theme="dark"] .cfg-input,
[data-theme="dark"] .form-row input,
[data-theme="dark"] .form-row select,
[data-theme="dark"] .filter-select,
[data-theme="dark"] .chat-input          { background: #373a40 !important; border-color: #4a4d55 !important; color: #c1c2c5 !important; }

[data-theme="dark"] .input-wrap          { background: #2c2d32 !important; border-color: #4a4d55 !important; }
[data-theme="dark"] .input-wrap-thinking { background: #1e2040 !important; border-color: #667eea !important; }

/* 按钮暗化 */
[data-theme="dark"] .refresh-btn,
[data-theme="dark"] .filter-btn,
[data-theme="dark"] .type-btn,
[data-theme="dark"] .cat-btn,
[data-theme="dark"] .import-btn,
[data-theme="dark"] .quick-btn,
[data-theme="dark"] .cfg-eye             { background: #2c2d32 !important; border-color: #4a4d55 !important; color: #c1c2c5 !important; }

/* 消息气泡 */
[data-theme="dark"] .bubble.user         { background: #3b3d8b !important; }
[data-theme="dark"] .bubble.assistant    { background: #2c2d32 !important; color: #c1c2c5 !important; }
[data-theme="dark"] .bubble.system       { background: #2d2a1a !important; color: #d4a72c !important; border-color: #5a4a00 !important; }
[data-theme="dark"] .bubble.system.notif { background: linear-gradient(135deg, #2d2a1a, #2a2618) !important; border-color: #7a5c00 !important; border-left-color: #c17600 !important; }
[data-theme="dark"] .notif-task-link     { color: #c17600 !important; border-top-color: rgba(193,118,0,0.25) !important; }
[data-theme="dark"] .ctx-warn-banner     { background: linear-gradient(90deg, #2d1f0a 0%, #2d1616 100%) !important; border-color: #6b3a00 !important; color: #e8a272 !important; }
[data-theme="dark"] .message-list        { background: #1e1f23 !important; }

/* 弹窗 */
[data-theme="dark"] .modal-box           { background: #2c2d32 !important; }
[data-theme="dark"] .modal-header,
[data-theme="dark"] .modal-footer        { border-color: #373a40 !important; }
[data-theme="dark"] .modal-title         { color: #c1c2c5 !important; }

/* 记录/列表行 */
[data-theme="dark"] .record-item         { background: #2c2d32 !important; }
[data-theme="dark"] .record-item:hover   { background: #373a40 !important; }
[data-theme="dark"] .record-response     { color: #909296 !important; }

/* 工具调用卡片 */
[data-theme="dark"] .tool-running-card   { background: #25273d !important; border-color: #667eea !important; }
[data-theme="dark"] .tool-calls-card     { background: #25273d !important; }

/* 搜索栏 */
[data-theme="dark"] .chat-search-bar     { background: #2c2d32 !important; border-color: #4a4d55 !important; }
[data-theme="dark"] .search-bar-input    { color: #c1c2c5 !important; }

/* 磁盘/进程条背景 */
[data-theme="dark"] .disk-bar-wrap,
[data-theme="dark"] .process-bar-wrap,
[data-theme="dark"] .rank-bar-wrap       { background: #373a40 !important; }
</style>
