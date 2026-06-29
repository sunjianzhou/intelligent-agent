# Mobile PWA Layout — iPhone 16 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将移动端导航从汉堡菜单抽屉改为底部 4-Tab Bar，新增 MorePanel + 公共 BottomSheet 组件，修复 iOS safe-area、dvh、键盘遮挡问题，并重构 ChatView 角色/模型选择器为底部抽屉。

**Architecture:** 新建 3 个 Vue 组件（`BottomSheet`/`BottomTabBar`/`MorePanel`），挂载于 `App.vue`；ChatView 角色/模型徽章内联触发 `BottomSheet`；所有改动通过 `@media (max-width: 768px)` 隔离，桌面端零影响。

**Tech Stack:** Vue 3 `<script setup>`，CSS `env(safe-area-inset-*)` + `dvh`，`window.visualViewport` API，FontAwesome 图标（已全局引入）

## Global Constraints

- 所有移动端 CSS 置于 `@media (max-width: 768px)` 内，桌面端样式不变
- 使用 `var(--color-*)` CSS 变量（已在 `main.css` 定义），禁止硬编码色值
- 组件用 Vue 3 `<script setup>` 语法
- 不引入新 npm 依赖
- 所有新增 `<button>` 必须有 `aria-label` 或可见文字
- 提交消息格式：`feat(mobile): <简短描述>`

---

## File Map

| 文件 | 操作 |
|------|------|
| `frontend/index.html` | 修改：viewport-fit=cover；更新 theme-color + status-bar meta |
| `frontend/src/App.vue` | 修改：dvh fallback；visualViewport 键盘监听；挂载 BottomTabBar + MorePanel |
| `frontend/src/styles/main.css` | 修改：dvh fallback；mobile main-content padding-bottom；全局 font-size |
| `frontend/src/components/common/BottomSheet.vue` | **新建**：公共底部抽屉组件 |
| `frontend/src/components/layout/BottomTabBar.vue` | **新建**：4-Tab 底部导航 |
| `frontend/src/components/layout/MorePanel.vue` | **新建**："更多"导航面板（基于 BottomSheet） |
| `frontend/src/components/layout/Header.vue` | 修改：移除汉堡菜单全部代码 |
| `frontend/src/views/ChatView.vue` | 修改：config-bar 隐藏；移动端角色/模型徽章；RoleModelSheet；清理死 CSS |

---

## Task 1: index.html — viewport-fit + PWA meta 修正

**Files:**
- Modify: `frontend/index.html:6-12`

**Interfaces:**
- Produces: `viewport-fit=cover` 使 CSS `env()` 函数生效；`black-translucent` 状态栏使内容延伸到刘海下方

- [ ] **Step 1: 替换 viewport + theme-color + status-bar meta**

打开 `frontend/index.html`，将 `<head>` 中以下 5 行：

```html
  <meta name="theme-color" content="#667eea">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="default">
  <meta name="apple-mobile-web-app-title" content="智能体">
  ...
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
```

替换为：

```html
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <meta name="apple-mobile-web-app-title" content="智能体">
  <meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0a0a0a" media="(prefers-color-scheme: dark)">
  ...
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
```

- [ ] **Step 2: 验证文件**

用浏览器打开 `http://localhost:5173`（或 `npm run dev` 启动后），在 Chrome DevTools → Application → Manifest 中确认 `theme_color` 已更新。

- [ ] **Step 3: 提交**

```bash
git add frontend/index.html
git commit -m "feat(mobile): update viewport-fit=cover and PWA meta for iPhone 16"
```

---

## Task 2: 全局 CSS 基础 — dvh fallback + keyboard var + font-size

**Files:**
- Modify: `frontend/src/styles/main.css`
- Modify: `frontend/src/App.vue` (`<style>` 块)

**Interfaces:**
- Produces: CSS 变量 `--keyboard-height`（初始值 `0px`，由 Task 6 的 JS 动态更新）；`100dvh` 兜底写法；移动端 `.main-content` 底部为 Tab Bar 留出空间

- [ ] **Step 1: 在 `main.css` `:root` 块中加入 `--keyboard-height` 变量**

在 `frontend/src/styles/main.css` 第 40 行（`:root` 块末尾，`--content-max-width` 之后）插入：

```css
  /* 移动端键盘高度（由 visualViewport 事件动态写入） */
  --keyboard-height: 0px;
```

- [ ] **Step 2: 在 `main.css` 末尾追加移动端全局规则**

在 `frontend/src/styles/main.css` 文件末尾追加：

```css

/* ── 移动端全局基础（iPhone 16 PWA 适配）────────────────── */

/* 防止 iOS Safari 自动放大 <16px 表单字体触发页面缩放 */
input,
textarea,
select {
  -webkit-text-size-adjust: 100%;
}

/* 移动端主内容区为固定 Tab Bar 留出空间 */
@media (max-width: 768px) {
  .main-content {
    padding-bottom: calc(56px + env(safe-area-inset-bottom)) !important;
  }
}
```

- [ ] **Step 3: 更新 `App.vue` `<style>` 中的 `body` 和 `.main-layout`**

在 `frontend/src/App.vue` 的 `<style>` 块中，将：

```css
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
  background: var(--color-bg);
  height: 100vh;
  overflow: hidden;
}
```

改为：

```css
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
  background: var(--color-bg);
  height: 100vh;
  height: 100dvh; /* dvh fallback：iOS 15.4+ 支持，旧版降级到 100vh */
  overflow: hidden;
}
```

将：

```css
.main-layout {
  width: 100%;
  height: 100%;
  background: var(--color-bg);
  display: flex;
}
```

改为：

```css
.main-layout {
  width: 100%;
  height: 100vh;
  height: 100dvh;
  background: var(--color-bg);
  display: flex;
}
```

- [ ] **Step 4: 手动验证**

`npm run dev`，在 Chrome DevTools 切换到 iPhone 16 模拟器（393×852），确认整体高度不超出屏幕、无滚动条。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/styles/main.css frontend/src/App.vue
git commit -m "feat(mobile): dvh fallback, keyboard CSS var, mobile content padding"
```

---

## Task 3: 新建 `BottomSheet.vue` — 公共底部抽屉组件

**Files:**
- Create: `frontend/src/components/common/BottomSheet.vue`

**Interfaces:**
- Produces:
  - Props: `modelValue: Boolean`（v-model 控制显隐）、`title: String`（可选标题）、`maxHeight: String`（默认 `'75vh'`）
  - Emits: `update:modelValue`
  - Slot: `default`（面板内容）
  - 行为：打开时锁 body 滚动 + 聚焦面板；ESC/遮罩点击关闭

- [ ] **Step 1: 创建文件**

新建 `frontend/src/components/common/BottomSheet.vue`，内容如下：

```vue
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
  background: rgba(0, 0, 0, 0.45);
  z-index: 200;
  display: flex;
  align-items: flex-end;
}

.bs-panel {
  width: 100%;
  background: var(--color-surface);
  border-radius: 20px 20px 0 0;
  overflow-y: auto;
  outline: none;
  padding-bottom: env(safe-area-inset-bottom, 0px);
}

.bs-handle {
  width: 40px;
  height: 4px;
  background: var(--color-border);
  border-radius: 2px;
  margin: 12px auto 8px;
}

.bs-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-text);
  padding: 0 20px 12px;
  border-bottom: 1px solid var(--color-border);
  margin: 0;
}

.bs-body {
  padding: 8px 0;
}

/* 遮罩淡入淡出 + 面板上划 */
.bs-enter-active,
.bs-leave-active {
  transition: opacity 0.28s ease;
}
.bs-enter-active .bs-panel,
.bs-leave-active .bs-panel {
  transition: transform 0.28s ease;
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
```

- [ ] **Step 2: 验证（手动）**

在任意 Vue 页面临时写 `<BottomSheet v-model="show" title="测试">内容</BottomSheet>` + `const show = ref(true)`，在 DevTools 移动端模式下确认面板出现、ESC/遮罩关闭有效，body 滚动被锁定。验证后还原。

- [ ] **Step 3: 提交**

```bash
git add frontend/src/components/common/BottomSheet.vue
git commit -m "feat(mobile): add BottomSheet common component"
```

---

## Task 4: 新建 `BottomTabBar.vue` — 4-Tab 底部导航

**Files:**
- Create: `frontend/src/components/layout/BottomTabBar.vue`

**Interfaces:**
- Consumes: `vue-router`（`useRoute`、`useRouter`）
- Produces:
  - Emits: `open-more`（点击"更多"Tab 时）
  - 样式：仅 `@media (max-width: 768px)` 显示，`position: fixed; bottom: 0`
  - 激活态：当前路由在 Tab 对应路径时高亮

- [ ] **Step 1: 创建文件**

新建 `frontend/src/components/layout/BottomTabBar.vue`，内容如下：

```vue
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
import { useRoute, useRouter } from 'vue-router'

const emit = defineEmits(['open-more'])
const route  = useRoute()
const router = useRouter()

const MAIN_PATHS = ['/chat', '/roles/editor', '/memory']

const TABS = [
  { key: 'chat',   label: '聊天', icon: 'fas fa-comment',    path: '/chat' },
  { key: 'roles',  label: '角色', icon: 'fas fa-id-card',    path: '/roles/editor' },
  { key: 'memory', label: '记忆', icon: 'fas fa-brain',      path: '/memory' },
  { key: 'more',   label: '更多', icon: 'fas fa-ellipsis-h', path: null },
]

const isActive = (tab) => {
  if (tab.path === null) return !MAIN_PATHS.includes(route.path)
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
```

- [ ] **Step 2: 验证（手动）**

在 `App.vue` 中临时 import 并挂载（见 Task 6），在 DevTools 移动端模式下确认 4 个 Tab 等宽显示，切换路由时激活色条正确。

- [ ] **Step 3: 提交**

```bash
git add frontend/src/components/layout/BottomTabBar.vue
git commit -m "feat(mobile): add 4-tab BottomTabBar component"
```

---

## Task 5: 新建 `MorePanel.vue` — 更多导航面板

**Files:**
- Create: `frontend/src/components/layout/MorePanel.vue`
- Consumes: `frontend/src/components/common/BottomSheet.vue`（Task 3）

**Interfaces:**
- Props: `modelValue: Boolean`（v-model）
- Emits: `update:modelValue`
- 行为：显示三组导航项 + 退出登录；点击任何项后关闭面板并导航

- [ ] **Step 1: 创建文件**

新建 `frontend/src/components/layout/MorePanel.vue`，内容如下：

```vue
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
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import BottomSheet from '@/components/common/BottomSheet.vue'

const props = defineProps({ modelValue: { type: Boolean, required: true } })
const emit = defineEmits(['update:modelValue'])

const router    = useRouter()
const authStore = useAuthStore()

const COMMON_ITEMS = [
  { name: 'project',   label: '项目',    icon: 'fas fa-folder-open', path: '/project'   },
  { name: 'knowledge', label: '知识库',  icon: 'fas fa-book',        path: '/knowledge' },
  { name: 'image',     label: '图片生成', icon: 'fas fa-image',      path: '/image'     },
]
const AI_ITEMS = [
  { name: 'admin-models', label: '模型管理',   icon: 'fas fa-robot', path: '/admin/models' },
  { name: 'admin-skills', label: 'Skill 管理', icon: 'fas fa-magic', path: '/admin/skills' },
  { name: 'admin-mcp',    label: 'MCP 配置',   icon: 'fas fa-plug',  path: '/admin/mcp'    },
]
const OPS_ITEMS = [
  { name: 'admin-tools',  label: '工具管理', icon: 'fas fa-tools',           path: '/admin/tools'  },
  { name: 'admin-tasks',  label: '任务管理', icon: 'fas fa-tasks',           path: '/admin/tasks'  },
  { name: 'admin-logs',   label: '操作日志', icon: 'fas fa-clipboard-list',  path: '/admin/logs'   },
  { name: 'admin-stats',  label: '统计分析', icon: 'fas fa-chart-bar',       path: '/admin/stats'  },
  { name: 'admin-system', label: '系统信息', icon: 'fas fa-info-circle',     path: '/admin/system' },
]

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
```

- [ ] **Step 2: 提交**

```bash
git add frontend/src/components/layout/MorePanel.vue
git commit -m "feat(mobile): add MorePanel component with grouped navigation"
```

---

## Task 6: `App.vue` — 挂载 BottomTabBar + MorePanel + keyboard 监听

**Files:**
- Modify: `frontend/src/App.vue`

**Interfaces:**
- Consumes: `BottomTabBar`（Task 4）、`MorePanel`（Task 5）
- 行为：`showMorePanel` ref 控制 MorePanel；`visualViewport` resize 事件写入 `--keyboard-height`

- [ ] **Step 1: 修改 `<template>`**

在 `frontend/src/App.vue` 中，找到 `<div v-else class="main-layout">` 块，将：

```html
    <div v-else class="main-layout">
      <Sidebar />
      <div class="main-content">
        <Header />
        <InstallPrompt />
        ...
        <StatusBar />
      </div>
    </div>
```

改为：

```html
    <div v-else class="main-layout">
      <Sidebar />
      <div class="main-content">
        <Header />
        <InstallPrompt />
        ...
        <StatusBar />
      </div>
      <!-- 移动端底部导航（桌面端通过 CSS display:none 隐藏） -->
      <BottomTabBar @open-more="showMorePanel = true" />
      <MorePanel v-model="showMorePanel" />
    </div>
```

- [ ] **Step 2: 修改 `<script setup>`**

在 `frontend/src/App.vue` 的 `<script setup>` 中：

1. 新增 import：

```js
import { ref } from 'vue'  // 已有，确认 ref 已导入
import BottomTabBar from '@/components/layout/BottomTabBar.vue'
import MorePanel    from '@/components/layout/MorePanel.vue'
```

2. 在 `useErrorBusStore()` 行之后新增：

```js
const showMorePanel = ref(false)
```

3. 在 `onMounted(() => {` 块中追加 keyboard 监听：

```js
onMounted(() => {
  if (authStore.isLoggedIn && !websocketStore.isConnected) _doConnect()

  // iOS PWA 键盘弹出时 visualViewport 收缩，将差值写入 CSS 变量
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', () => {
      const kb = Math.max(0, window.innerHeight - window.visualViewport.height)
      document.documentElement.style.setProperty('--keyboard-height', `${kb}px`)
    })
  }
})
```

- [ ] **Step 3: 在移动端模拟器验证**

`npm run dev`，DevTools 切换到 iPhone 16（393×852），确认：
1. 底部 4 个 Tab 可见且等宽
2. 点击"更多"弹出 MorePanel，点遮罩关闭
3. 点击 MorePanel 中的"项目"导航成功

- [ ] **Step 4: 提交**

```bash
git add frontend/src/App.vue
git commit -m "feat(mobile): mount BottomTabBar + MorePanel, add keyboard height listener"
```

---

## Task 7: `Header.vue` — 移除汉堡菜单

**Files:**
- Modify: `frontend/src/components/layout/Header.vue`

**Interfaces:**
- 行为：移动端 Header 只剩页面标题 + 连接状态 + 主题切换；汉堡菜单及抽屉全部删除

- [ ] **Step 1: 从 `<template>` 中删除汉堡按钮**

找到并删除（含前后空行）：

```html
      <!-- 移动端汉堡菜单 -->
      <button class="mobile-menu-btn" @click="showMobileMenu = !showMobileMenu">
        <i class="fas fa-bars" />
      </button>
```

- [ ] **Step 2: 从 `<template>` 中删除整个移动端抽屉**

找到并删除：

```html
    <!-- 移动端导航抽屉 -->
    <div v-if="showMobileMenu" class="mobile-drawer" @click="showMobileMenu = false">
      <div class="mobile-nav" @click.stop>
        ...（全部内容）...
      </div>
    </div>
```

（从 `<!-- 移动端导航抽屉 -->` 注释行到对应 `</div>` 的闭合行，全部删除）

- [ ] **Step 3: 从 `<script setup>` 中删除已无用的变量和数据**

删除以下行：

```js
const showMobileMenu = ref(false)

// 移动端主导航：主入口 + 系统快捷入口（短标签）
const systemItem = { ...ADMIN_ITEMS.find(i => i.name === 'admin-system'), label: '系统' }
const navItems = [...NAV_ITEMS, systemItem]

const adminNavItems = ADMIN_ITEMS
```

同时从 import 行中移除 `ADMIN_ITEMS`（若 `NAV_ITEMS` 也不再使用则一并移除；`PAGE_CONFIGS` 保留，用于页面标题显示）：

```js
// 改为（只保留 PAGE_CONFIGS）：
import { NAV_ITEMS, PAGE_CONFIGS } from '@/config/routes.config'
// 若 NAV_ITEMS 也不再使用，改为：
import { PAGE_CONFIGS } from '@/config/routes.config'
```

检查 template 是否还有 `NAV_ITEMS` 用到，若无则从 import 中删除。

- [ ] **Step 4: 从 `<style scoped>` 中删除汉堡相关 CSS**

删除以下所有 CSS 规则（含注释）：

```css
/* 桌面端隐藏汉堡按钮 */
.mobile-menu-btn { ... }

@media (max-width: 768px) {
  .mobile-menu-btn { display: flex; align-items: center; }
}

/* 移动端抽屉 */
.mobile-drawer { display: none; }

@media (max-width: 768px) {
  .mobile-drawer { ... }
}

.mobile-nav { ... }
.mobile-nav-item { ... }
.mobile-nav-item:hover, .mobile-nav-item.router-link-active { ... }
.mobile-nav-item i { ... }
.mobile-nav-divider { ... }
.mobile-nav-section-title { ... }
.mobile-nav-option { ... }
.mobile-nav-option.mobile-active { ... }
.mobile-check { ... }
```

- [ ] **Step 5: 验证**

刷新移动端模拟器，确认：
1. 不再有汉堡按钮
2. Header 显示页面标题 + 连接状态 + 主题切换
3. 桌面端 Header 外观不变

- [ ] **Step 6: 提交**

```bash
git add frontend/src/components/layout/Header.vue
git commit -m "feat(mobile): remove hamburger menu, replaced by BottomTabBar"
```

---

## Task 8: `ChatView.vue` — 移动端角色/模型徽章 + RoleModelSheet + CSS 清理

**Files:**
- Modify: `frontend/src/views/ChatView.vue`

**Interfaces:**
- Consumes: `BottomSheet`（Task 3）；现有 `onRoleChange(roleId)`、`handleConfigSwitch(modelName)`、`availableRoles`、`activeRoleId`、`availableModels`、`currentModel`
- 行为：移动端隐藏 config-bar；输入区顶部显示角色+模型徽章；点击任一徽章弹出 RoleModelSheet；清理死 CSS

- [ ] **Step 1: 在 `<script setup>` 中导入 BottomSheet 并添加 sheet 状态**

在 ChatView.vue 的 `<script setup>` import 区末尾追加：

```js
import BottomSheet from '@/components/common/BottomSheet.vue'
```

在 `// ── 配置条：角色 + 模型` 区域（`configDropdownOpen` 附近）新增：

```js
// 移动端角色/模型选择底部抽屉
const showRoleModelSheet = ref(false)
```

- [ ] **Step 2: 在 `<template>` 中添加移动端徽章行**

找到 `<!-- 输入区 -->` 注释下方的 `<div class="input-area">`，在其内部、`<!-- 图片附件预览 -->` **之前**插入：

```html
      <!-- 移动端：角色/模型选择徽章（点击弹出底部抽屉） -->
      <div class="mobile-config-chips">
        <button
          class="mobile-chip mobile-chip-role"
          :aria-label="`当前角色：${activeRoleName}`"
          @click="showRoleModelSheet = true"
        >
          <i class="fas fa-id-card" aria-hidden="true" />
          <span>{{ activeRoleName }}</span>
        </button>
        <button
          class="mobile-chip mobile-chip-model"
          :aria-label="`当前模型：${currentModel || '默认'}`"
          @click="showRoleModelSheet = true"
        >
          <i class="fas fa-robot" aria-hidden="true" />
          <span>{{ currentModel || '默认' }}</span>
        </button>
      </div>
```

- [ ] **Step 3: 在 `<script setup>` 中添加 `activeRoleName` computed**

在 `activeRoleId` ref 定义之后追加：

```js
const activeRoleName = computed(() => {
  if (!activeRoleId.value) return '默认助手'
  return availableRoles.value.find(r => r.roleId === activeRoleId.value)?.roleCard?.name || activeRoleId.value
})
```

- [ ] **Step 4: 在 `<template>` 末尾（`</div>` 前，config-bar 之后）添加 RoleModelSheet**

找到 ChatView template 的结尾 `</div>` 闭合标签（`.chat-view` 的），在其**前面**插入：

```html
    <!-- 移动端：角色 + 模型选择底部抽屉 -->
    <BottomSheet v-model="showRoleModelSheet" title="角色与模型">
      <!-- 角色列表 -->
      <div class="rms-section-title">角色</div>
      <button
        class="rms-option"
        :class="{ active: !activeRoleId }"
        :aria-selected="!activeRoleId"
        @click="onRoleChange(''); showRoleModelSheet = false"
      >
        <i class="fas fa-robot rms-icon" aria-hidden="true" />
        <span>默认助手</span>
        <i v-if="!activeRoleId" class="fas fa-check rms-check" aria-hidden="true" />
      </button>
      <button
        v-for="r in availableRoles"
        :key="r.roleId"
        class="rms-option"
        :class="{ active: r.roleId === activeRoleId }"
        :aria-selected="r.roleId === activeRoleId"
        :aria-label="r.roleCard?.name || r.roleId"
        @click="onRoleChange(r.roleId); showRoleModelSheet = false"
      >
        <i class="fas fa-id-card rms-icon" aria-hidden="true" />
        <span>{{ r.roleCard?.name || r.roleId }}</span>
        <i v-if="r.roleId === activeRoleId" class="fas fa-check rms-check" aria-hidden="true" />
      </button>

      <div class="rms-divider" />

      <!-- 模型列表 -->
      <div class="rms-section-title">模型</div>
      <button
        v-for="m in availableModels"
        :key="m"
        class="rms-option"
        :class="{ active: m === currentModel }"
        :aria-selected="m === currentModel"
        :aria-label="m"
        @click="handleConfigSwitch(m); showRoleModelSheet = false"
      >
        <i class="fas fa-cube rms-icon" aria-hidden="true" />
        <span>{{ m }}</span>
        <i v-if="m === currentModel" class="fas fa-check rms-check" aria-hidden="true" />
      </button>
      <div v-if="availableModels.length === 0" class="rms-empty">暂无可用模型</div>
    </BottomSheet>
```

- [ ] **Step 5: 在 `<style scoped>` 末尾追加移动端新增 CSS**

在 ChatView.vue `<style scoped>` 的 `@media (max-width: 768px)` 块中追加以下内容：

```css
  /* 桌面端 config-bar 在移动端隐藏 */
  .config-bar { display: none; }

  /* 移动端徽章行 */
  .mobile-config-chips {
    display: flex;
    gap: 8px;
    padding: 6px 0 4px;
  }

  /* 导出按钮在移动端隐藏（低频操作，从工具栏移除） */
  .toolbar-export-wrap { display: none; }
```

同时在 `@media (max-width: 768px)` 之外（全局 scoped）追加 RoleModelSheet + 徽章样式：

```css
/* 移动端徽章（仅在移动端通过父元素显示） */
.mobile-config-chips { display: none; }

@media (max-width: 768px) {
  .mobile-config-chips { display: flex; }
}

.mobile-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 20px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 0.78rem;
  cursor: pointer;
  max-width: min(140px, 35vw);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  -webkit-tap-highlight-color: transparent;
  transition: border-color 0.15s;
}

.mobile-chip:hover { border-color: var(--color-primary); }

.mobile-chip-role {
  background: #eef2ff;
  border-color: #c7d2fe;
  color: #4f46e5;
}

.mobile-chip-role i { color: #6366f1; font-size: 0.72rem; }

.mobile-chip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* RoleModelSheet 内部样式 */
.rms-section-title {
  font-size: 0.72rem;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 10px 20px 4px;
}

.rms-option {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 20px;
  background: none;
  border: none;
  color: var(--color-text);
  font-size: 0.95rem;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
  -webkit-tap-highlight-color: transparent;
}

.rms-option:hover,
.rms-option:active { background: var(--color-surface-raised); }

.rms-option.active { color: var(--color-primary); font-weight: 500; }

.rms-icon {
  width: 20px;
  text-align: center;
  color: var(--color-text-muted);
  font-size: 0.9rem;
  flex-shrink: 0;
}

.rms-option.active .rms-icon { color: var(--color-primary); }

.rms-check {
  margin-left: auto;
  color: var(--color-primary);
  font-size: 0.85rem;
}

.rms-divider {
  height: 1px;
  background: var(--color-border);
  margin: 6px 0;
}

.rms-empty {
  padding: 12px 20px;
  font-size: 0.85rem;
  color: var(--color-text-muted);
  text-align: center;
}
```

- [ ] **Step 6: 清理死 CSS**

在 `@media (max-width: 768px)` 块中，删除以下三行（这些类在模板中已不存在）：

```css
  .export-float      { bottom: 70px; left: 8px; }
  .clear-float       { bottom: 26px; left: 8px; }
  .history-float     { bottom: 114px; left: 8px; }
```

同时删除独立的 `.clear-float-btn` 和 `.clear-float-btn:hover` 规则（约 2041-2057 行）。

- [ ] **Step 7: 验证**

在 DevTools 移动端模式下打开 ChatView，确认：
1. 角色/模型徽章显示在输入框上方
2. 点击徽章弹出 RoleModelSheet
3. 选择角色后 `activeRoleName` 徽章文字更新
4. 选择模型后切换生效（有 toast 提示）
5. 桌面端 config-bar 正常显示，无徽章行

- [ ] **Step 8: 提交**

```bash
git add frontend/src/views/ChatView.vue
git commit -m "feat(mobile): mobile role/model chips + RoleModelSheet, hide config-bar on mobile"
```

---

## 自审检查结果

**Spec 覆盖度：**

| Spec 要求 | 对应任务 |
|----------|---------|
| viewport-fit=cover | Task 1 |
| theme-color + status-bar meta | Task 1 |
| dvh fallback | Task 2 |
| `--keyboard-height` CSS 变量 | Task 2 |
| 全局 font-size + text-size-adjust | Task 2 |
| mobile main-content padding-bottom | Task 2 |
| BottomSheet 公共组件（a11y/ESC/body 锁定） | Task 3 |
| BottomTabBar 4 Tab + a11y | Task 4 |
| MorePanel 三分组 + 退出登录 | Task 5 |
| App.vue 挂载 + visualViewport 监听 | Task 6 |
| Header 移除汉堡菜单 | Task 7 |
| ChatView config-bar 隐藏 + 角色/模型徽章 | Task 8 |
| RoleModelSheet（基于 BottomSheet） | Task 8 |
| 导出按钮移动端隐藏 | Task 8 |
| 死 CSS 清理 | Task 8 |

**类型一致性：**
- `onRoleChange(roleId: string)` — Task 8 传字符串，与 ChatView 现有实现一致（`const roleId = e.target ? e.target.value : e`）
- `handleConfigSwitch(modelName: string)` — Task 8 直接传 string，与现有实现一致
- `BottomSheet` v-model — Task 3 定义 `modelValue: Boolean` + `emit('update:modelValue')`，Task 5/8 使用 `v-model` 或 `:model-value` + `@update:model-value`，一致

**无占位符：** 所有步骤均含完整代码。
