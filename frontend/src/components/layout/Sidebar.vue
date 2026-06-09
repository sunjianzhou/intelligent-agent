<template>
  <div class="sidebar">
    <!-- Logo -->
    <div class="logo">
      <h1><i class="fas fa-robot"></i> 智能体</h1>
    </div>
    
    <!-- 导航菜单 -->
    <nav class="nav-menu">
      <router-link 
        v-for="item in navItems" 
        :key="item.name"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.name) }"
      >
        <i :class="item.icon"></i>
        <span>{{ item.label }}</span>
      </router-link>
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
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLocalSessionStore } from '@/stores/localSession'

const authStore    = useAuthStore()
const router       = useRouter()
const sessionStore = useLocalSessionStore()

const logout = () => {
  authStore.logout()
  router.push('/login')
}

const newSession = async () => {
  await sessionStore.startNewSession()
  router.push('/chat')
}

const openSession = async (id) => {
  await sessionStore.resumeSession(id)
  router.push('/chat')
}

const deleteSession = (id) => sessionStore.removeSession(id)

onMounted(() => sessionStore.loadSessions())

const route = useRoute()

// 客户端导航项
const navItems = [
  { name: 'chat',        label: '聊天',   icon: 'fas fa-comment',      path: '/chat' },
  { name: 'personas',    label: '角色',   icon: 'fas fa-user-circle',  path: '/personas' },
  { name: 'memory',      label: '记忆',   icon: 'fas fa-brain',        path: '/memory' },
  { name: 'project',     label: '项目',   icon: 'fas fa-folder-open',  path: '/project' },
  { name: 'admin-tasks', label: '任务',   icon: 'fas fa-tasks',        path: '/admin/tasks' },
]

// 检查当前激活的导航项
const isActive = computed(() => (name) => {
  return route.name === name
})
</script>

<style scoped>
.sidebar {
  width: 250px;
  background: #2c3e50;
  color: white;
  padding: 30px 20px;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.logo {
  text-align: center;
  margin-bottom: 30px;
  padding-bottom: 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.logo h1 {
  font-size: 1.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.logo i {
  color: #4fc3a1;
}

.nav-menu {
  flex: 0 0 auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 15px;
  color: rgba(255, 255, 255, 0.8);
  text-decoration: none;
  border-radius: 8px;
  margin-bottom: 8px;
  transition: all 0.3s;
  cursor: pointer;
}

.nav-item:hover, .nav-item.active {
  background: rgba(255, 255, 255, 0.1);
  color: white;
}

.nav-item i {
  width: 20px;
  text-align: center;
  font-size: 1.1rem;
}

.nav-item.active i {
  color: #4fc3a1;
}

.user-info {
  padding: 20px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-avatar {
  width: 40px;
  height: 40px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.2rem;
}

.user-details {
  display: flex;
  flex-direction: column;
}

.user-name {
  font-weight: 500;
  font-size: 0.95rem;
}

.user-status {
  font-size: 0.8rem;
  color: #4fc3a1;
  opacity: 0.8;
}

@media (max-width: 768px) {
  .sidebar {
    display: none;
  }
}

.logout-btn {
  margin-left: auto; background: none; border: none;
  color: rgba(255,255,255,0.5); cursor: pointer; font-size: 1rem;
  padding: 4px 6px; border-radius: 4px; transition: color 0.2s;
}
.logout-btn:hover { color: #ff6b6b; }

/* ── 历史会话 ─────────────────────────────────────────── */
.history-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin: 12px 0;
  border-top: 1px solid rgba(255,255,255,0.08);
  padding-top: 12px;
}
.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 8px;
}
.history-title {
  font-size: 0.75rem;
  color: rgba(255,255,255,0.4);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.new-chat-btn {
  background: rgba(255,255,255,0.08);
  border: none; color: rgba(255,255,255,0.6);
  width: 22px; height: 22px; border-radius: 5px;
  cursor: pointer; font-size: 0.7rem;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.new-chat-btn:hover { background: #4fc3a1; color: white; }
.history-list {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(255,255,255,0.1) transparent;
}
.history-item {
  display: flex;
  align-items: center;
  padding: 7px 10px;
  border-radius: 6px;
  cursor: pointer;
  gap: 6px;
  transition: background 0.15s;
  margin-bottom: 2px;
}
.history-item:hover { background: rgba(255,255,255,0.07); }
.history-item.active { background: rgba(79,195,161,0.18); }
.history-text {
  flex: 1;
  font-size: 0.8rem;
  color: rgba(255,255,255,0.65);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.history-item.active .history-text { color: #4fc3a1; }
.del-session-btn {
  background: none; border: none;
  color: rgba(255,255,255,0.2);
  cursor: pointer; font-size: 0.65rem;
  padding: 2px 4px; border-radius: 3px;
  opacity: 0; transition: opacity 0.15s, color 0.15s;
}
.history-item:hover .del-session-btn { opacity: 1; }
.del-session-btn:hover { color: #ff6b6b; }
.history-empty {
  font-size: 0.78rem;
  color: rgba(255,255,255,0.25);
  padding: 8px 10px;
  text-align: center;
}
</style>
