<template>
  <div class="empty-state">
    <i :class="[icon, 'empty-icon']" />
    <p class="empty-title">{{ title }}</p>
    <p v-if="sub" class="empty-sub">{{ sub }}</p>
    <slot />
  </div>
</template>

<script setup>
/**
 * 统一空态。此前 8 个页面各自写了一份 .empty-state/.empty-icon/.empty-title/.empty-sub
 * （样式已经漂移：图标色有 var(--color-text-muted) / var(--color-text-muted) / 内联样式三种写法），统一到这里。
 */
defineProps({
  /** Font Awesome 图标 class，例如 'fas fa-tasks' */
  icon:  { type: String, required: true },
  title: { type: String, required: true },
  sub:   { type: String, default: '' },
})
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-1-5);
  padding: var(--space-9) var(--space-4-5);
  text-align: center;
}

.empty-icon {
  font-size: 2.5rem;
  color: var(--color-text-muted);
  opacity: 0.6;
}

.empty-title {
  margin: 0;
  font-size: var(--text-base);
  font-weight: var(--weight-medium);
  color: var(--color-text-secondary);
}

.empty-sub {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-muted);
}
</style>
