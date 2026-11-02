<template>
  <Teleport to="body">
    <Transition name="bs">
      <div
        v-if="modelValue"
        class="bs-mask"
        @click.self="close"
      >
        <div
          ref="panelRef"
          class="bs-panel"
          :style="{ maxHeight }"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="title ? 'bs-title' : undefined"
          tabindex="-1"
          @keydown.esc="close"
        >
          <div class="bs-handle" aria-hidden="true" />
          <h3 v-if="title" id="bs-title" class="bs-title">{{ title }}</h3>
          <div class="bs-body">
            <slot />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, required: true },
  title:      { type: String,  default: '' },
  maxHeight:  { type: String,  default: '75vh' },
})
const emit = defineEmits(['update:modelValue'])
const panelRef = ref(null)

const close = () => emit('update:modelValue', false)

watch(() => props.modelValue, async (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    await nextTick()
    panelRef.value?.focus()
  } else {
    document.body.style.overflow = ''
  }
})
</script>

<style scoped>
.bs-mask {
  position: fixed;
  inset: 0;
  background: var(--color-scrim);
  z-index: 200;
  display: flex;
  align-items: flex-end;
}

.bs-panel {
  width: 100%;
  background: var(--color-surface);
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  overflow-y: auto;
  outline: none;
  padding-bottom: env(safe-area-inset-bottom, var(--space-0));
}

.bs-handle {
  width: 40px;
  height: 4px;
  background: var(--color-border);
  border-radius: var(--radius-xs);
  margin: var(--space-3) auto var(--space-2);
}

.bs-title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  color: var(--color-text);
  padding: 0 var(--space-4-5) var(--space-3);
  border-bottom: 1px solid var(--color-border);
  margin: 0;
}

.bs-body {
  padding: var(--space-2) 0;
}

/* 遮罩淡入淡出 + 面板上划 */
.bs-enter-active,
.bs-leave-active {
  transition: opacity var(--duration-slow) var(--ease-standard);
}
.bs-enter-active .bs-panel,
.bs-leave-active .bs-panel {
  transition: transform var(--duration-slow) var(--ease-standard);
}
.bs-enter-from,
.bs-leave-to {
  opacity: 0;
}
.bs-enter-from .bs-panel,
.bs-leave-to .bs-panel {
  transform: translateY(100%);
}
</style>
