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
  border-left: 2px solid var(--color-border);
  margin-left: var(--space-3-5);
}
.task-node:first-child { margin-left: 0; border-left: none; }

.node-row {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3-5);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background var(--duration-fast);
}
.node-row:hover { background: var(--color-surface-sunken); }

.expand-toggle {
  width: 14px;
  font-size: var(--text-2xs);
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.expand-placeholder { width: 14px; flex-shrink: 0; }

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot.pending     { background: var(--color-text-muted); }
.status-dot.in_progress { background: var(--color-primary); }
.status-dot.done        { background: var(--color-accent); }
.status-dot.blocked     { background: var(--color-danger); }

.task-name {
  flex: 1;
  font-size: var(--text-sm);
  color: var(--color-text);
  line-height: var(--leading-snug);
}

.task-badge {
  font-size: var(--text-2xs);
  padding: var(--space-px) var(--space-1-5);
  border-radius: var(--radius-sm);
  font-weight: var(--weight-medium);
  flex-shrink: 0;
}
.task-badge.pending     { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
.task-badge.in_progress { background: var(--color-primary-soft); color: var(--color-primary); }
.task-badge.done        { background: var(--color-success-soft); color: var(--color-success); }
.task-badge.blocked     { background: var(--color-danger-soft); color: var(--color-danger); }

.task-desc {
  padding: 0 var(--space-3-5) var(--space-1) 34px;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  line-height: var(--leading-snug);
}

.subtasks {
  border-left: 2px solid var(--color-surface-raised);
  margin-left: 22px;
}
</style>
