<template>
  <nav class="bottom-tab-bar" role="tablist" aria-label="主导航">
    <button
      v-for="tab in TABS"
      :key="tab.key"
      class="tab-btn"
      :class="{ active: isActive(tab) }"
      role="tab"
      :aria-selected="isActive(tab)"
      :aria-label="tab.label"
      @click="handleTab(tab)"
    >
      <i :class="tab.icon" aria-hidden="true" />
      <span class="tab-label">{{ tab.label }}</span>
    </button>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAV_ITEMS } from '@/config/routes.config.js'

const emit  = defineEmits(['open-more'])
const route = useRoute()
const router = useRouter()

// 从 routes.config.js 的 NAV_ITEMS 派生底部 Tab（聊天/角色配置/记忆）
const BOTTOM_TAB_NAMES = ['chat', 'role-editor', 'memory']
const bottomTabs = computed(() =>
  NAV_ITEMS
    .filter(item => BOTTOM_TAB_NAMES.includes(item.name))
    .map(item => ({ key: item.name, label: item.label, icon: item.icon, path: item.path }))
)

const TABS = computed(() => [
  ...bottomTabs.value,
  { key: 'more', label: '更多', icon: 'fas fa-ellipsis-h', path: null },
])

const MAIN_PATHS = computed(() => bottomTabs.value.map(t => t.path))

const isActive = (tab) => {
  if (tab.path === null) return !MAIN_PATHS.value.includes(route.path)
  return route.path === tab.path
}

const handleTab = (tab) => {
  if (tab.path === null) emit('open-more')
  else router.push(tab.path)
}
</script>

<style scoped>
.bottom-tab-bar {
  display: none;
}

@media (max-width: 768px) {
  .bottom-tab-bar {
    display: flex;
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    z-index: 50;
    height: calc(56px + env(safe-area-inset-bottom));
    padding-bottom: env(safe-area-inset-bottom);
    background: var(--color-surface);
    border-top: 1px solid var(--color-border);
    box-shadow: 0 -2px 12px rgba(0, 0, 0, 0.06);
  }
}

.tab-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  background: none;
  border: none;
  border-top: 2px solid transparent;
  padding: 0;
  cursor: pointer;
  color: var(--color-text-muted);
  transition: color 0.15s, border-color 0.15s;
  -webkit-tap-highlight-color: transparent;
}

.tab-btn i {
  font-size: 18px;
}

.tab-label {
  font-size: 10px;
  font-weight: 500;
  line-height: 1;
}

.tab-btn.active {
  color: var(--color-primary);
  border-top-color: var(--color-primary);
}

.tab-btn:not(.active):active {
  color: var(--color-text-secondary);
}
</style>
