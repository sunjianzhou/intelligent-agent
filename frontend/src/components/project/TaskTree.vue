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
  background: white;
  border-radius: 8px;
  border: 1px solid #e8eaed;
  overflow: hidden;
}

.tree-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.tree-title {
  font-size: 0.88rem;
  font-weight: 600;
  color: #4a5568;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.add-btn {
  background: #f0f4ff;
  color: #667eea;
  border: 1px solid #d0d8ff;
  border-radius: 6px;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 0.78rem;
  transition: background 0.15s;
}
.add-btn:hover:not(:disabled) { background: #e0e7ff; }
.add-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.add-task-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-bottom: 1px solid #f5f5f5;
  background: #fafbff;
}

.add-task-input {
  flex: 1;
  border: 1px solid #d0d8ff;
  border-radius: 6px;
  padding: 5px 9px;
  font-size: 0.82rem;
  color: #333;
  outline: none;
}
.add-task-input:focus { border-color: #667eea; }

.add-confirm-btn, .add-cancel-btn {
  border: none;
  border-radius: 5px;
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 0.75rem;
}
.add-confirm-btn { background: #667eea; color: white; }
.add-confirm-btn:hover { background: #5a6fd6; }
.add-cancel-btn  { background: #f0f0f0; color: #888; }
.add-cancel-btn:hover  { background: #e0e0e0; }

.decompose-btn {
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 0.78rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: background 0.15s;
}
.decompose-btn:hover:not(:disabled) { background: #5a6fd6; }
.decompose-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.tree-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #bbb;
  font-size: 0.85rem;
  padding: 20px;
}
.empty-icon { font-size: 2rem; }

.tree-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.decompose-input-wrap {
  padding: 8px 14px;
}
.decompose-input-wrap.inline {
  border-bottom: 1px solid #f5f5f5;
}

.decompose-input {
  width: 100%;
  border: 1px solid #e0e3e8;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 0.82rem;
  color: #555;
  outline: none;
  transition: border-color 0.15s;
}
.decompose-input:focus { border-color: #667eea; }
.decompose-input::placeholder { color: #ccc; }

.error-hint {
  padding: 6px 14px 10px;
  font-size: 0.78rem;
  color: #e53935;
}
</style>
