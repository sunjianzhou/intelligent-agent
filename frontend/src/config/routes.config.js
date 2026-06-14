/**
 * 路由导航单一来源配置。
 * Sidebar.vue / Header.vue 均从此处 import，新增页面只需改这一个文件。
 */

// 高频操作区（侧边栏第一区 + 移动端主导航）
export const NAV_ITEMS = [
  { name: 'chat',        label: '聊天',   icon: 'fas fa-comment',     path: '/chat' },
  { name: 'role-editor', label: '角色配置', icon: 'fas fa-id-card',   path: '/roles/editor' },
  { name: 'memory',      label: '记忆',   icon: 'fas fa-brain',       path: '/memory' },
  { name: 'project',     label: '项目',   icon: 'fas fa-folder-open', path: '/project' },
]

// 低频配置区（侧边栏第二区）
export const CONFIG_ITEMS = [
  { name: 'admin-tools',  label: '工具管理',   icon: 'fas fa-tools',     path: '/admin/tools' },
  { name: 'admin-skills', label: 'Skill 管理', icon: 'fas fa-magic',     path: '/admin/skills' },
  { name: 'admin-mcp',    label: 'MCP 配置',   icon: 'fas fa-plug',      path: '/admin/mcp' },
]

// 系统与数据区（侧边栏第三区）
export const SYSTEM_ITEMS = [
  { name: 'admin-models', label: '模型管理', icon: 'fas fa-robot',         path: '/admin/models' },
  { name: 'admin-tasks',  label: '任务管理', icon: 'fas fa-tasks',         path: '/admin/tasks' },
  { name: 'admin-logs',   label: '操作日志', icon: 'fas fa-clipboard-list', path: '/admin/logs' },
  { name: 'admin-stats',  label: '统计分析', icon: 'fas fa-chart-bar',     path: '/admin/stats' },
  { name: 'admin-system', label: '系统信息', icon: 'fas fa-info-circle',   path: '/admin/system' },
]

// 合并所有后台条目（移动端抽屉管理区复用）
export const ADMIN_ITEMS = [...CONFIG_ITEMS, ...SYSTEM_ITEMS]

// 页面标题与图标（Header 组件根据路由 name 查找）
export const PAGE_CONFIGS = {
  chat:           { title: '与智能体对话', icon: 'fas fa-comment' },
  'role-editor':  { title: '角色配置',     icon: 'fas fa-id-card' },
  memory:         { title: '我的记忆',     icon: 'fas fa-brain' },
  project:        { title: '项目文件',     icon: 'fas fa-folder-open' },
  'admin-tools':  { title: '工具管理',     icon: 'fas fa-tools' },
  'admin-skills': { title: 'Skill 管理',  icon: 'fas fa-magic' },
  'admin-mcp':    { title: 'MCP 配置',    icon: 'fas fa-plug' },
  'admin-tasks':  { title: '任务管理',     icon: 'fas fa-tasks' },
  'admin-logs':   { title: '操作日志',     icon: 'fas fa-clipboard-list' },
  'admin-system': { title: '系统信息',     icon: 'fas fa-info-circle' },
  'admin-stats':  { title: '统计分析',     icon: 'fas fa-chart-bar' },
  'admin-models': { title: '模型管理',     icon: 'fas fa-robot' },
}
