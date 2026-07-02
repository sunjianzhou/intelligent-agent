<template>
  <BottomSheet
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    max-height="80vh"
  >
    <!-- 常用 -->
    <div class="mp-group">
      <div class="mp-group-title">常用</div>
      <button
        v-for="item in COMMON_ITEMS"
        :key="item.name"
        class="mp-item"
        :aria-label="item.label"
        @click="go(item.path)"
      >
        <i :class="item.icon" aria-hidden="true" />
        <span>{{ item.label }}</span>
        <i class="fas fa-chevron-right mp-chevron" aria-hidden="true" />
      </button>
    </div>

    <div class="mp-divider" />

    <!-- AI 能力 -->
    <div class="mp-group">
      <div class="mp-group-title">AI 能力</div>
      <button
        v-for="item in AI_ITEMS"
        :key="item.name"
        class="mp-item"
        :aria-label="item.label"
        @click="go(item.path)"
      >
        <i :class="item.icon" aria-hidden="true" />
        <span>{{ item.label }}</span>
        <i class="fas fa-chevron-right mp-chevron" aria-hidden="true" />
      </button>
    </div>

    <div class="mp-divider" />

    <!-- 运维与系统 -->
    <div class="mp-group">
      <div class="mp-group-title">运维与系统</div>
      <button
        v-for="item in OPS_ITEMS"
        :key="item.name"
        class="mp-item"
        :aria-label="item.label"
        @click="go(item.path)"
      >
        <i :class="item.icon" aria-hidden="true" />
        <span>{{ item.label }}</span>
        <i class="fas fa-chevron-right mp-chevron" aria-hidden="true" />
      </button>
    </div>

    <div class="mp-divider" />

    <!-- 退出登录 -->
    <div class="mp-group">
      <button class="mp-item mp-item-danger" aria-label="退出登录" @click="handleLogout">
        <i class="fas fa-sign-out-alt" aria-hidden="true" />
        <span>退出登录</span>
      </button>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { NAV_ITEMS, CONFIG_ITEMS, SYSTEM_ITEMS } from '@/config/routes.config.js'
import BottomSheet from '@/components/common/BottomSheet.vue'

const props = defineProps({ modelValue: { type: Boolean, required: true } })
const emit = defineEmits(['update:modelValue'])

const router    = useRouter()
const authStore = useAuthStore()

// 从 routes.config.js 派生三组导航，不再硬编码
const COMMON_ITEMS = computed(() =>
  NAV_ITEMS.filter(item => ['project', 'knowledge', 'image'].includes(item.name))
)

const AI_ITEMS = computed(() => CONFIG_ITEMS)

const OPS_ITEMS = computed(() => SYSTEM_ITEMS)

const go = (path) => {
  emit('update:modelValue', false)
  router.push(path)
}

const handleLogout = () => {
  emit('update:modelValue', false)
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.mp-group-title {
  font-size: 0.72rem;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 10px 20px 4px;
}

.mp-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 20px;
  background: none;
  border: none;
  color: var(--color-text);
  font-size: 1rem;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
  -webkit-tap-highlight-color: transparent;
}

.mp-item:hover,
.mp-item:active {
  background: var(--color-surface-raised);
}

.mp-item > i:first-child {
  width: 22px;
  text-align: center;
  color: var(--color-primary);
  font-size: 1rem;
  flex-shrink: 0;
}

.mp-item-danger           { color: var(--color-danger); }
.mp-item-danger > i:first-child { color: var(--color-danger); }

.mp-chevron {
  margin-left: auto;
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.mp-divider {
  height: 1px;
  background: var(--color-border);
  margin: 4px 0;
}
</style>
