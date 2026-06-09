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
  background: rgba(0, 0, 0, 0.45);
  display: flex; align-items: center; justify-content: center;
}
.confirm-box {
  background: white; border-radius: 12px; width: 360px; max-width: 90vw;
  padding: 22px 24px; box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}
.confirm-title { font-size: 1.05rem; font-weight: 600; color: #303133; margin-bottom: 10px; }
.confirm-message { font-size: 0.9rem; color: #606266; line-height: 1.6; white-space: pre-wrap; }
.confirm-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.confirm-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 0.88rem; cursor: pointer;
  border: 1px solid #dcdfe6; background: white; color: #606266; transition: all 0.15s;
}
.confirm-btn--cancel:hover { color: #667eea; border-color: #c6d2fe; }
.confirm-btn--ok { background: #667eea; border-color: #667eea; color: white; }
.confirm-btn--ok:hover { background: #5a6fd6; border-color: #5a6fd6; }
.confirm-btn--ok.danger { background: #f56c6c; border-color: #f56c6c; }
.confirm-btn--ok.danger:hover { background: #e85656; border-color: #e85656; }

[data-theme="dark"] .confirm-box { background: #2c2d32; }
[data-theme="dark"] .confirm-title { color: #c1c2c5; }
[data-theme="dark"] .confirm-message { color: #909296; }
[data-theme="dark"] .confirm-btn { background: #25262b; border-color: #4a4d55; color: #c1c2c5; }
</style>
