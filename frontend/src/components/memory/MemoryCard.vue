<template>
  <div class="memory-card" :class="{ deleting: deletingId === mem.id, invalidated: mem.invalidated }">
    <div class="memory-header">
      <div class="memory-meta">
        <span class="badge cat-badge">{{ mem.category }}</span>
        <span v-if="mem.role" class="badge role-badge">{{ mem.role }}</span>
        <span v-if="mem.invalidated" class="badge inv-badge" :title="mem.invalidated_reason">
          已失效
        </span>
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
          ★ {{ mem.importance }} <i class="fas fa-pen" style="font-size:var(--text-2xs);margin-left:var(--space-0-5)" />
        </span>
        <template v-if="activeType === 'long_term' || isSearchMode">
          <button v-if="!mem.invalidated" class="act-btn pin-btn" title="置顶（重要度 1.0）"
                  @click.stop="emit('pin-one', mem)">
            <i class="fas fa-thumbtack" />
          </button>
          <button v-if="!mem.invalidated" class="act-btn inv-btn" title="失效（软删除，可恢复）"
                  @click.stop="emit('invalidate-one', mem)">
            <i class="fas fa-eye-slash" />
          </button>
          <button v-if="mem.invalidated" class="act-btn restore-btn" title="恢复该记忆"
                  @click.stop="emit('restore-one', mem)">
            <i class="fas fa-undo" />
          </button>
        </template>
        <button v-if="activeType === 'long_term' || isSearchMode" class="del-btn"
                :disabled="deletingId === mem.id" title="彻底删除（不可恢复）"
                @click="emit('delete-one', mem.id)">
          <i class="fas fa-trash" />
        </button>
      </div>
    </div>
    <div class="memory-content">{{ mem.content }}</div>
    <div v-if="mem.invalidated && mem.invalidated_reason" class="inv-reason">
      失效原因：{{ mem.invalidated_reason }}
    </div>
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
const emit = defineEmits(['edit-importance', 'delete-one', 'pin-one', 'invalidate-one', 'restore-one'])
</script>

<style scoped>
.memory-card {
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-3-5) var(--space-4);
  transition: var(--transition-interactive);

  box-shadow: var(--shadow-card);}
.memory-card:hover   { border-color: var(--color-primary-soft); }
.memory-card.deleting { opacity: 0.4; pointer-events: none; }
.memory-card.invalidated { opacity: 0.72; border-style: dashed; }

.memory-header {
  display: flex; align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.memory-meta { display: flex; gap: var(--space-1-5); flex-wrap: wrap; }
.badge {
  font-size: var(--text-2xs); padding: var(--space-0-5) var(--space-2);
  border-radius: var(--radius-sm); font-weight: var(--weight-medium);
}
.cat-badge  { background: var(--color-surface-raised); color: var(--color-primary); }
.role-badge { background: var(--color-success-soft); color: var(--color-success); }
.sim-badge  { background: var(--color-warn-soft); color: var(--color-warn); }
.inv-badge  { background: var(--color-violet-soft); color: var(--color-primary); }

.memory-actions {
  display: flex; align-items: center; gap: var(--space-2);
}
.importance-badge {
  font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-md);
  font-weight: var(--weight-medium); cursor: default;
}
.imp-high { background: var(--color-warn-soft); color: var(--color-warn); }
.imp-mid  { background: var(--color-success-soft); color: var(--color-success); }
.imp-low  { background: var(--color-bg); color: var(--color-text-muted); }
.del-btn {
  background: none; border: none;
  color: var(--color-text-muted); cursor: pointer;
  font-size: var(--text-sm-2); padding: var(--space-1);
  border-radius: var(--radius-xs); transition: color var(--duration-base);
}
.del-btn:hover:not(:disabled) { color: var(--color-danger); }
.del-btn:disabled { cursor: not-allowed; }
.act-btn {
  background: none; border: none;
  color: var(--color-text-muted); cursor: pointer;
  font-size: var(--text-sm-2); padding: var(--space-1);
  border-radius: var(--radius-xs); transition: color var(--duration-base);
}
.act-btn:hover { color: var(--color-primary); }
.pin-btn:hover    { color: var(--color-warn); }
.inv-btn:hover    { color: var(--color-primary); }
.restore-btn:hover { color: var(--color-success); }

.memory-content {
  font-size: var(--text-sm); color: var(--color-text);
  line-height: var(--leading-relaxed); margin-bottom: var(--space-2);
  word-break: break-word;
}
.memory-footer {
  display: flex; align-items: center; gap: var(--space-3);
}
.mem-time   { font-size: var(--text-xs); color: var(--color-text-muted); }
.mem-access { font-size: var(--text-xs); color: var(--color-text-muted); }
.inv-reason {
  font-size: var(--text-xs); color: var(--color-primary);
  margin-bottom: var(--space-2); font-style: italic;
}
</style>
