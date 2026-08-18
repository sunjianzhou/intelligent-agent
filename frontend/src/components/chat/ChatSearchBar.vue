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
  display: flex; align-items: center; gap: 6px;
  padding: 6px var(--space-3); background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.search-bar-icon { color: var(--color-text-muted); font-size: 0.85rem; }
.search-bar-input {
  flex: 1; border: none; outline: none;
  font-size: 0.88rem; color: var(--color-text);
}
.search-count { font-size: 0.78rem; color: var(--color-text-muted); white-space: nowrap; }
.search-nav-btn, .search-close-btn {
  background: none; border: none; color: var(--color-text-muted);
  cursor: pointer; padding: var(--space-1) 6px; border-radius: 4px; font-size: 0.8rem;
}
.search-nav-btn:hover, .search-close-btn:hover { background: #f0f0f0; color: var(--color-text); }
.search-nav-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.search-slide-enter-active, .search-slide-leave-active { transition: all 0.2s; }
.search-slide-enter-from, .search-slide-leave-to { opacity: 0; transform: translateY(-8px); }
@media (max-width: 768px) {
  .search-bar-input { font-size: 16px !important; }
}
</style>
