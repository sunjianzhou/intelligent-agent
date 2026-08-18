<template>
  <div v-if="show" class="history-backdrop" @click="emit('close')" />
  <transition name="history-slide">
    <div v-if="show" class="history-panel">
      <div class="history-header">
        <span class="history-title"><i class="fas fa-history" /> 历史会话</span>
        <button class="history-close" @click="emit('close')"><i class="fas fa-times" /></button>
      </div>
      <button class="new-chat-btn" @click="emit('new')">
        <i class="fas fa-plus" /> 新开对话
      </button>
      <div class="history-list" v-if="!loading">
        <div v-if="!sessions.length" class="history-empty">暂无历史会话记录</div>
        <div
          v-for="sess in sessions"
          :key="sess.session_id"
          class="history-item"
          @click="emit('load', sess.session_id)"
        >
          <div class="history-item-top">
            <span class="history-item-date">{{ formatHistoryDate(sess.updated_at) }}</span>
            <button class="history-item-del" title="删除" @click.stop="emit('delete', sess.session_id)">
              <i class="fas fa-trash-alt" />
            </button>
          </div>
          <div class="history-item-preview">
            <span v-if="sess.parent_session_id" class="branch-badge">
              <i class="fas fa-code-branch" /> 分支
            </span>{{ sess.preview || '新对话' }}
          </div>
          <div class="history-item-count">{{ sess.message_count }} 条消息</div>
        </div>
      </div>
      <div v-else class="history-loading">
        <i class="fas fa-circle-notch fa-spin" /> 加载中...
      </div>
    </div>
  </transition>
</template>

<script setup>
defineProps({
  show: Boolean,
  loading: Boolean,
  sessions: Array,
})
const emit = defineEmits(['close', 'new', 'load', 'delete'])

const formatHistoryDate = (iso) => {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    const diff = Date.now() - d.getTime()
    if (diff < 60000)     return '刚刚'
    if (diff < 3600000)   return `${Math.floor(diff / 60000)} 分钟前`
    if (diff < 86400000)  return `${Math.floor(diff / 3600000)} 小时前`
    if (diff < 604800000) return `${Math.floor(diff / 86400000)} 天前`
    return d.toLocaleDateString('zh-CN')
  } catch { return iso }
}
</script>

<style scoped>
.history-backdrop {
  display: inline-flex; align-items: center; gap: 2px;
  font-size: 0.7rem; color: #6c6fff; background: #ededff;
  border-radius: 3px; padding: 0 var(--space-1); margin-right: var(--space-1); vertical-align: middle;
  font-weight: 600; line-height: 1.5;
}
.history-panel {
  position: absolute;
  top: 0; left: 0; bottom: 0;
  width: 280px;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  box-shadow: 2px 0 16px rgba(0,0,0,0.12);
  z-index: 20;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.history-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px var(--space-4);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.history-title {
  font-size: 0.92rem; font-weight: 600; color: var(--color-text);
  display: flex; align-items: center; gap: 7px;
}
.history-title i { color: var(--color-primary); }
.history-close {
  background: none; border: none; color: var(--color-text-muted);
  cursor: pointer; padding: var(--space-1) 6px; border-radius: 4px; font-size: 0.9rem;
  transition: background 0.15s, color 0.15s;
}
.history-close:hover { background: #f5f5f5; color: var(--color-text); }
.new-chat-btn {
  margin: 10px 14px;
  padding: var(--space-2) 14px;
  background: var(--color-primary); color: white;
  border: none; border-radius: var(--radius-sm);
  font-size: 0.88rem; cursor: pointer;
  display: flex; align-items: center; justify-content: center; gap: 6px;
  transition: background 0.15s;
  flex-shrink: 0;
}
.new-chat-btn:hover { background: var(--color-primary-hover); }
.history-list {
  flex: 1; overflow-y: auto; padding: var(--space-1) 10px 10px;
}
.history-list::-webkit-scrollbar       { width: 3px; }
.history-list::-webkit-scrollbar-thumb { background: #e0e0e0; border-radius: 2px; }
.history-empty, .history-loading {
  text-align: center; color: var(--color-text-muted); font-size: 0.85rem; padding: var(--space-6) 0;
}
.history-item {
  padding: 10px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
  border: 1px solid transparent;
}
.history-item:hover { background: #f5f7ff; border-color: #e8ecff; }
.history-item-top {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: var(--space-1);
}
.history-item-date { font-size: 0.74rem; color: var(--color-text-muted); }
.history-item-del {
  background: none; border: none; color: #ddd;
  cursor: pointer; padding: 2px 5px; font-size: 0.72rem; border-radius: 4px;
  transition: color 0.15s, background 0.15s;
  line-height: 1;
}
.history-item-del:hover { color: #e53935; background: #fce4e4; }
.history-item-preview {
  font-size: 0.84rem; color: var(--color-text-secondary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  margin-bottom: 3px;
}
.history-item-count { font-size: 0.74rem; color: var(--color-text-muted); }
.branch-badge {
  display: inline-flex; align-items: center; gap: 2px;
  font-size: 0.7rem; color: #6c6fff; background: #ededff;
  border-radius: 3px; padding: 0 var(--space-1); margin-right: var(--space-1); vertical-align: middle;
  font-weight: 600; line-height: 1.5;
}
[data-theme="dark"] .branch-badge { color: #a5b4fc; background: #2a2a5a; }
.history-slide-enter-active, .history-slide-leave-active { transition: transform 0.25s ease; }
.history-slide-enter-from, .history-slide-leave-to { transform: translateX(-100%); }
@media (max-width: 768px) {
  .history-panel { width: min(240px, 85vw); }
}
</style>
