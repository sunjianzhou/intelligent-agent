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

        <!-- 聊天页：历史会话快捷入口 -->
        <template v-if="route.name === 'chat'">
          <div class="mobile-nav-divider" />
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
      <!-- 模型切换入口 -->
      <div class="model-entry" ref="modelMenuRef" v-if="availableModels.length > 0 || modelStatus">
        <button class="model-btn" :class="{ open: modelMenuOpen, cloud: isCloudMode }" @click.stop="modelMenuOpen = !modelMenuOpen" :title="modelStatus">
          <i :class="isCloudMode ? 'fas fa-cloud' : 'fas fa-cube'" />
          <span class="model-btn-text">{{ modelStatus }}</span>
          <i class="fas fa-chevron-down model-chevron" />
        </button>
        <div v-if="modelMenuOpen" class="model-dropdown">
          <div class="dropdown-title">切换模型</div>
          <div
            v-for="m in availableModels"
            :key="m"
            class="model-drop-item"
            :class="{ active: m === currentModel, switching: switchingModel === m }"
            @click="handleSwitch(m)"
          >
            <i :class="m === cloudModelName ? 'fas fa-cloud' : 'fas fa-cube'" />
            <span>{{ m }}</span>
            <i v-if="m === currentModel && !isCloudMode" class="fas fa-check model-check" />
            <i v-if="switchingModel === m" class="fas fa-circle-notch fa-spin model-check" />
          </div>
          <div v-if="availableModels.length === 0" class="model-drop-empty">暂无可用模型</div>
        </div>
      </div>

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
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
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

// ── 模型切换 ──────────────────────────────────────────────
const modelMenuOpen   = ref(false)
const modelMenuRef    = ref(null)
const switchingModel  = ref('')
const modelStatus     = computed(() => store.modelStatus)
const currentModel    = computed(() => store.currentModel)
const availableModels = computed(() => store.availableModels)
const cloudModelName  = computed(() => store.cloudModel)
const isCloudMode     = computed(() => modelStatus.value?.includes?.('☁') ?? false)

const handleSwitch = async (m) => {
  if (m === currentModel.value || switchingModel.value) return
  switchingModel.value = m
  modelMenuOpen.value  = false
  await store.switchModel(m)
  switchingModel.value = ''
}

const handleClickOutsideModel = (e) => {
  if (modelMenuRef.value && !modelMenuRef.value.contains(e.target)) modelMenuOpen.value = false
}

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
onMounted(() => {
  document.addEventListener('click', handleClickOutsideModel)
  store.loadModels()
  applyTheme(isDark.value)
})

watch(isConnected, (connected) => {
  if (connected && store.availableModels.length === 0) store.loadModels()
})
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutsideModel)
})
</script>

<style scoped>
/* 桌面端隐藏汉堡按钮 */
.mobile-menu-btn {
  display: none;
  background: none;
  border: none;
  font-size: 1.2rem;
  color: #667eea;
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
  background: #2c3e50;
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
  background: white;
  border-bottom: 1px solid #e1e5e9;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.header-left .page-title {
  font-size: 1.15rem;
  color: #333;
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 500;
}
.header-left .page-title i { color: #667eea; }

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
.status-dot.connected           { background: #4caf50; }
.status-dot.disconnected-sudden { background: #f44336; animation: pulse-dot 1.5s infinite; }
.status-dot.disconnected-init   { background: #bbb; }
.status-text.connected          { color: #2e7d32; }
.status-text.disconnected-sudden { color: #c62828; }
.status-text.disconnected-init   { color: #999; }
@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0.4; }
}

.theme-btn {
  width: 34px; height: 34px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  color: #667; cursor: pointer; font-size: 0.9rem;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.theme-btn:hover { border-color: #667eea; color: #667eea; }

/* ── 模型切换入口 ─────────────────────────────────────── */
.model-entry { position: relative; }
.model-btn {
  height: 34px; padding: 0 10px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  color: #555; cursor: pointer; font-size: 0.82rem;
  display: flex; align-items: center; gap: 6px;
  max-width: 160px; transition: all 0.2s;
}
.model-btn:hover, .model-btn.open { border-color: #667eea; color: #667eea; }
.model-btn.cloud { border-color: #c7d2fe; color: #4f46e5; background: #f0f2ff; }
.model-btn-text {
  max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.model-chevron { font-size: 0.68rem; opacity: 0.6; flex-shrink: 0; }

.model-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  min-width: 180px;
  background: white;
  border: 1px solid #e0e3e8;
  border-radius: 10px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.1);
  z-index: 100;
  overflow: hidden;
}
.model-drop-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  font-size: 0.85rem;
  color: #444;
  cursor: pointer;
  transition: background 0.15s;
}
.model-drop-item:hover { background: #f5f6ff; color: #667eea; }
.model-drop-item.active { background: #f0f2ff; color: #4f46e5; font-weight: 500; }
.model-drop-item.switching { opacity: 0.7; pointer-events: none; }
.model-drop-item i:first-child { color: #bbb; font-size: 0.78rem; width: 14px; text-align: center; flex-shrink: 0; }
.model-drop-item.active i:first-child, .model-drop-item:hover i:first-child { color: #667eea; }
.model-check { margin-left: auto; font-size: 0.75rem; color: #667eea; flex-shrink: 0; }
.model-drop-empty { padding: 10px 14px; font-size: 0.82rem; color: #aaa; }

@media (max-width: 768px) { .model-entry { display: none; } }

@media (max-width: 768px) {
  .header { padding: 0 12px; }
  .page-title { font-size: 1rem; }
}
</style>