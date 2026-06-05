import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  makeProject, saveProject, getProject,
  listProjects, deleteProject,
} from '@/services/localDB'
import { putProjectSpec } from '@/services/api'

const ACTIVE_PROJECT_KEY = 'agent_active_project_id'

export const useProjectStore = defineStore('project', () => {
  const projects        = ref([])
  // 不在 store 初始化时读 localStorage，防止用户切换时上一个用户的项目 ID 污染新用户
  // 在登录确认后由 App.vue 调用 restoreActiveProject() 读取
  const activeProjectId = ref(null)

  const activeProject = computed(() =>
    projects.value.find(p => p.id === activeProjectId.value) ?? null
  )

  // ── CRUD ────────────────────────────────────────────────────────────────────

  async function loadProjects() {
    projects.value = await listProjects()
  }

  async function createProject(title) {
    const project = makeProject(title)
    await saveProject(project)
    projects.value.unshift(project)
    return project
  }

  async function activateProject(id) {
    activeProjectId.value = id
    localStorage.setItem(ACTIVE_PROJECT_KEY, id)
    const p = await getProject(id)
    if (!p) return
    // 更新内存列表中的数据
    const idx = projects.value.findIndex(x => x.id === id)
    if (idx !== -1) projects.value[idx] = p
    else projects.value.unshift(p)
    // 若有未同步的 spec，重试一次
    if (!p.synced && p.spec?.content) {
      await _syncSpec(p).catch(() => {})
    }
  }

  async function deactivateProject() {
    activeProjectId.value = null
    localStorage.removeItem(ACTIVE_PROJECT_KEY)
  }

  async function removeProject(id) {
    await deleteProject(id)
    projects.value = projects.value.filter(p => p.id !== id)
    if (activeProjectId.value === id) {
      activeProjectId.value = null
      localStorage.removeItem(ACTIVE_PROJECT_KEY)
    }
  }

  // ── 关联 session ─────────────────────────────────────────────────────────────

  async function addSessionToProject(projectId, sessionId) {
    const p = await getProject(projectId)
    if (!p) return
    if (!p.session_ids.includes(sessionId)) {
      p.session_ids.push(sessionId)
      p.updated_at = new Date().toISOString()
      await saveProject(p)
      _patchLocal(p)
    }
  }

  // ── Context summary ─────────────────────────────────────────────────────────

  async function updateContextSummary(id, summary, version) {
    const p = await getProject(id)
    if (!p) return
    p.context_summary = summary
    p.context_version = version
    p.updated_at = new Date().toISOString()
    await saveProject(p)
    _patchLocal(p)
  }

  // ── Spec ─────────────────────────────────────────────────────────────────────

  async function updateSpec(projectId, content) {
    const p = await getProject(projectId)
    if (!p) return
    p.spec.content      = content
    p.spec.version      = (p.spec.version ?? 0) + 1
    p.spec.last_updated = new Date().toISOString()
    p.updated_at        = new Date().toISOString()
    p.synced            = false
    await saveProject(p)
    _patchLocal(p)
    await _syncSpec(p)
  }

  async function _syncSpec(p) {
    try {
      await putProjectSpec({
        project_id: p.id,
        content:    p.spec.content,
        version:    p.spec.version,
      })
      p.synced = true
      await saveProject(p)
      _patchLocal(p)
    } catch {
      // 保持 synced:false，下次 activateProject 时重试
    }
  }

  // ── Task tree ────────────────────────────────────────────────────────────────

  async function addManualTask(projectId, title) {
    const p = await getProject(projectId)
    if (!p) return null
    const task = {
      id:         `task-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      title,
      status:     'pending',
      subtasks:   [],
      created_at: new Date().toISOString(),
    }
    if (!p.task_tree)             p.task_tree = { root_tasks: [], auto_decompose: false }
    if (!p.task_tree.root_tasks)  p.task_tree.root_tasks = []
    p.task_tree.root_tasks.push(task)
    p.task_tree.last_updated = new Date().toISOString()
    p.updated_at             = new Date().toISOString()
    await saveProject(p)
    _patchLocal(p)
    return task
  }

  async function updateTaskTree(projectId, taskTree) {
    const p = await getProject(projectId)
    if (!p) return
    p.task_tree           = { ...taskTree, last_updated: new Date().toISOString() }
    p.updated_at          = new Date().toISOString()
    await saveProject(p)
    _patchLocal(p)
  }

  async function markTaskDone(taskData) {
    const pid = taskData?.project_id
    if (!pid) return
    const p = await getProject(pid)
    if (!p) return
    _setTaskStatus(p.task_tree.root_tasks, taskData.task_id, 'done')
    p.task_tree.last_updated = new Date().toISOString()
    p.updated_at             = new Date().toISOString()
    await saveProject(p)
    _patchLocal(p)
  }

  async function markTaskBlocked(taskData) {
    const pid = taskData?.project_id
    if (!pid) return
    const p = await getProject(pid)
    if (!p) return
    _setTaskStatus(p.task_tree.root_tasks, taskData.task_id, 'blocked')
    p.task_tree.last_updated = new Date().toISOString()
    p.updated_at             = new Date().toISOString()
    await saveProject(p)
    _patchLocal(p)
  }

  // ── 工具方法 ─────────────────────────────────────────────────────────────────

  function _patchLocal(updated) {
    const idx = projects.value.findIndex(p => p.id === updated.id)
    if (idx !== -1) projects.value[idx] = { ...updated }
  }

  function _setTaskStatus(tasks, taskId, status) {
    if (!tasks) return
    for (const t of tasks) {
      if (t.id === taskId) { t.status = status; return }
      if (t.subtasks?.length) _setTaskStatus(t.subtasks, taskId, status)
    }
  }

  /** 登录确认后调用，从 localStorage 恢复上次激活的项目 ID */
  function restoreActiveProject() {
    const stored = localStorage.getItem(ACTIVE_PROJECT_KEY)
    if (stored) activeProjectId.value = stored
  }

  return {
    projects, activeProjectId, activeProject,
    loadProjects, createProject, activateProject, deactivateProject, removeProject,
    addSessionToProject, updateContextSummary,
    updateSpec, addManualTask, updateTaskTree, markTaskDone, markTaskBlocked,
    restoreActiveProject,
  }
})
