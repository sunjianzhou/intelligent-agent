/**
 * 路由导航单一来源配置。
 * Sidebar.vue / Header.vue 均从此处 import，新增页面只需改这一个文件。
 */

// 主导航（侧边栏 + 移动端主导航公用的 4 个核心入口）
export const NAV_ITEMS = [
  { name: 'chat',        label: '聊天',   icon: 'fas fa-comment',     path: '/chat' },
  { name: 'role-editor', label: '角色配置', icon: 'fas fa-id-card',   path: '/roles/editor' },
  { name: 'memory',      label: '记忆',   icon: 'fas fa-brain',       path: '/memory' },
  { name: 'project',     label: '项目',   icon: 'fas fa-folder-open', path: '/project' },
]

// 管理后台条目（桌面端下拉菜单 + 移动端抽屉管理区 共用）
export const ADMIN_ITEMS = [
  { name: 'admin-tasks',  label: '任务管理',  icon: 'fas fa-tasks',       path: '/admin/tasks' },
  { name: 'admin-tools',  label: '工具管理',  icon: 'fas fa-tools',       path: '/admin/tools' },
  { name: 'admin-skills', label: 'Skill 管理', icon: 'fas fa-magic',      path: '/admin/skills' },
  { name: 'admin-system', label: '系统信息',  icon: 'fas fa-info-circle', path: '/admin/system' },
  { name: 'admin-stats',  label: '统计分析',  icon: 'fas fa-chart-bar',   path: '/admin/stats' },
]

// 页面标题与图标（Header 组件根据路由 name 查找）
export const PAGE_CONFIGS = {
  chat:           { title: '与智能体对话', icon: 'fas fa-comment' },
  'role-editor':  { title: '角色配置',     icon: 'fas fa-id-card' },
  memory:         { title: '我的记忆',     icon: 'fas fa-brain' },
  project:        { title: '项目文件',     icon: 'fas fa-folder-open' },
  'admin-tools':  { title: '工具管理',     icon: 'fas fa-tools' },
  'admin-skills': { title: 'Skill 管理',  icon: 'fas fa-magic' },
  'admin-tasks':  { title: '任务管理',     icon: 'fas fa-tasks' },
  'admin-system': { title: '系统信息',     icon: 'fas fa-info-circle' },
  'admin-stats':  { title: '统计分析',     icon: 'fas fa-chart-bar' },
}
