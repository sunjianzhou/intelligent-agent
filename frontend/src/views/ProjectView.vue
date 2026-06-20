<template>
  <div class="project-view">
    <!-- ── 左侧：项目列表 ────────────────────────────────── -->
    <div class="project-list-panel">
      <div class="panel-header">
        <span class="panel-title">项目</span>
        <button class="icon-btn add-btn" @click="showCreate = true" title="新建项目">
          <i class="fas fa-plus" />
        </button>
      </div>

      <div class="project-list">
        <div
          v-for="p in projectStore.projects"
          :key="p.id"
          class="project-item"
          :class="{ active: p.id === projectStore.activeProjectId }"
          @click="selectProject(p.id)"
        >
          <i class="fas fa-folder project-icon" />
          <span class="project-name">{{ p.title }}</span>
          <button class="icon-btn del-btn" @click.stop="removeProject(p.id)" title="删除项目">
            <i class="fas fa-trash" />
          </button>
        </div>
        <div v-if="!projectStore.projects.length" class="list-empty">
          暂无项目，点击 + 创建
        </div>
      </div>

      <!-- 新建弹窗 -->
      <div v-if="showCreate" class="create-overlay" @click.self="showCreate = false">
        <div class="create-box">
          <p class="create-label">项目名称</p>
          <input
            v-model="newTitle"
            class="create-input"
            placeholder="例：智能客服系统"
            autofocus
            @keydown.enter="createProject"
            @keydown.esc="showCreate = false"
          />
          <div class="create-actions">
            <button class="cancel-btn" @click="showCreate = false">取消</button>
            <button class="confirm-btn" :disabled="!newTitle.trim()" @click="createProject">创建</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 中间：规格编辑 ────────────────────────────────── -->
    <div class="spec-panel">
      <SpecEditor :project-id="projectStore.activeProjectId" />
    </div>

    <!-- ── 右侧：任务树 ───────────────────────────────────── -->
    <div class="task-panel">
      <TaskTree :project-id="projectStore.activeProjectId" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useProjectStore } from '@/stores/project'
import SpecEditor from '@/components/project/SpecEditor.vue'
import TaskTree   from '@/components/project/TaskTree.vue'

const projectStore = useProjectStore()
const showCreate   = ref(false)
const newTitle     = ref('')

onMounted(async () => {
  await projectStore.loadProjects()
})

async function selectProject(id) {
  await projectStore.activateProject(id)
}

async function removeProject(id) {
  await projectStore.removeProject(id)
}

async function createProject() {
  const title = newTitle.value.trim()
  if (!title) return
  const project = await projectStore.createProject(title)
  await projectStore.activateProject(project.id)
  newTitle.value = ''
  showCreate.value = false
}
</script>

<style scoped>
.project-view {
  display: flex;
  height: 100%;
  overflow: hidden;
  background: var(--color-bg);
  gap: 0;
}

/* ── 左侧面板 ── */
.project-list-panel {
  width: 200px;
  flex-shrink: 0;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  position: relative;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) 14px;
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.panel-title {
  font-size: 0.82rem;
  font-weight: 600;
  color: #4a5568;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.icon-btn {
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 5px;
  background: none;
  color: var(--color-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.78rem;
  transition: all 0.15s;
}
.add-btn:hover { background: var(--color-accent); color: white; }
.del-btn { opacity: 0; }
.del-btn:hover { background: #fce4e4; color: #e53935; opacity: 1 !important; }

.project-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px 0;
}

.project-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  transition: background 0.12s;
  border-radius: 0;
}
.project-item:hover { background: var(--color-bg); }
.project-item:hover .del-btn { opacity: 1; }
.project-item.active { background: #eef0ff; }

.project-icon {
  font-size: 0.82rem;
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.project-item.active .project-icon { color: var(--color-primary); }

.project-name {
  flex: 1;
  font-size: 0.83rem;
  color: #444;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.project-item.active .project-name { color: var(--color-primary); font-weight: 500; }

.list-empty {
  padding: 20px 14px;
  font-size: 0.78rem;
  color: var(--color-text-muted);
  text-align: center;
}

/* ── 新建弹窗 ── */
.create-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0,0,0,0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}

.create-box {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 18px;
  width: 170px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.15);
}

.create-label {
  font-size: 0.82rem;
  color: var(--color-text-secondary);
  margin-bottom: var(--space-2);
}

.create-input {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 6px 10px;
  font-size: 0.85rem;
  outline: none;
  margin-bottom: 10px;
}
.create-input:focus { border-color: var(--color-primary); }

.create-actions {
  display: flex;
  gap: var(--space-2);
  justify-content: flex-end;
}

.cancel-btn {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1) 10px;
  font-size: 0.78rem;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.confirm-btn {
  background: var(--color-primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: 4px var(--space-3);
  font-size: 0.78rem;
  cursor: pointer;
}
.confirm-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── 中间和右侧面板 ── */
.spec-panel {
  flex: 1;
  padding: var(--space-3);
  overflow: hidden;
  min-width: 0;
  border-right: 1px solid var(--color-border);
}

.task-panel {
  width: 300px;
  flex-shrink: 0;
  padding: var(--space-3) var(--space-3) var(--space-3) 0;
  overflow: hidden;
}

/* ── 移动端 ── */
@media (max-width: 768px) {
  .project-view {
    flex-direction: column;
  }
  .project-list-panel {
    width: 100%;
    height: auto;
    border-right: none;
    border-bottom: 1px solid var(--color-border);
    max-height: 140px;
  }
  .project-list {
    display: flex;
    flex-direction: row;
    flex-wrap: nowrap;
    overflow-x: auto;
  }
  .task-panel {
    width: 100%;
    padding: 0 var(--space-3) var(--space-3);
  }
}
</style>
