<template>
  <div class="skeleton-wrap" :class="`variant-${variant}`" aria-hidden="true">
    <div v-for="i in rows" :key="i" class="skeleton-item">
      <template v-if="variant === 'cards'">
        <div class="sk sk-title" />
        <div class="sk sk-line" />
        <div class="sk sk-line short" />
      </template>
      <template v-else>
        <div class="sk sk-avatar" />
        <div class="skeleton-lines">
          <div class="sk sk-line" />
          <div class="sk sk-line short" />
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
/**
 * 首屏加载骨架屏。列表/卡片两类内容用它替代「转圈 + 空白」，
 * 让加载过程有结构感（感知性能），spinner 只用于局部小交互。
 */
defineProps({
  rows:    { type: Number, default: 4 },
  variant: { type: String, default: 'list' }, // list | cards
})
</script>

<style scoped>
.skeleton-wrap { display: flex; flex-direction: column; gap: var(--space-3); padding: var(--space-5) 0; }
.skeleton-wrap.variant-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: var(--space-3); }

.skeleton-item {
  display: flex; flex-direction: column; gap: var(--space-2);
  padding: var(--space-3-5);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.skeleton-wrap.variant-list .skeleton-item { flex-direction: row; align-items: center; }
.skeleton-lines { display: flex; flex-direction: column; gap: var(--space-2); flex: 1; }

.sk {
  background: linear-gradient(90deg,
    var(--color-surface-sunken) 25%,
    var(--color-surface-raised) 37%,
    var(--color-surface-sunken) 63%);
  background-size: 400% 100%;
  border-radius: var(--radius-xs);
  animation: sk-shimmer 1.4s var(--ease-standard) infinite;
}
.sk-avatar { width: 32px; height: 32px; border-radius: var(--radius-full); flex-shrink: 0; }
.sk-title  { height: 16px; width: 45%; }
.sk-line   { height: 12px; width: 100%; }
.sk-line.short { width: 65%; }

@keyframes sk-shimmer {
  0%   { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

@media (prefers-reduced-motion: reduce) {
  .sk { animation: none; }
}
</style>
