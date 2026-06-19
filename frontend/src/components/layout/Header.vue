<template>
  <div class="header">
    <div class="header-left">
      <!-- 移动端汉堡菜单 -->
      <button class="mobile-menu-btn" @click="showMobileMenu = !showMobileMenu">
        <i class="fas fa-bars" />
      </button>
      <h2 class="page-title">
        <i :class="pageIcon" />
        {{ pageTitle }}
      </h2>
    </div>
    
    <!-- 移动端导航抽屉 -->
    <div v-if="showMobileMenu" class="mobile-drawer" @click="showMobileMenu = false">
      <div class="mobile-nav" @click.stop>
        <!-- 主导航 -->
        <router-link
          v-for="item in navItems"
          :key="item.name"
          :to="item.path"
          class="mobile-nav-item"
          @click="showMobileMenu = false"
        >
          <i :class="item.icon" />
          {{ item.label }}
        </router-link>

        <!-- 聊天页：历史会话 + 新对话快捷入口 -->
        <template v-if="route.name === 'chat'">
          <div class="mobile-nav-divider" />
          <div
            class="mobile-nav-item mobile-nav-option"
            @click="store.triggerNewSession(); showMobileMenu = false"
          >
            <i class="fas fa-plus" />
            新对话
          </div>
          <div
            class="mobile-nav-item mobile-nav-option"
            @click="store.triggerOpenHistory(); showMobileMenu = false"
          >
            <i class="fas fa-history" />
            历史会话
          </div>
        </template>

        <!-- 管理后台 -->
        <div class="mobile-nav-divider" />
        <div class="mobile-nav-section-title">管理后台</div>
        <router-link
          v-for="item in adminNavItems"
          :key="item.name"
          :to="item.path"
          class="mobile-nav-item mobile-nav-option"
          @click="showMobileMenu = false"
        >
          <i :class="item.icon" />
          {{ item.label }}
        </router-link>

      </div>
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
import { NAV_ITEMS, ADMIN_ITEMS, PAGE_CONFIGS } from '@/config/routes.config'

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

// ── 移动端菜单 ────────────────────────────────────────────
const showMobileMenu = ref(false)

// 移动端主导航：主入口 + 系统快捷入口（短标签）
const systemItem = { ...ADMIN_ITEMS.find(i => i.name === 'admin-system'), label: '系统' }
const navItems = [...NAV_ITEMS, systemItem]

const adminNavItems = ADMIN_ITEMS

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
/* 桌面端隐藏汉堡按钮 */
.mobile-menu-btn {
  display: none;
  background: none;
  border: none;
  font-size: 1.2rem;
  color: var(--color-primary);
  cursor: pointer;
  padding: 4px 8px;
}

@media (max-width: 768px) {
  .mobile-menu-btn { display: flex; align-items: center; }
}

/* 移动端抽屉 */
.mobile-drawer {
  display: none;
}

@media (max-width: 768px) {
  .mobile-drawer {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.4);
    z-index: 100;
  }
}

.mobile-nav {
  position: absolute;
  top: 0; left: 0;
  width: 220px; height: 100%;
  background: var(--color-sidebar-bg);
  padding: 20px 0;
  display: flex;
  flex-direction: column;
}

.mobile-nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  color: rgba(255,255,255,0.8);
  text-decoration: none;
  font-size: 1rem;
  transition: background 0.2s;
}

.mobile-nav-item:hover,
.mobile-nav-item.router-link-active {
  background: rgba(255,255,255,0.1);
  color: white;
}

.mobile-nav-item i { width: 20px; text-align: center; }

.mobile-nav-divider { height: 1px; background: rgba(255,255,255,0.1); margin: 4px 0; }
.mobile-nav-section-title {
  padding: 8px 20px 4px;
  font-size: 0.72rem;
  color: rgba(255,255,255,0.4);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
.mobile-nav-option { font-size: 0.9rem; }
.mobile-nav-option.mobile-active { color: #a5b4fc; }
.mobile-check { margin-left: auto; font-size: 0.8rem; color: #a5b4fc; }

.header {
  height: 60px;
  background: var(--color-surface);
  border-top: 2px solid var(--color-primary);
  border-bottom: 1px solid var(--color-border);
  padding: 0 var(--space-5);
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
  font-weight: 500;
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
  width: 34px; height: 34px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; font-size: 0.9rem;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.theme-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }

@media (max-width: 768px) {
  .header { padding: 0 12px; }
  .page-title { font-size: 1rem; }
}
</style>