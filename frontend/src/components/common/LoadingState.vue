<template>
  <div class="loading-state">
    <i class="fas fa-circle-notch fa-spin" />
    <span>{{ text }}</span>
  </div>
</template>

<script setup>
/** 统一加载态（此前 6 个页面各写一份 .loading-state） */
defineProps({
  text: { type: String, default: '加载中…' },
})
</script>

<style scoped>
.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-6) var(--space-4);
  font-size: var(--text-sm);
  color: var(--color-text-muted);
}
</style>
