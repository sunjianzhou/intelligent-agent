<template>
  <div v-if="store.visible" class="confirm-overlay" @click.self="store.onCancel">
    <div class="confirm-box">
      <div class="confirm-title">{{ store.title }}</div>
      <div class="confirm-message">{{ store.message }}</div>
      <div class="confirm-actions">
        <button class="confirm-btn confirm-btn--cancel" @click="store.onCancel">{{ store.cancelText }}</button>
        <button
          class="confirm-btn confirm-btn--ok"
          :class="{ danger: store.danger }"
          @click="store.onConfirm"
        >{{ store.confirmText }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useConfirmDialogStore } from '@/stores/confirmDialog'

const store = useConfirmDialogStore()
</script>

<style scoped>
.confirm-overlay {
  position: fixed; inset: 0; z-index: 3000;
  background: var(--color-scrim);
  display: flex; align-items: center; justify-content: center;
}
.confirm-box {
  background: var(--color-surface); border-radius: var(--radius-md); width: 360px; max-width: 90vw;
  padding: 22px var(--space-5); box-shadow: var(--shadow-overlay);
}
.confirm-title { font-size: var(--text-lg); font-weight: var(--weight-semibold); color: var(--color-text); margin-bottom: var(--space-2-5); }
.confirm-message { font-size: var(--text-sm); color: var(--color-text-secondary); line-height: var(--leading-relaxed); white-space: pre-wrap; }
.confirm-actions { display: flex; justify-content: flex-end; gap: var(--space-2-5); margin-top: var(--space-4-5); }
.confirm-btn {
  padding: var(--space-2) var(--space-4); border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer;
  border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text-secondary); transition: var(--transition-interactive);
}
.confirm-btn--cancel:hover { color: var(--color-primary); border-color: var(--color-primary-border); }
.confirm-btn--ok { background: var(--color-primary); border-color: var(--color-primary); color: var(--color-text-on-primary); }
.confirm-btn--ok:hover { background: var(--color-primary-hover); border-color: var(--color-primary-hover); }
.confirm-btn--ok.danger { background: var(--color-danger); border-color: var(--color-danger); }
.confirm-btn--ok.danger:hover { background: var(--color-danger); border-color: var(--color-danger); }
</style>
