<template>
  <div class="task-node" :style="{ paddingLeft: depth * 16 + 'px' }">
    <div class="node-row" @click="expanded = !expanded">
      <span class="expand-toggle" v-if="task.subtasks?.length">
        <i :class="expanded ? 'fas fa-chevron-down' : 'fas fa-chevron-right'" />
      </span>
      <span v-else class="expand-placeholder" />

      <span class="status-dot" :class="task.status || 'pending'" :title="statusLabel" />

      <span class="task-name">{{ task.title || task.name }}</span>

      <span class="task-badge" :class="task.status || 'pending'">{{ statusLabel }}</span>
    </div>

    <div v-if="task.description" class="task-desc">{{ task.description }}</div>

    <div v-if="expanded && task.subtasks?.length" class="subtasks">
      <TaskNode
        v-for="sub in task.subtasks"
        :key="sub.id"
        :task="sub"
        :depth="depth + 1"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  task:  { type: Object, required: true },
  depth: { type: Number, default: 0 },
})

const expanded = ref(true)

const statusLabel = computed(() => {
  const map = { pending: '待处理', in_progress: '进行中', done: '完成', blocked: '阻塞' }
  return map[props.task.status] || '待处理'
})
</script>

<style scoped>
.task-node {
  border-left: 2px solid #f0f0f0;
  margin-left: 14px;
}
.task-node:first-child { margin-left: 0; border-left: none; }

.node-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.12s;
}
.node-row:hover { background: #f8f9fa; }

.expand-toggle {
  width: 14px;
  font-size: 0.7rem;
  color: #aaa;
  flex-shrink: 0;
}
.expand-placeholder { width: 14px; flex-shrink: 0; }

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot.pending     { background: #bbb; }
.status-dot.in_progress { background: #667eea; }
.status-dot.done        { background: #4fc3a1; }
.status-dot.blocked     { background: #e53935; }

.task-name {
  flex: 1;
  font-size: 0.85rem;
  color: #333;
  line-height: 1.4;
}

.task-badge {
  font-size: 0.68rem;
  padding: 1px 7px;
  border-radius: 10px;
  font-weight: 500;
  flex-shrink: 0;
}
.task-badge.pending     { background: #f5f5f5; color: #888; }
.task-badge.in_progress { background: #eef0ff; color: #667eea; }
.task-badge.done        { background: #e8f8f3; color: #2e7d32; }
.task-badge.blocked     { background: #fce4e4; color: #c62828; }

.task-desc {
  padding: 0 14px 4px 34px;
  font-size: 0.76rem;
  color: #999;
  line-height: 1.4;
}

.subtasks {
  border-left: 2px solid #f0f4ff;
  margin-left: 22px;
}
</style>
