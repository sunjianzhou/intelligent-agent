import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '@/views/LoginView.vue'  // 直接 import，不懒加载
import { isTokenExpired } from '@/utils/jwt'
import { useAuthStore } from '@/stores/auth'

const ChatView       = () => import('@/views/ChatView.vue')
const ToolsView      = () => import('@/views/ToolsView.vue')
const TasksView      = () => import('@/views/TasksView.vue')
const SystemView     = () => import('@/views/SystemView.vue')
const MemoryView     = () => import('@/views/MemoryView.vue')
const SkillView      = () => import('@/views/SkillView.vue')
const ProjectView    = () => import('@/views/ProjectView.vue')
const RoleEditorView = () => import('@/views/RoleEditorView.vue')
const ModelView      = () => import('@/views/ModelView.vue')
const MCPView        = () => import('@/views/MCPView.vue')
const LogView        = () => import('@/views/LogView.vue')

const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  { path: '/',      redirect: '/chat' },

  // ── 客户端路由 ──────────────────────────────────────────
  { path: '/chat',         name: 'chat',        component: ChatView,       meta: { title: '聊天' } },
  { path: '/personas',     redirect: '/roles/editor' },
  { path: '/roles/editor', name: 'role-editor', component: RoleEditorView, meta: { title: '角色配置' } },
  { path: '/memory',   name: 'memory',   component: MemoryView, meta: { title: '我的记忆' } },
  { path: '/project',  name: 'project',  component: ProjectView, meta: { title: '项目文件' } },

  // ── 管理后台路由 ────────────────────────────────────────
  { path: '/admin/tools',  name: 'admin-tools',  component: ToolsView,  meta: { title: '工具管理', admin: true } },
  { path: '/admin/skills', name: 'admin-skills', component: SkillView,  meta: { title: 'Skill 管理', admin: true } },
  { path: '/admin/mcp',    name: 'admin-mcp',    component: MCPView,    meta: { title: 'MCP 配置', admin: true } },
  { path: '/admin/tasks',  name: 'admin-tasks',  component: TasksView,  meta: { title: '任务管理', admin: true } },
  { path: '/admin/system', name: 'admin-system', component: SystemView, meta: { title: '系统信息', admin: true } },
  { path: '/admin/stats',  name: 'admin-stats',  component: () => import('@/views/StatsView.vue'), meta: { title: '统计分析', admin: true } },
  { path: '/admin/models', name: 'admin-models', component: ModelView,   meta: { title: '模型管理', admin: true } },
  { path: '/admin/logs',   name: 'admin-logs',   component: LogView,    meta: { title: '操作日志', admin: true } },

  // 旧路径重定向，保持后向兼容
  { path: '/tools',  redirect: '/admin/tools' },
  { path: '/skills', redirect: '/admin/skills' },
  { path: '/tasks',  redirect: '/admin/tasks' },
  { path: '/system', redirect: '/admin/system' },
  { path: '/stats',  redirect: '/admin/stats' },

  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - 智能体` : '智能体'

  if (to.meta.public) { next(); return }

  const token = localStorage.getItem('agent_token')
  if (!token || isTokenExpired(token)) {
    // 同步清理 authStore，避免 localStorage 与响应式状态不一致
    const authStore = useAuthStore()
    authStore.logout()
    next({ name: 'login' })
  } else {
    next()
  }
})

export default router