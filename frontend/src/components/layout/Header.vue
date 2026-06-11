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
      <!-- 管理后台入口 -->
      <div class="admin-entry" ref="adminMenuRef">
        <button class="admin-btn" :class="{ open: adminMenuOpen }" @click="adminMenuOpen = !adminMenuOpen" title="管理后台">
          <i class="fas fa-cog" />
        </button>
        <div v-if="adminMenuOpen" class="admin-dropdown">
          <div class="dropdown-title">管理后台</div>
          <router-link v-for="item in adminItems" :key="item.name" :to="item.path" class="admin-item" @click="adminMenuOpen = false">
            <i :class="item.icon" />
            <span>{{ item.label }}</span>
          </router-link>
        </div>
      </div>

      <!-- 连接状态 -->
      <div class="connection-status">
        <span class="status-dot" :class="isConnected ? 'connected' : 'disconnected'" />
        <span class="status-text" :class="isConnected ? 'connected' : 'disconnected'">
          {{ connectionStatus }}
        </span>
      </div>

      <!-- 暗色主题切换（WANT-011） -->
      <button class="theme-btn" :title="isDark ? '切换浅色主题' : '切换暗色主题'" @click="toggleTheme">
        <i :class="isDark ? 'fas fa-sun' : 'fas fa-moon'" />
      </button>

      <!-- 清空聊天 -->
      <button v-if="showClearButton" class="btn btn-secondary" @click="clearChat">
        <i class="fas fa-trash" />
        清空
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useWebSocketStore } from '@/stores/websocket'

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

const navItems = [
  { name: 'chat',        label: '聊天',   icon: 'fas fa-comment',      path: '/chat' },
  { name: 'role-editor', label: '角色配置', icon: 'fas fa-id-card',    path: '/roles/editor' },
  { name: 'memory',      label: '记忆',   icon: 'fas fa-brain',        path: '/memory' },
  { name: 'project',     label: '项目',   icon: 'fas fa-folder-open',  path: '/project' },
  { name: 'admin-system', label: '系统',  icon: 'fas fa-info-circle',  path: '/admin/system' },
]

const adminNavItems = [
  { name: 'admin-tasks',  label: '任务管理', icon: 'fas fa-tasks',       path: '/admin/tasks' },
  { name: 'admin-tools',  label: '工具管理', icon: 'fas fa-tools',       path: '/admin/tools' },
  { name: 'admin-skills', label: 'Skill 管理', icon: 'fas fa-magic',     path: '/admin/skills' },
  { name: 'admin-system', label: '系统信息', icon: 'fas fa-info-circle', path: '/admin/system' },
  { name: 'admin-stats',  label: '统计分析', icon: 'fas fa-chart-bar',   path: '/admin/stats' },
]

// ── 页面配置 ──────────────────────────────────────────────
const pageConfigs = {
  chat:          { title: '与智能体对话', icon: 'fas fa-comment' },
  'role-editor': { title: '角色配置',     icon: 'fas fa-id-card' },
  memory:        { title: '我的记忆',     icon: 'fas fa-brain' },
  project:       { title: '项目文件',     icon: 'fas fa-folder-open' },
  'admin-tools':  { title: '工具管理',   icon: 'fas fa-tools' },
  'admin-skills': { title: 'Skill 管理', icon: 'fas fa-magic' },
  'admin-tasks':  { title: '任务管理',   icon: 'fas fa-tasks' },
  'admin-system': { title: '系统信息',   icon: 'fas fa-info-circle' },
  'admin-stats':  { title: '统计分析',   icon: 'fas fa-chart-bar' },
}
const pageConfig  = computed(() => pageConfigs[route.name] || { title: '智能体', icon: 'fas fa-robot' })
const pageTitle   = computed(() => pageConfig.value.title)
const pageIcon    = computed(() => pageConfig.value.icon)

// ── Store 数据 ────────────────────────────────────────────
const isConnected      = computed(() => store.isConnected)
const connectionStatus = computed(() => store.connectionStatus)
// ── 显示控制 ──────────────────────────────────────────────
const showClearButton = computed(() => route.name === 'chat')

// ── 管理后台入口 ──────────────────────────────────────────
const adminMenuOpen = ref(false)
const adminMenuRef  = ref(null)
const adminItems = [
  { name: 'admin-tasks',  label: '任务管理', icon: 'fas fa-tasks',       path: '/admin/tasks' },
  { name: 'admin-tools',  label: '工具管理', icon: 'fas fa-tools',       path: '/admin/tools' },
  { name: 'admin-skills', label: 'Skill 管理', icon: 'fas fa-magic',     path: '/admin/skills' },
  { name: 'admin-system', label: '系统信息', icon: 'fas fa-info-circle', path: '/admin/system' },
  { name: 'admin-stats',  label: '统计分析', icon: 'fas fa-chart-bar',   path: '/admin/stats' },
]

const handleClickOutside = (e) => {
  if (adminMenuRef.value && !adminMenuRef.value.contains(e.target)) {
    adminMenuOpen.value = false
  }
}

// ── 清空聊天 ──────────────────────────────────────────────
const clearChat = async () => {
  try {
    await ElMessageBox.confirm('清空后将同时清除 AI 的对话记忆，无法恢复。', '确认清空对话', {
      confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning',
      confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  await store.clearMessages()
  ElMessage({ message: '对话已清空', type: 'success', duration: 1500 })
}

// ── 生命周期 ──────────────────────────────────────────────
onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  store.loadModels()
  applyTheme(isDark.value)  // 初始化主题
})

watch(isConnected, (connected) => {
  if (connected && store.availableModels.length === 0) store.loadModels()
})
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
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
.status-dot.connected    { background: #4caf50; }
.status-dot.disconnected { background: #f44336; }
.status-text.connected   { color: #2e7d32; }
.status-text.disconnected { color: #c62828; }

/* ── 清空按钮 ────────────────────────────────────────── */
.btn {
  padding: 7px 14px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.88rem;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s;
}
.btn-secondary { background: #f0f2f5; color: #555; }
.btn-secondary:hover { background: #e4e6ea; }

.theme-btn {
  width: 34px; height: 34px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  color: #667; cursor: pointer; font-size: 0.9rem;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.theme-btn:hover { border-color: #667eea; color: #667eea; }

/* ── 管理后台入口 ─────────────────────────────────────── */
.admin-entry { position: relative; }
.admin-btn {
  width: 34px; height: 34px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  color: #888; cursor: pointer; font-size: 0.9rem;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.admin-btn:hover, .admin-btn.open { border-color: #667eea; color: #667eea; }
.admin-btn.open i { animation: spin-slow 2s linear infinite; }
@keyframes spin-slow { to { transform: rotate(360deg); } }

.admin-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  min-width: 160px;
  background: white;
  border: 1px solid #e0e3e8;
  border-radius: 10px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.1);
  z-index: 100;
  overflow: hidden;
}
.admin-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  font-size: 0.88rem;
  color: #444;
  text-decoration: none;
  transition: background 0.15s;
}
.admin-item:hover { background: #f5f6ff; color: #667eea; }
.admin-item i { color: #bbb; font-size: 0.8rem; width: 14px; text-align: center; }
.admin-item:hover i { color: #667eea; }

@media (max-width: 768px) {
  .admin-entry { display: none; }
  .header { padding: 0 12px; }
  .page-title { font-size: 1rem; }
}
</style>