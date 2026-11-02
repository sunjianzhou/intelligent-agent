<template>
  <div class="sidebar">
    <!-- Logo -->
    <div class="logo">
      <h1><i class="fas fa-robot"></i> 智能体</h1>
    </div>

    <!-- 高频操作区 -->
    <nav class="nav-section">
      <div class="nav-section-label">常用</div>
      <router-link
        v-for="item in NAV_ITEMS"
        :key="item.name"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.name) }"
      >
        <i :class="item.icon"></i>
        <span>{{ item.label }}</span>
      </router-link>
    </nav>

    <!-- 管理后台（可折叠，合并原"配置"+"系统"两组） -->
    <nav class="nav-section admin-group">
      <button class="nav-section-toggle" type="button" @click="adminExpanded = !adminExpanded">
        <span class="nav-section-label">管理后台</span>
        <i class="fas fa-chevron-right toggle-caret" :class="{ expanded: adminExpanded }"></i>
      </button>
      <div v-show="adminExpanded" class="admin-group-items">
        <router-link
          v-for="item in ADMIN_ITEMS"
          :key="item.name"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.name) }"
        >
          <i :class="item.icon"></i>
          <span>{{ item.label }}</span>
        </router-link>
      </div>
    </nav>

    <!-- 历史会话 -->
    <div class="history-section">
      <div class="history-header">
        <span class="history-title"><i class="fas fa-history" /> 历史</span>
        <button class="new-chat-btn" @click="newSession" title="新对话">
          <i class="fas fa-plus" />
        </button>
      </div>
      <div class="history-list">
        <div
          v-for="s in sessionStore.sessions.slice(0, 12)"
          :key="s.id"
          class="history-item"
          :class="{ active: s.id === sessionStore.activeId }"
          @click="openSession(s.id)"
        >
          <span class="history-text">{{ s.title || '新对话' }}</span>
          <button class="del-session-btn" @click.stop="deleteSession(s.id)">
            <i class="fas fa-times" />
          </button>
        </div>
        <div v-if="sessionStore.sessions.length === 0" class="history-empty">暂无记录</div>
      </div>
    </div>

    <!-- 用户信息 -->
    <div class="user-info">
      <div class="user-avatar"><i class="fas fa-user" /></div>
      <div class="user-details">
        <div class="user-name">{{ authStore.username || '访客' }}</div>
        <div class="user-status">在线</div>
      </div>
      <button class="logout-btn" @click="logout" title="退出登录">
        <i class="fas fa-sign-out-alt" />
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLocalSessionStore } from '@/stores/localSession'
import { useWebSocketStore } from '@/stores/websocket'
import { NAV_ITEMS, ADMIN_ITEMS } from '@/config/routes.config'

const authStore    = useAuthStore()
const router       = useRouter()
const sessionStore = useLocalSessionStore()
const wsStore      = useWebSocketStore()

const logout = () => {
  authStore.logout()
  router.push('/login')
}

const newSession = () => {
  // 若已在聊天页，用信号通知 ChatView 执行完整的新开对话逻辑（清消息+清记忆+新会话）
  // 若不在聊天页，先导航过去再触发
  if (router.currentRoute.value.path === '/chat') {
    wsStore.triggerNewSession()
  } else {
    router.push('/chat').then(() => wsStore.triggerNewSession())
  }
}

const openSession = async (id) => {
  await sessionStore.resumeSession(id)
  if (router.currentRoute.value.path === '/chat') {
    wsStore.triggerOpenSession(id)
  } else {
    router.push('/chat').then(() => wsStore.triggerOpenSession(id))
  }
}

const deleteSession = (id) => sessionStore.removeSession(id)

onMounted(() => sessionStore.loadSessions())

const route = useRoute()

// 当前路由若属于"管理后台"分组，默认展开该分组，避免选中项被隐藏
const adminExpanded = ref(ADMIN_ITEMS.some((item) => item.name === route.name))

const isActive = computed(() => (name) => route.name === name)
</script>

<style scoped>
.sidebar {
  width: 220px;
  background: var(--color-sidebar-bg);
  color: var(--color-sidebar-text);
  padding: var(--space-5) var(--space-4);
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow-y: auto;
  scrollbar-width: none;
}
.sidebar::-webkit-scrollbar { display: none; }

.logo {
  text-align: center;
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-surface-on-dark);
  flex-shrink: 0;
}
.logo h1 {
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  letter-spacing: var(--tracking-wide);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
}
.logo i { color: var(--color-chrome-accent); }

/* ── 导航分区 ─────────────────────────────────────────── */
.nav-section {
  flex-shrink: 0;
  margin-bottom: var(--space-1);
}
.nav-section-label {
  font-size: var(--text-xs);
  color: var(--color-text-on-dark-faint);
  text-transform: uppercase;
  letter-spacing: var(--tracking-wider);
  padding: var(--space-1-5) var(--space-2-5) var(--space-1);
}
.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  color: var(--color-sidebar-text);
  text-decoration: none;
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-0-5);
  border-left: 2px solid transparent;
  transition: var(--transition-interactive);
  font-size: var(--text-base);
}
.nav-item:hover { background: var(--color-surface-on-dark-subtle); color: var(--color-chrome-text-strong); }
.nav-item.active {
  background: var(--color-surface-on-dark);
  color: var(--color-chrome-text-strong);
  border-left-color: var(--color-chrome-accent);
}
.nav-item i {
  width: 18px;
  text-align: center;
  font-size: var(--text-base);
  flex-shrink: 0;
}
.nav-item.active i { color: var(--color-chrome-accent); }

/* 分区间分隔线 */
.nav-section + .nav-section {
  border-top: 1px solid var(--color-surface-on-dark-subtle);
  padding-top: var(--space-2);
  margin-top: var(--space-1);
}

/* ── 管理后台折叠分组 ─────────────────────────────────── */
.nav-section-toggle {
  width: 100%;
  display: flex; align-items: center; justify-content: space-between;
  background: none; border: none; cursor: pointer;
  padding: var(--space-1) var(--space-2-5) var(--space-1);
  /* 不写 font-size 会回退到浏览器默认按钮字号（13.33px），与 15px 导航项不成节奏 */
  font-size: var(--text-xs);
}
.nav-section-toggle .nav-section-label { padding: 0; }
.toggle-caret {
  font-size: var(--text-2xs);
  color: var(--color-text-on-dark-faint);
  transition: transform var(--duration-base);
}
.toggle-caret.expanded { transform: rotate(90deg); }
.admin-group-items { display: flex; flex-direction: column; }

/* ── 历史会话 ─────────────────────────────────────────── */
.history-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin-top: var(--space-2);
  border-top: 1px solid var(--color-surface-on-dark-subtle);
  padding-top: var(--space-2-5);
  min-height: 60px;
}
.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-1) var(--space-1-5);
  flex-shrink: 0;
}
.history-title {
  font-size: var(--text-2xs);
  color: var(--color-text-on-dark-faint);
  text-transform: uppercase;
  letter-spacing: var(--tracking-wider);
}
.new-chat-btn {
  background: var(--color-surface-on-dark-subtle);
  border: none; color: var(--color-text-on-dark-soft);
  width: 28px; height: 28px; border-radius: var(--radius-sm);
  cursor: pointer; font-size: var(--text-xs);
  display: flex; align-items: center; justify-content: center;
  transition: var(--transition-interactive);
}
.new-chat-btn:hover { background: var(--color-chrome-accent-soft); color: var(--color-chrome-accent); }

.history-list {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--color-surface-on-dark) transparent;
}
.history-item {
  display: flex;
  align-items: center;
  padding: var(--space-1-5) var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
  gap: var(--space-1);
  transition: background var(--duration-fast);
  margin-bottom: var(--space-0-5);
}
.history-item:hover { background: var(--color-surface-on-dark-subtle); }
.history-item.active { background: var(--color-chrome-accent-soft); }
.history-text {
  flex: 1;
  font-size: var(--text-xs);
  color: var(--color-text-on-dark-soft);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.history-item.active .history-text { color: var(--color-chrome-accent); }
.del-session-btn {
  background: none; border: none;
  color: var(--color-surface-on-dark-strong);
  cursor: pointer; font-size: var(--text-2xs);
  padding: var(--space-1-5) var(--space-2); border-radius: var(--radius-xs);
  opacity: 0; transition: opacity var(--duration-fast), color var(--duration-fast);
}
.history-item:hover .del-session-btn { opacity: 1; }
.del-session-btn:hover { color: var(--color-chrome-danger); }
.history-empty {
  font-size: var(--text-xs);
  color: var(--color-text-on-dark-faint);
  padding: var(--space-1-5) var(--space-2);
  text-align: center;
}

/* ── 用户信息 ─────────────────────────────────────────── */
.user-info {
  padding: var(--space-3-5) 0 0;
  border-top: 1px solid var(--color-surface-on-dark);
  display: flex;
  align-items: center;
  gap: var(--space-2-5);
  flex-shrink: 0;
}
.user-avatar {
  width: 34px; height: 34px;
  background: var(--color-surface-on-dark);
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-md);
}
.user-details { display: flex; flex-direction: column; }
.user-name { font-weight: var(--weight-medium); font-size: var(--text-sm); }
.user-status { font-size: var(--text-xs); color: var(--color-chrome-accent); opacity: 0.8; }
.logout-btn {
  margin-left: auto; background: none; border: none;
  color: var(--color-text-on-dark-faint); cursor: pointer; font-size: var(--text-base);
  padding: var(--space-1) var(--space-1-5); border-radius: var(--radius-xs); transition: color var(--duration-base);
}
.logout-btn:hover { color: var(--color-chrome-danger); }

@media (max-width: 768px) {
  .sidebar { display: none; }
}
</style>
