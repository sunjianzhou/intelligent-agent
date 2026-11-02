<template>
  <div class="task-tree">
    <div class="tree-header">
      <span class="tree-title">任务分解</span>
      <div class="header-actions">
        <button class="add-btn" :disabled="!projectId" title="手动添加任务" @click="showAddInput = !showAddInput">
          <i class="fas fa-plus" />
        </button>
        <button class="decompose-btn" :disabled="decomposing || !projectId" @click="decomposeTask">
          <i class="fas fa-sitemap" />
          {{ decomposing ? '分解中…' : 'AI 分解' }}
        </button>
      </div>
    </div>

    <!-- 手动添加任务行 -->
    <div v-if="showAddInput" class="add-task-row">
      <input
        ref="addInputRef"
        v-model="addTitle"
        class="add-task-input"
        placeholder="任务标题，Enter 确认，Esc 取消"
        @keydown.enter="confirmAdd"
        @keydown.esc="cancelAdd"
      />
      <button class="add-confirm-btn" @click="confirmAdd"><i class="fas fa-check" /></button>
      <button class="add-cancel-btn"  @click="cancelAdd"><i class="fas fa-times" /></button>
    </div>

    <!-- Empty state -->
    <div v-if="!tasks.length && !showAddInput" class="tree-empty">
      <i class="fas fa-sitemap empty-icon" />
      <p>暂无任务，点击「AI 分解」自动生成，或「+」手动添加</p>
      <div class="decompose-input-wrap">
        <input
          v-model="decomposeInput"
          class="decompose-input"
          placeholder="描述要分解的任务（留空则基于规格文档）…"
          @keydown.enter="decomposeTask"
        />
      </div>
    </div>

    <!-- Task tree -->
    <div v-else-if="tasks.length" class="tree-body">
      <div class="decompose-input-wrap inline">
        <input
          v-model="decomposeInput"
          class="decompose-input"
          placeholder="重新描述任务以覆盖现有分解…"
          @keydown.enter="decomposeTask"
        />
      </div>
      <TaskNode
        v-for="task in tasks"
        :key="task.id"
        :task="task"
        :depth="0"
      />
    </div>

    <div v-if="decomposeError" class="error-hint">{{ decomposeError }}</div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { useProjectStore } from '@/stores/project'
import { decomposeProjectTasks } from '@/services/api'
import TaskNode from './TaskNode.vue'

const props = defineProps({
  projectId: { type: String, default: null },
})

const projectStore    = useProjectStore()
const decomposing     = ref(false)
const decomposeInput  = ref('')
const decomposeError  = ref('')

// 手动添加任务
const showAddInput = ref(false)
const addTitle     = ref('')
const addInputRef  = ref(null)

const tasks = computed(() => {
  if (!props.projectId) return []
  const proj = projectStore.projects.find(p => p.id === props.projectId)
  return proj?.task_tree?.root_tasks || []
})

async function confirmAdd() {
  const title = addTitle.value.trim()
  if (!title || !props.projectId) return
  await projectStore.addManualTask(props.projectId, title)
  addTitle.value = ''
  showAddInput.value = false
}

function cancelAdd() {
  addTitle.value = ''
  showAddInput.value = false
}

// 打开输入框时自动聚焦
const _watchShow = () => {
  if (showAddInput.value) nextTick(() => addInputRef.value?.focus())
}
import { watch } from 'vue'
watch(showAddInput, _watchShow)

async function decomposeTask() {
  if (!props.projectId || decomposing.value) return
  decomposing.value  = true
  decomposeError.value = ''
  try {
    const result = await decomposeProjectTasks(props.projectId, decomposeInput.value || null)
    if (result?.task_tree) {
      await projectStore.updateTaskTree(props.projectId, result.task_tree)
    } else {
      decomposeError.value = result?.error || '分解失败，请重试'
    }
  } catch {
    decomposeError.value = '请求失败，请检查网络连接'
  } finally {
    decomposing.value = false
  }
}
</script>

<style scoped>
.task-tree {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--color-surface);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  overflow: hidden;
}

.tree-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2-5) var(--space-3-5);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.tree-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-secondary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
}

.add-btn {
  background: var(--color-surface-raised);
  color: var(--color-primary);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-sm);
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: var(--text-xs);
  transition: background var(--duration-fast);
}
.add-btn:hover:not(:disabled) { background: var(--color-primary-soft); }
.add-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.add-task-row {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3-5);
  border-bottom: 1px solid var(--color-surface-sunken);
  background: var(--color-primary-soft);
}

.add-task-input {
  flex: 1;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2);
  font-size: var(--text-sm-2);
  color: var(--color-text);
  outline: none;
}
.add-task-input:focus { border-color: var(--color-primary); }

.add-confirm-btn, .add-cancel-btn {
  border: none;
  border-radius: var(--radius-xs);
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: var(--text-xs);
}
.add-confirm-btn { background: var(--color-primary); color: var(--color-text-on-primary); }
.add-confirm-btn:hover { background: var(--color-primary-hover); }
.add-cancel-btn  { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
.add-cancel-btn:hover  { background: var(--color-border); }

.decompose-btn {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border: none;
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-xs);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  transition: background var(--duration-fast);
}
.decompose-btn:hover:not(:disabled) { background: var(--color-primary-hover); }
.decompose-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.tree-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  padding: var(--space-4-5);
}
.empty-icon { font-size: 2rem; }

.tree-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-2) 0;
}

.decompose-input-wrap {
  padding: var(--space-2) var(--space-3-5);
}
.decompose-input-wrap.inline {
  border-bottom: 1px solid var(--color-surface-sunken);
}

.decompose-input {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1-5) var(--space-2-5);
  font-size: var(--text-sm-2);
  color: var(--color-text);
  outline: none;
  transition: border-color var(--duration-fast);
}
.decompose-input:focus { border-color: var(--color-primary); }
.decompose-input::placeholder { color: var(--color-text-muted); }

.error-hint {
  padding: var(--space-1-5) var(--space-3-5) var(--space-2-5);
  font-size: var(--text-xs);
  color: var(--color-danger);
}
</style>
