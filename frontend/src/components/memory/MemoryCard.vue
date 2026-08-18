<template>
  <div class="memory-card" :class="{ deleting: deletingId === mem.id }">
    <div class="memory-header">
      <div class="memory-meta">
        <span class="badge cat-badge">{{ mem.category }}</span>
        <span v-if="mem.role" class="badge role-badge">{{ mem.role }}</span>
        <span v-if="mem.similarity !== undefined" class="badge sim-badge">
          相关性 {{ (mem.similarity * 100).toFixed(0) }}%
        </span>
      </div>
      <div class="memory-actions">
        <span class="importance-badge"
              :class="mem.importance >= 0.8 ? 'imp-high' : mem.importance >= 0.5 ? 'imp-mid' : 'imp-low'"
              :title="`重要性: ${mem.importance}（点击修改）`"
              style="cursor:pointer"
              @click.stop="emit('edit-importance', mem)">
          ★ {{ mem.importance }} <i class="fas fa-pen" style="font-size:0.65rem;margin-left:2px" />
        </span>
        <button
          v-if="activeType === 'long_term' || isSearchMode"
          class="del-btn"
          :disabled="deletingId === mem.id"
          @click="emit('delete-one', mem.id)"
        >
          <i class="fas fa-trash" />
        </button>
      </div>
    </div>
    <div class="memory-content">{{ mem.content }}</div>
    <div class="memory-footer">
      <span class="mem-time">{{ formatTime(mem.created_at) }}</span>
      <span v-if="mem.access_count" class="mem-access">
        访问 {{ mem.access_count }} 次
      </span>
    </div>
  </div>
</template>

<script setup>
import { formatTime } from '@/utils/date'

defineProps({
  mem: { type: Object, required: true },
  activeType: String,
  isSearchMode: Boolean,
  deletingId: [String, Number],
})
const emit = defineEmits(['edit-importance', 'delete-one'])
</script>

<style scoped>
.memory-card {
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 14px var(--space-4);
  transition: all 0.2s;
}
.memory-card:hover   { border-color: #c5caf5; }
.memory-card.deleting { opacity: 0.4; pointer-events: none; }

.memory-header {
  display: flex; align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.memory-meta { display: flex; gap: 6px; flex-wrap: wrap; }
.badge {
  font-size: 11px; padding: 2px var(--space-2);
  border-radius: var(--radius-sm); font-weight: 500;
}
.cat-badge  { background: var(--color-surface-raised); color: var(--color-primary); }
.role-badge { background: #e8f5e9; color: #2e7d32; }
.sim-badge  { background: #fff8e1; color: #f57f17; }

.memory-actions {
  display: flex; align-items: center; gap: var(--space-2);
}
.importance-badge {
  font-size: 0.75rem; padding: 2px var(--space-2); border-radius: var(--radius-md);
  font-weight: 500; cursor: default;
}
.imp-high { background: #fff8e1; color: var(--color-warn); }
.imp-mid  { background: #e8f5e9; color: #2e7d32; }
.imp-low  { background: var(--color-bg); color: var(--color-text-muted); }
.del-btn {
  background: none; border: none;
  color: var(--color-text-muted); cursor: pointer;
  font-size: 0.82rem; padding: var(--space-1);
  border-radius: 4px; transition: color 0.2s;
}
.del-btn:hover:not(:disabled) { color: var(--color-danger); }
.del-btn:disabled { cursor: not-allowed; }

.memory-content {
  font-size: 0.88rem; color: var(--color-text);
  line-height: 1.6; margin-bottom: var(--space-2);
  word-break: break-word;
}
.memory-footer {
  display: flex; align-items: center; gap: var(--space-3);
}
.mem-time   { font-size: 0.75rem; color: var(--color-text-muted); }
.mem-access { font-size: 0.75rem; color: var(--color-text-muted); }
</style>
