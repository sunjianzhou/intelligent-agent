import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  listProjects, createProject as apiCreateProject,
  getProjectById, updateProject as apiUpdateProject,
  deleteProjectApi,
  putProjectSpec,
} from '@/services/api'

const ACTIVE_PROJECT_KEY = 'agent_active_project_id'

export const useProjectStore = defineStore('project', () => {
  const projects        = ref([])
  const activeProjectId = ref(null)

  const activeProject = computed(() =>
    projects.value.find(p => p.id === activeProjectId.value) ?? null
  )

  // ── CRUD ────────────────────────────────────────────────────────────────────

  async function loadProjects() {
    const data = await listProjects()
    if (data?.projects) projects.value = data.projects
  }

  async function createProject(title) {
    const data = await apiCreateProject({ title })
    if (!data?.project) throw new Error(data?.message || '创建项目失败')
    projects.value.unshift(data.project)
    return data.project
  }

  async function activateProject(id) {
    activeProjectId.value = id
    localStorage.setItem(ACTIVE_PROJECT_KEY, id)
    const data = await getProjectById(id)
    if (!data?.project) return
    _patchLocal(data.project)
    // 若有未同步的 spec，重试一次
    const p = data.project
    if (!p.synced && p.spec?.content) {
      await _syncSpec(p).catch(() => {})
    }
  }

  async function deactivateProject() {
    activeProjectId.value = null
    localStorage.removeItem(ACTIVE_PROJECT_KEY)
  }

  async function removeProject(id) {
    await deleteProjectApi(id)
    projects.value = projects.value.filter(p => p.id !== id)
    if (activeProjectId.value === id) {
      activeProjectId.value = null
      localStorage.removeItem(ACTIVE_PROJECT_KEY)
    }
  }

  // ── 关联 session ─────────────────────────────────────────────────────────────

  async function addSessionToProject(projectId, sessionId) {
    const p = _getLocal(projectId)
    if (!p) return
    if (p.session_ids.includes(sessionId)) return
    const updated = { ...p, session_ids: [...p.session_ids, sessionId] }
    await _update(updated)
  }

  // ── Context summary ─────────────────────────────────────────────────────────

  async function updateContextSummary(id, summary, version) {
    const p = _getLocal(id)
    if (!p) return
    await _update({ ...p, context_summary: summary, context_version: version })
  }

  // ── Spec ─────────────────────────────────────────────────────────────────────

  async function updateSpec(projectId, content) {
    const p = _getLocal(projectId)
    if (!p) return
    const spec = {
      ...p.spec,
      content,
      version:      (p.spec?.version ?? 0) + 1,
      last_updated: new Date().toISOString(),
    }
    const updated = { ...p, spec, synced: false }
    await _update(updated)
    await _syncSpec(updated)
  }

  async function _syncSpec(p) {
    try {
      await putProjectSpec({
        project_id: p.id,
        content:    p.spec.content,
        version:    p.spec.version,
      })
      await _update({ ...p, synced: true })
    } catch {
      // 保持 synced:false，下次 activateProject 时重试
    }
  }

  // ── Task tree ────────────────────────────────────────────────────────────────

  async function addManualTask(projectId, title) {
    const p = _getLocal(projectId)
    if (!p) return null
    const task = {
      id:         `task-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      title,
      status:     'pending',
      subtasks:   [],
      created_at: new Date().toISOString(),
    }
    const taskTree = p.task_tree ?? { root_tasks: [], auto_decompose: false }
    const updated = {
      ...p,
      task_tree: {
        ...taskTree,
        root_tasks:   [...(taskTree.root_tasks ?? []), task],
        last_updated: new Date().toISOString(),
      },
    }
    await _update(updated)
    return task
  }

  async function updateTaskTree(projectId, taskTree) {
    const p = _getLocal(projectId)
    if (!p) return
    await _update({ ...p, task_tree: { ...taskTree, last_updated: new Date().toISOString() } })
  }

  async function markTaskDone(taskData) {
    const pid = taskData?.project_id
    if (!pid) return
    const p = _getLocal(pid)
    if (!p) return
    const cloned = JSON.parse(JSON.stringify(p))
    _setTaskStatus(cloned.task_tree?.root_tasks, taskData.task_id, 'done')
    await _update(cloned)
  }

  async function markTaskBlocked(taskData) {
    const pid = taskData?.project_id
    if (!pid) return
    const p = _getLocal(pid)
    if (!p) return
    const cloned = JSON.parse(JSON.stringify(p))
    _setTaskStatus(cloned.task_tree?.root_tasks, taskData.task_id, 'blocked')
    await _update(cloned)
  }

  // ── 工具方法 ─────────────────────────────────────────────────────────────────

  function _getLocal(id) {
    return projects.value.find(p => p.id === id) ?? null
  }

  function _patchLocal(updated) {
    const idx = projects.value.findIndex(p => p.id === updated.id)
    if (idx !== -1) projects.value[idx] = { ...updated }
    else projects.value.unshift({ ...updated })
  }

  async function _update(p) {
    const data = await apiUpdateProject(p.id, p)
    if (data?.project) _patchLocal(data.project)
    else _patchLocal(p)
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
