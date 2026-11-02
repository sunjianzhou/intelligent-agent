<template>
  <transition name="search-slide">
    <div v-if="show" class="chat-search-bar">
      <i class="fas fa-search search-bar-icon" />
      <input
        ref="inputRef"
        :value="keyword"
        class="search-bar-input"
        placeholder="搜索聊天记录..."
        @input="emit('update:keyword', $event.target.value)"
        @keydown.esc="emit('close')"
        @keydown.enter="emit('next')"
      />
      <span v-if="matchesCount" class="search-count">
        {{ currentIdx + 1 }} / {{ matchesCount }}
      </span>
      <button class="search-nav-btn" :disabled="!matchesCount" @click="emit('prev')"><i class="fas fa-chevron-up" /></button>
      <button class="search-nav-btn" :disabled="!matchesCount" @click="emit('next')"><i class="fas fa-chevron-down" /></button>
      <button class="search-close-btn" @click="emit('close')"><i class="fas fa-times" /></button>
    </div>
  </transition>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  show: Boolean,
  keyword: String,
  matchesCount: Number,
  currentIdx: Number,
})
const emit = defineEmits(['close', 'prev', 'next', 'update:keyword'])
const inputRef = ref(null)

// 打开搜索栏时自动聚焦输入框
watch(() => props.show, (v) => {
  if (v) nextTick(() => inputRef.value?.focus())
})
</script>

<style scoped>
.chat-search-bar {
  display: flex; align-items: center; gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3); background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.search-bar-icon { color: var(--color-text-muted); font-size: var(--text-sm); }
.search-bar-input {
  flex: 1; border: none; outline: none;
  font-size: var(--text-sm); color: var(--color-text);
}
.search-count { font-size: var(--text-xs); color: var(--color-text-muted); white-space: nowrap; }
.search-nav-btn, .search-close-btn {
  background: none; border: none; color: var(--color-text-muted);
  cursor: pointer; padding: var(--space-1) var(--space-1-5); border-radius: var(--radius-xs); font-size: var(--text-sm-2);
}
.search-nav-btn:hover, .search-close-btn:hover { background: var(--color-surface-sunken); color: var(--color-text); }
.search-nav-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.search-slide-enter-active, .search-slide-leave-active { transition: var(--transition-interactive); }
.search-slide-enter-from, .search-slide-leave-to { opacity: 0; transform: translateY(-8px); }
@media (max-width: 768px) {
  .search-bar-input { font-size: var(--text-md) !important; }
}
</style>
