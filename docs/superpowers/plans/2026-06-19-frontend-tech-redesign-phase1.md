# Frontend 深色科技感改版 · Phase 1（设计令牌 + 外壳）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `frontend/` 落地一套深色科技感设计令牌（颜色/间距/圆角/阴影/字号），并用它重做 Header、Sidebar、StatusBar 和 App 外壳，让整个应用从"白底卡片悬浮在紫色渐变背景上"变成"全屏深色科技控制台"的视觉效果。

**Architecture:** 纯 CSS 自定义属性（CSS variables），定义在 `frontend/src/styles/main.css` 的 `:root`（浅色默认值）与 `[data-theme="dark"]`（深色覆盖值）选择器下。各 Vue 组件的 `<style scoped>` 改为引用 `var(--token-name)` 而不是硬编码颜色/数值。不引入新依赖，不改交互逻辑。

**Tech Stack:** Vue 3 (`<script setup>`), 原生 CSS 自定义属性，Vite。

## Global Constraints

- 不引入新的 npm 依赖（项目已有 `element-plus` 但本计划不做组件级替换，只同步其少量 CSS 变量）
- 不修改路由结构、不增删导航项的"目标页面"（`routes.config.js` 中的数据本身不变，只调整 `Sidebar.vue` 如何渲染它们）
- 不修改 WebSocket / Pinia store / 表单校验等任何 JS 业务逻辑
- 本阶段（Phase 1）只涉及：`frontend/src/styles/main.css`、`frontend/src/components/layout/Header.vue`、`frontend/src/components/layout/Sidebar.vue`、`frontend/src/components/layout/StatusBar.vue`、`frontend/src/App.vue`。其余 18 个 view 文件的逐页替换是后续 Phase 2+ 计划，不在本计划范围内
- 每个任务完成后必须能 `npm run build` 成功，且能在浏览器里手动确认外观（项目目前没有 Vue 组件级单测，`@vue/test-utils` 未安装，因此本计划的"验证"以 build 通过 + 手动浏览器检查为准，不新增测试依赖）

---

## File Structure

| 文件 | 改动内容 |
|---|---|
| `frontend/src/styles/main.css` | 新增/重写设计令牌（颜色/间距/圆角/阴影/字号）+ 通用 atom class（`.btn-primary` `.btn-ghost` `.card` `.badge-*` `.input`）+ Element Plus 变量同步 |
| `frontend/src/components/layout/Sidebar.vue` | 模板：三段平铺导航 → "常用"一级 + "管理后台"可折叠分组（复用已有的 `ADMIN_ITEMS` 导出）；样式：硬编码颜色/间距 → 令牌；新增折叠交互的 `<script setup>` 状态 |
| `frontend/src/components/layout/Header.vue` | 样式：硬编码颜色 → 令牌；顶部新增强调色描边 |
| `frontend/src/components/layout/StatusBar.vue` | 样式：硬编码颜色 → 令牌 |
| `frontend/src/App.vue` | 全局 `<style>`：去掉"白卡片悬浮在紫色渐变背景上"的居中卡片布局，改为全屏深色令牌驱动的应用外壳；删除随之失效的 `[data-theme="dark"] body/.main-layout/.page-container` 覆盖规则（其它尚未迁移的 view 覆盖规则保留不动） |

---

### Task 1: 设计令牌与通用组件样式

**Files:**
- Modify: `frontend/src/styles/main.css`

**Interfaces:**
- Produces：本计划及后续所有页面迁移都会引用的 CSS 变量名 —
  颜色：`--color-bg` `--color-surface` `--color-surface-raised` `--color-border` `--color-primary` `--color-primary-hover` `--color-accent` `--color-text` `--color-text-secondary` `--color-text-muted` `--color-danger` `--color-warn` `--color-success` `--color-sidebar-bg` `--color-sidebar-text`
  间距：`--space-1` … `--space-6`（4/8/12/16/24/32px）
  圆角：`--radius-sm` `--radius-md` `--radius-lg`（6/10/16px）
  阴影：`--shadow-sm` `--shadow-glow`
  字号：`--text-xs` `--text-sm` `--text-base` `--text-lg` `--text-xl`
  Atom class：`.btn-primary` `.btn-ghost` `.card` `.badge` `.badge-success` `.badge-warn` `.badge-danger` `.input`

- [ ] **Step 1: 替换 `main.css` 的令牌定义与新增 atom class**

把 `frontend/src/styles/main.css` 第 1-15 行的 `:root { ... }` 块替换为下面内容，并在文件末尾追加 `[data-theme="dark"]` 覆盖块与 atom class 块：

```css
/* ── 设计令牌（Phase 1 深色科技感改版）── */
:root {
  /* 颜色 — 浅色主题（默认） */
  --color-bg:             #f4f6fb;
  --color-surface:        #ffffff;
  --color-surface-raised: #f0f1ff;
  --color-border:         #e0e3e8;

  --color-primary:       #667eea;
  --color-primary-hover: #5a6fd6;
  --color-accent:        #2f9e7a;

  --color-text:           #1f2430;
  --color-text-secondary: #5b6472;
  --color-text-muted:     #8b94a3;

  --color-danger:  #e5484d;
  --color-warn:    #f59f00;
  --color-success: #2f9e44;

  --color-sidebar-bg:   #11151c;
  --color-sidebar-text: rgba(255,255,255,0.78);

  /* 间距 */
  --space-1: 4px;  --space-2: 8px;  --space-3: 12px;
  --space-4: 16px; --space-5: 24px; --space-6: 32px;

  /* 圆角 */
  --radius-sm: 6px; --radius-md: 10px; --radius-lg: 16px;

  /* 阴影 */
  --shadow-sm:   0 1px 3px rgba(15,23,42,0.08);
  --shadow-glow: 0 0 0 1px rgba(102,126,234,0.35), 0 4px 16px rgba(102,126,234,0.25);

  /* 字号 */
  --text-xs: 0.72rem; --text-sm: 0.82rem; --text-base: 0.92rem;
  --text-lg: 1.05rem; --text-xl: 1.3rem;
}

/* 颜色 — 深色主题覆盖 */
[data-theme="dark"] {
  --color-bg:             #0d1117;
  --color-surface:        #161b22;
  --color-surface-raised: #1f2733;
  --color-border:         rgba(255,255,255,0.08);

  --color-primary:       #7c8cf8;
  --color-primary-hover: #8e9dff;
  --color-accent:        #4fc3a1;

  --color-text:           #e6e8eb;
  --color-text-secondary: #9aa4b2;
  --color-text-muted:     #6b7480;

  --color-danger:  #f87171;
  --color-warn:    #fbbf24;
  --color-success: #34d399;

  --color-sidebar-bg:   #0a0d12;
  --color-sidebar-text: rgba(255,255,255,0.78);

  --shadow-sm:   0 1px 3px rgba(0,0,0,0.5);
  --shadow-glow: 0 0 0 1px rgba(124,140,248,0.45), 0 4px 20px rgba(124,140,248,0.4);
}

/* Element Plus 变量同步（仅同步颜色，不替换其组件） */
:root, [data-theme="dark"] {
  --el-color-primary: var(--color-primary);
  --el-color-success:  var(--color-success);
  --el-color-warning:  var(--color-warn);
  --el-color-danger:   var(--color-danger);
}
[data-theme="dark"] {
  --el-bg-color: var(--color-surface);
  --el-text-color-primary: var(--color-text);
  --el-text-color-regular: var(--color-text-secondary);
  --el-border-color: var(--color-border);
  --el-border-color-light: var(--color-border);
  --el-fill-color-blank: var(--color-surface);
}
```

在文件末尾（`.gap-5 { gap: 20px; }` 之后）追加：

```css

/* ── 通用 atom class（Phase 1）── */
.btn-primary {
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  padding: 8px 16px; border-radius: var(--radius-sm); border: none;
  background: var(--color-primary); color: #fff;
  font-size: var(--text-sm); font-weight: 500; cursor: pointer;
  transition: background 0.2s, box-shadow 0.2s;
}
.btn-primary:hover:not(:disabled) { background: var(--color-primary-hover); box-shadow: var(--shadow-glow); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-ghost {
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  padding: 8px 16px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary);
  font-size: var(--text-sm); cursor: pointer; transition: all 0.2s;
}
.btn-ghost:hover { border-color: var(--color-primary); color: var(--color-primary); }

.card {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: var(--space-4); box-shadow: var(--shadow-sm);
}

.badge {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 10px; border-radius: 20px;
  font-size: var(--text-xs); font-weight: 500;
}
.badge-success { background: rgba(52,211,153,0.15); color: var(--color-success); }
.badge-warn    { background: rgba(251,191,36,0.15);  color: var(--color-warn); }
.badge-danger  { background: rgba(248,113,113,0.15); color: var(--color-danger); }

.input {
  width: 100%; padding: 8px 12px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); font-size: var(--text-sm);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.input:focus {
  outline: none; border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(102,126,234,0.15);
}
```

- [ ] **Step 2: 验证 build 通过**

Run: `cd frontend && npm run build`
Expected: 命令成功退出（exit code 0），无 CSS 语法错误。这一步只新增了未被引用的变量与 class，不会影响现有页面渲染。

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/styles/main.css
git commit -m "feat(frontend): add dark-tech design tokens and shared atom classes"
```

---

### Task 2: Sidebar 改造（令牌化 + 管理后台可折叠分组）

**Files:**
- Modify: `frontend/src/components/layout/Sidebar.vue`

**Interfaces:**
- Consumes：Task 1 产出的 `--color-sidebar-bg` `--color-sidebar-text` `--color-accent` `--color-primary` `--space-*` `--radius-*` 等令牌；`frontend/src/config/routes.config.js` 已导出的 `NAV_ITEMS`、`ADMIN_ITEMS`（无需修改该文件）
- Produces：无（叶子组件，无下游任务依赖其内部实现）

- [ ] **Step 1: 替换模板与脚本，加入折叠状态**

把 `frontend/src/components/layout/Sidebar.vue` 的 `<template>` 中第 8-36 行（原来的「常用」「配置」「系统」三个 `<nav class="nav-section">` 块）替换为：

```html
    <!-- 高频操作区 -->
    <nav class="nav-section">
      <div class="nav-section-label">常用</div>
      <router-link
        v-for="item in NAV_ITEMS"
        :key="item.name"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.name) }"
      >
        <i :class="item.icon"></i>
        <span>{{ item.label }}</span>
      </router-link>
    </nav>

    <!-- 管理后台（可折叠，合并原"配置"+"系统"两组） -->
    <nav class="nav-section admin-group">
      <button class="nav-section-toggle" type="button" @click="adminExpanded = !adminExpanded">
        <span class="nav-section-label">管理后台</span>
        <i class="fas fa-chevron-right toggle-caret" :class="{ expanded: adminExpanded }"></i>
      </button>
      <div v-show="adminExpanded" class="admin-group-items">
        <router-link
          v-for="item in ADMIN_ITEMS"
          :key="item.name"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.name) }"
        >
          <i :class="item.icon"></i>
          <span>{{ item.label }}</span>
        </router-link>
      </div>
    </nav>
```

把 `<script setup>` 中的 import 行：

```js
import { NAV_ITEMS, CONFIG_ITEMS, SYSTEM_ITEMS } from '@/config/routes.config'
```

替换为：

```js
import { NAV_ITEMS, ADMIN_ITEMS } from '@/config/routes.config'
```

在 `const route = useRoute()` 与 `const isActive = ...` 之间（原第 133-135 行附近）新增折叠状态，默认根据当前路由是否落在管理后台分组里来决定初始展开/收起：

```js
const route = useRoute()

// 当前路由若属于"管理后台"分组，默认展开该分组，避免选中项被隐藏
const adminExpanded = ref(ADMIN_ITEMS.some((item) => item.name === route.name))

const isActive = computed(() => (name) => route.name === name)
```

并把脚本顶部的 `import { computed, onMounted } from 'vue'` 改为：

```js
import { ref, computed, onMounted } from 'vue'
```

- [ ] **Step 2: 重写样式，令牌化 + 折叠分组样式**

把 `<style scoped>` 中第 139-209 行（`.sidebar` 到 `.nav-section + .nav-section` 这一段，即侧边栏容器、logo、导航分组样式）替换为：

```css
.sidebar {
  width: 220px;
  background: var(--color-sidebar-bg);
  color: var(--color-sidebar-text);
  padding: var(--space-5) var(--space-4);
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow-y: auto;
  scrollbar-width: none;
}
.sidebar::-webkit-scrollbar { display: none; }

.logo {
  text-align: center;
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid rgba(255,255,255,0.1);
  flex-shrink: 0;
}
.logo h1 {
  font-size: 1.3rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
}
.logo i { color: var(--color-accent); }

/* ── 导航分区 ─────────────────────────────────────────── */
.nav-section {
  flex-shrink: 0;
  margin-bottom: var(--space-1);
}
.nav-section-label {
  font-size: var(--text-xs);
  color: rgba(255,255,255,0.35);
  text-transform: uppercase;
  letter-spacing: 0.07em;
  padding: 6px 10px 3px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 9px 12px;
  color: var(--color-sidebar-text);
  text-decoration: none;
  border-radius: var(--radius-sm);
  margin-bottom: 2px;
  border-left: 2px solid transparent;
  transition: all 0.2s;
  font-size: var(--text-base);
}
.nav-item:hover { background: rgba(255,255,255,0.08); color: #fff; }
.nav-item.active {
  background: rgba(255,255,255,0.1);
  color: #fff;
  border-left-color: var(--color-accent);
}
.nav-item i {
  width: 18px;
  text-align: center;
  font-size: 0.95rem;
  flex-shrink: 0;
}
.nav-item.active i { color: var(--color-accent); }

/* 分区间分隔线 */
.nav-section + .nav-section {
  border-top: 1px solid rgba(255,255,255,0.06);
  padding-top: var(--space-2);
  margin-top: var(--space-1);
}

/* ── 管理后台折叠分组 ─────────────────────────────────── */
.nav-section-toggle {
  width: 100%;
  display: flex; align-items: center; justify-content: space-between;
  background: none; border: none; cursor: pointer;
  padding: 6px 10px 3px;
}
.nav-section-toggle .nav-section-label { padding: 0; }
.toggle-caret {
  font-size: 0.65rem;
  color: rgba(255,255,255,0.35);
  transition: transform 0.2s;
}
.toggle-caret.expanded { transform: rotate(90deg); }
.admin-group-items { display: flex; flex-direction: column; }
```

- [ ] **Step 3: 验证 build 通过**

Run: `cd frontend && npm run build`
Expected: exit code 0。

- [ ] **Step 4: 手动浏览器验证**

Run: `cd frontend && npm run dev`，打开浏览器访问 dev server 地址，登录后检查：
- 侧边栏顶部「常用」6 项正常显示且可点击跳转
- 「管理后台」默认折叠，点击标题能展开/收起，箭头图标跟着旋转
- 当前访问的页面（无论在「常用」还是「管理后台」分组里）在导航项左侧有一条强调色竖线
- 直接刷新一个管理后台页面（如 `/admin/system`）时，「管理后台」分组应自动展开（不应隐藏当前选中项）

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/components/layout/Sidebar.vue
git commit -m "feat(frontend): tokenize sidebar styles and collapse admin nav into one group"
```

---

### Task 3: Header 改造（令牌化 + 顶部强调描边）

**Files:**
- Modify: `frontend/src/components/layout/Header.vue`

**Interfaces:**
- Consumes：Task 1 的 `--color-surface` `--color-border` `--color-text` `--color-primary` `--color-success` `--color-danger` `--color-sidebar-bg` 等令牌
- Produces：无

- [ ] **Step 1: 重写 `<style scoped>` 中的颜色相关规则**

把 `frontend/src/components/layout/Header.vue` 中以下几处硬编码替换：

`.mobile-menu-btn` 的 `color: #667eea;` → `color: var(--color-primary);`

`.mobile-nav` 的 `background: #2c3e50;` → `background: var(--color-sidebar-bg);`

`.header` 块：

```css
.header {
  height: 60px;
  background: var(--color-surface);
  border-top: 2px solid var(--color-primary);
  border-bottom: 1px solid var(--color-border);
  padding: 0 var(--space-5);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}
```

`.header-left .page-title` 块：

```css
.header-left .page-title {
  font-size: var(--text-lg);
  color: var(--color-text);
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 500;
}
.header-left .page-title i { color: var(--color-primary); }
```

连接状态与主题按钮：

```css
.status-dot.connected           { background: var(--color-success); }
.status-dot.disconnected-sudden { background: var(--color-danger); animation: pulse-dot 1.5s infinite; }
.status-dot.disconnected-init   { background: var(--color-text-muted); }
.status-text.connected           { color: var(--color-success); }
.status-text.disconnected-sudden { color: var(--color-danger); }
.status-text.disconnected-init   { color: var(--color-text-muted); }

.theme-btn {
  width: 34px; height: 34px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; font-size: 0.9rem;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.theme-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
```

（其余样式如 `.mobile-drawer`、`.mobile-nav-item`、媒体查询保持不变，只是上面列出的几处颜色硬编码需要替换。)

- [ ] **Step 2: 验证 build 通过**

Run: `cd frontend && npm run build`
Expected: exit code 0。

- [ ] **Step 3: 手动浏览器验证**

`npm run dev` 后检查 Header 顶部有一条明显的强调色细描边，浅色/深色主题切换按钮都能正常点击且图标对比清晰，连接状态点的绿/红颜色在深色主题下依然清晰可辨。

- [ ] **Step 4: Commit**

```bash
cd frontend
git add src/components/layout/Header.vue
git commit -m "feat(frontend): tokenize header styles and add top accent border"
```

---

### Task 4: StatusBar 改造（令牌化）

**Files:**
- Modify: `frontend/src/components/layout/StatusBar.vue`

**Interfaces:**
- Consumes：Task 1 的 `--color-surface` `--color-border` `--color-text` `--color-text-muted` 令牌
- Produces：无

- [ ] **Step 1: 重写 `<style scoped>`**

把 `frontend/src/components/layout/StatusBar.vue` 的整个 `<style scoped>` 块替换为：

```css
<style scoped>
.status-bar {
  height: 32px;
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
  padding: 0 var(--space-5);
  display: flex;
  align-items: center;
  gap: var(--space-5);
  font-size: var(--text-xs);
}

.status-item {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--color-text-secondary);
}

.status-icon { font-size: 0.7rem; }
.status-label { color: var(--color-text-muted); }
.status-value { color: var(--color-text); font-weight: 500; }
</style>
```

- [ ] **Step 2: 验证 build 通过**

Run: `cd frontend && npm run build`
Expected: exit code 0。

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/components/layout/StatusBar.vue
git commit -m "feat(frontend): tokenize status bar styles"
```

---

### Task 5: App.vue 外壳改造（去掉悬浮卡片布局，改为全屏令牌驱动外壳）

**Files:**
- Modify: `frontend/src/App.vue`

**Interfaces:**
- Consumes：Task 1 的 `--color-bg` `--color-warn` 等令牌
- Produces：无。本任务会删除 `[data-theme="dark"] body`、`[data-theme="dark"] .main-layout`、`[data-theme="dark"] .page-container` 三条覆盖规则（因为这三个元素改为令牌驱动后不再需要手写深色覆盖）；**不要删除**紧随其后的 `.chat-view` `.tools-view` 等其它 view 的深色覆盖规则——那些页面要等后续 Phase 计划逐页迁移令牌后才能删除对应规则，本任务删除会导致它们在深色主题下变白、出现样式倒退。

- [ ] **Step 1: 重写居中卡片布局为全屏外壳**

把 `frontend/src/App.vue` 的 `<style>` 块中第 82-157 行（从 `<style>` 开始到 `.page-container { ... }` 结束，即 reset、body、`.app`、`.main-layout`、`.main-content`、`.mock-mode-indicator`、`.page-container` 这几段）替换为：

```css
<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
  background: var(--color-bg);
  height: 100vh;
  overflow: hidden;
}

.app {
  width: 100vw;
  height: 100vh;
  display: flex;
}

.main-layout {
  width: 100%;
  height: 100%;
  background: var(--color-bg);
  display: flex;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.mock-mode-indicator {
  background: rgba(245, 159, 0, 0.15);
  color: var(--color-warn);
  padding: var(--space-2) var(--space-5);
  font-size: 0.9rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.page-container {
  flex: 1;
  overflow: hidden;
  background: var(--color-bg);
}
```

注意：原来的"手机端去掉 padding 和圆角，全屏显示"两个 `@media (max-width: 768px)` 块（分别针对 `.app` 和 `.main-layout`）此时已经没有 padding/圆角/box-shadow 可去掉了（桌面端现在本来就是全屏无圆角），直接删除这两个媒体查询块，不要保留空规则。

- [ ] **Step 2: 删除已经冗余的深色覆盖规则**

在 `<style>` 块的 `[data-theme="dark"]` 部分，删除以下三行（它们的元素已经在 Step 1 改为 `var(--color-bg)` 驱动，深浅色会自动跟随，不再需要手写覆盖）：

```css
[data-theme="dark"] body {
  background: linear-gradient(135deg, #2d3561 0%, #1a1b2e 100%);
}

[data-theme="dark"] .main-layout {
  background: #1e1f23;
  box-shadow: 0 20px 60px rgba(0,0,0,0.7);
}
```

以及紧随其后的"通用卡片/背景覆盖"组里这一行（只删这一行，组内其它行如 `.chat-view`、`.tools-view` 等保留）：

```css
[data-theme="dark"] .page-container       { background: #25262b !important; }
```

- [ ] **Step 3: 验证 build 通过**

Run: `cd frontend && npm run build`
Expected: exit code 0。

- [ ] **Step 4: 手动浏览器验证（本任务也是 Phase 1 的最终整体验证）**

`npm run dev`，打开浏览器，依次确认：
1. 应用现在是全屏铺满的深色科技控制台外观，不再是"白卡片悬浮在紫色渐变背景上"
2. 用 Header 右上角按钮切换浅色/深色主题，背景、侧边栏、卡片、文字颜色都应整体联动变化，没有"一部分变了一部分没变"的割裂感
3. 还没迁移的页面内容（如 ChatView、SystemView 内部的卡片）在两种主题下应保持原有可读性（这些页面本任务不动，靠 App.vue 里保留的旧覆盖规则继续兼容，是预期中的"暂时新旧混搭"，会在后续 Phase 里逐页统一）
4. 移动端宽度（缩小浏览器窗口到 768px 以下）下汉堡菜单、抽屉导航仍正常工作
5. 浏览器 console 没有新增的报错或警告

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/App.vue
git commit -m "feat(frontend): replace floating card shell with full-bleed token-driven app shell"
```

---

## Self-Review Notes

- **Spec coverage**：本计划覆盖了设计文档（`docs/superpowers/specs/2026-06-19-frontend-tech-redesign-design.md`）的「第 1 节 设计令牌」「第 2 节 共享外壳改造」「第 3 节 通用组件样式」「第 4 节步骤 1-2」。文档第 4 节步骤 3-7（业务页/后台管理页逐页替换、Element Plus 弹层细节核对）按文档本身的分阶段顺序，留给后续 Phase 计划，不在本计划重复覆盖。
- **Placeholder scan**：已检查，所有步骤均为完整可执行的代码块和命令，无 TBD/待补全内容。
- **Type/命名一致性**：所有任务引用的 CSS 变量名（`--color-*` `--space-*` `--radius-*` `--shadow-*` `--text-*`）与 Task 1 中定义的完全一致；`ADMIN_ITEMS` 的导入名与 `routes.config.js` 中已存在的导出名一致，未发明新名字。
