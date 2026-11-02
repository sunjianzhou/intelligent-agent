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
      <!-- 移动端底部导航（桌面端通过 CSS display:none 隐藏） -->
      <BottomTabBar @open-more="showMorePanel = true" />
      <MorePanel v-model="showMorePanel" />
    </div>
    <ConfirmDialog />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, computed, watch, ref } from 'vue'
import { useWebSocketStore } from '@/stores/websocket'
import { useAuthStore } from '@/stores/auth'
import { useErrorBusStore } from '@/stores/errorBus'
import { useProjectStore } from '@/stores/project'
import Sidebar from '@/components/layout/Sidebar.vue'
import Header from '@/components/layout/Header.vue'
import StatusBar from '@/components/layout/StatusBar.vue'
import BottomTabBar from '@/components/layout/BottomTabBar.vue'
import MorePanel from '@/components/layout/MorePanel.vue'
import LoginView from '@/views/LoginView.vue'
import InstallPrompt from '@/components/InstallPrompt.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'

const websocketStore = useWebSocketStore()
const authStore      = useAuthStore()
const projectStore   = useProjectStore()
useErrorBusStore()   // 激活 store，确保 api.js 首次调用前已初始化
const showMorePanel  = ref(false)
const isMockMode     = computed(() => websocketStore.isMockMode)
const isLoginPage    = computed(() => !authStore.isLoggedIn)

// connect() 内部已封装 _defaultWsUrl()，无需在外部重复构造 URL
const _doConnect = () => websocketStore.connect()

// 登录状态变化时立即响应：登录后连 WS + 加载项目列表，退出后断连
// immediate:true 确保页面刷新时（已有 token）同样建立连接
watch(
  () => authStore.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      _doConnect()
      // 登录确认后才从 localStorage 恢复激活项目，防止跨用户污染
      projectStore.restoreActiveProject()
      projectStore.loadProjects().then(() => {
        // 刷新后恢复激活项目的完整数据（含 spec 未同步重试）
        const stored = projectStore.activeProjectId
        if (stored) projectStore.activateProject(stored)
      })
    } else {
      websocketStore.disconnect()
      // 退出时清除激活项目，防止下一个用户看到本次会话的项目
      projectStore.deactivateProject()
    }
  },
  { immediate: true }
)

onMounted(() => {
  // 首屏已登录且 WS 尚未建立时兜底连接（watch immediate 通常已覆盖）
  if (authStore.isLoggedIn && !websocketStore.isConnected) _doConnect()

  // iOS PWA 键盘弹出时 visualViewport 收缩，将差值写入 CSS 变量
  let vpCleanup
  if (window.visualViewport) {
    const handler = () => {
      const kb = Math.max(0, window.innerHeight - window.visualViewport.height)
      document.documentElement.style.setProperty('--keyboard-height', `${kb}px`)
    }
    handler() // 立即初始化，避免首次渲染前变量未定义
    window.visualViewport.addEventListener('resize', handler)
    vpCleanup = () => window.visualViewport.removeEventListener('resize', handler)
  }
  onUnmounted(() => vpCleanup?.())
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
  background: var(--color-bg);
  height: 100vh;
  height: 100dvh; /* dvh fallback：iOS 15.4+ 支持，旧版降级到 100vh */
  overflow: hidden;
}

.app {
  width: 100vw;
  height: 100vh;
  display: flex;
}

.main-layout {
  width: 100%;
  height: 100vh;
  height: 100dvh;
  background: var(--color-bg);
  display: flex;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.mock-mode-indicator {
  background: var(--color-warn-soft);
  color: var(--color-warn);
  padding: var(--space-2) var(--space-5);
  font-size: var(--text-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.page-container {
  flex: 1;
  overflow: hidden;
  background: var(--color-bg);
}

/* 页面切换动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--duration-base) var(--ease-standard);
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
  .stats-view, .system-view { padding: var(--space-3) !important; gap: var(--space-2-5) !important; }

  /* 表格/卡片行：强制单列 */
  .card-row, .chart-row, .detail-row,
  .stats-row, .overview-row { grid-template-columns: 1fr 1fr !important; }

  /* 卡片字体缩小 */
  .stat-num { font-size: var(--text-lg) !important; }
  .ov-val   { font-size: var(--text-xl) !important; }

  /* 工具卡片网格 */
  .tools-grid { grid-template-columns: 1fr !important; }

  /* 任务弹窗：满屏 */
  .modal-box { width: 95vw !important; max-height: 90vh !important; }

  /* 工具/记忆工具栏：竖排 */
  .toolbar { flex-wrap: wrap !important; gap: var(--space-2) !important; }

  /* 内存导入按钮在工具栏中不换行 */
  .import-btn, .refresh-btn { flex-shrink: 0; }

  /* 配置面板：单列 */
  .config-grid, .param-grid { grid-template-columns: 1fr !important; }
}

/* 移动端：为 Tab Bar + Home Indicator 留出底部空间 */
@media (max-width: 768px) {
  .main-content {
    padding-bottom: calc(56px + env(safe-area-inset-bottom));
  }
}
</style>
