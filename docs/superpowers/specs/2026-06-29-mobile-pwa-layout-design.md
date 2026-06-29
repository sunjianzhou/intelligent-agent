# Mobile PWA Layout Design — iPhone 16 适配

**日期**：2026-06-29  
**目标设备**：iPhone 16（393×852 CSS px，`env(safe-area-inset-top)` ≈ 59px，`env(safe-area-inset-bottom)` ≈ 34px）  
**范围**：`≤768px` 媒体查询，桌面端行为不变

---

## 一、问题现状

| 问题 | 影响 |
|------|------|
| `body { height: 100vh }` | iOS Safari 地址栏展开/收起时布局跳动 |
| 无 `env(safe-area-inset-bottom)` | 输入框被 Home Indicator 遮挡 |
| 无 `viewport-fit=cover` | Dynamic Island 区域留白，内容缩在安全边距内 |
| 汉堡菜单在左上角 | 拇指要跨屏操作，导航两次点击才可达 |
| config-bar 常驻 | 占用聊天区垂直空间，393px 宽下拥挤 |
| 浮动按钮硬编码 bottom 值 | 与键盘/底部区域冲突，视觉干扰 |

---

## 二、整体布局（移动端）

```
┌─────────────────────────────────────────┐
│  ← env(safe-area-inset-top) padding →   │  Dynamic Island 留空
├─────────────────────────────────────────┤
│  Header（精简）                          │  60px，无汉堡按钮
│  页面标题 + 连接状态 + 主题切换            │
├─────────────────────────────────────────┤
│                                         │
│  <router-view>（页面内容）               │  flex: 1, overflow: hidden
│                                         │
├─────────────────────────────────────────┤
│  Bottom Tab Bar                         │  56px
│  聊天  角色  记忆  项目  更多             │  + env(safe-area-inset-bottom)
└─────────────────────────────────────────┘
```

**关键 CSS 变更：**

```css
/* App.vue */
body { height: 100dvh; overflow: hidden; }
.main-layout { height: 100dvh; }

/* 移动端主内容区为 tab bar 留出空间 */
@media (max-width: 768px) {
  .main-content {
    padding-bottom: calc(56px + env(safe-area-inset-bottom));
  }
}
```

**`index.html` viewport meta（确认或新增）：**
```html
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
```

---

## 三、新增组件

### 3.1 `BottomTabBar.vue`

**位置**：`frontend/src/components/layout/BottomTabBar.vue`  
**挂载**：`App.vue` 中 `v-if="authStore.isLoggedIn"` 块内，`<StatusBar>` 之前；通过 CSS `@media (max-width: 768px)` 控制显隐

**Tab 定义：**

| index | label | icon | 行为 |
|-------|-------|------|------|
| 0 | 聊天 | `fa-comment` | `router.push('/chat')` |
| 1 | 角色 | `fa-id-card` | `router.push('/roles')` |
| 2 | 记忆 | `fa-brain` | `router.push('/memory')` |
| 3 | 项目 | `fa-folder` | `router.push('/project')` |
| 4 | 更多 | `fa-ellipsis-h` | 打开 `MorePanel`（不走路由） |

**激活态判断：**
- Tab 0-3：`route.path === tab.path`
- Tab 4（更多）：`route.path` 不匹配任何前 4 项时高亮

**样式要点：**
- `position: fixed; bottom: 0; left: 0; right: 0; z-index: 50`
- `height: calc(56px + env(safe-area-inset-bottom))`
- `padding-bottom: env(safe-area-inset-bottom)`
- 背景：`var(--color-surface)`，顶部 `1px solid var(--color-border)`
- 激活图标+文字：`var(--color-primary)`；激活 Tab 顶部 `2px solid var(--color-primary)`
- 未激活：`var(--color-text-muted)` + hover 态 `var(--color-text-secondary)`
- 5 等分 flex 布局，每个 Tab 纵向排列图标（18px）+ 文字（10px）

### 3.2 `MorePanel.vue`

**位置**：`frontend/src/components/layout/MorePanel.vue`  
**触发**：`BottomTabBar` 通过 `emit('open-more')` → `App.vue` 持有 `showMorePanel` ref → v-model 传入

**结构：**
- 遮罩层（`rgba(0,0,0,0.45)` 点击关闭）
- 底部面板（`border-radius: 20px 20px 0 0`，白/暗色跟主题），`max-height: 80vh`，内部可滚动
- 顶部拖拽条（`4px × 40px` 圆角灰条）
- 分组一「常用」：知识库（`/knowledge`）、图片生成（`/image`）
- 分组二「管理后台」：工具管理、Skill、MCP 配置、模型管理、任务管理、操作日志、统计分析、系统信息
- 底部：退出登录（红色，复用现有 `authStore.logout()` 逻辑）
- 每项：图标 + 文字，点击后关闭面板并导航

**动画：** `transform: translateY(100%)` → `translateY(0)`，`transition: 0.28s ease`

---

## 四、Header.vue 移动端精简

**改动：**
- `@media (max-width: 768px)` 下隐藏 `.mobile-menu-btn`（汉堡按钮已被 Tab Bar 取代）
- 移除整个 `mobile-drawer` 相关逻辑（`showMobileMenu` ref、`mobile-nav` 模板、对应 CSS）
- Header 保留：页面标题 + 连接状态 + 主题切换

> Sidebar 在移动端已经是 `display: none`，不需要改动。

---

## 五、ChatView.vue 移动端改造

### 5.1 config-bar 隐藏 + 角色/模型徽章

**在 `≤768px` 下：**
- `.config-bar { display: none }` 
- 在 `input-area` 内顶部插入 `.mobile-config-chips` 行：
  ```
  [🎭 <role_name>]  [🤖 <model_name>]
  ```
  - 角色徽章：品牌色浅背景（`#eef2ff`），限宽 `min(120px, 40vw)`，超出省略
  - 模型徽章：灰色背景，限宽同上
  - 点击任一徽章 → 弹出 `RoleModelSheet`（底部抽屉）

### 5.2 `RoleModelSheet`（内联在 ChatView，不抽组件）

- 遮罩 + 底部面板，结构同 MorePanel
- 面板内两个 section：
  - **角色**：`v-for roles`，单选列表，当前激活项有勾 + 高亮；点击调用现有 `activateRole(id)` 逻辑
  - **模型**：`v-for availableModels`，单选列表，点击调用现有 `store.switchModel()` 逻辑
- 确认后关闭面板

### 5.3 浮动按钮移除，功能整合进 input-meta

**移除：** `.export-float`、`.clear-float`、`.history-float` 三个悬浮按钮（CSS + 模板）

**在 `≤768px` 下，input-meta 工具栏（`.input-toolbar`）显示：**
- 历史（`fa-history`）— 原 `history-float` 功能
- 导出（`fa-download`）— 原 `export-float` 功能（保留下拉菜单）
- 清空（`fa-trash`）— 原 `clear-float` 功能
- 桌面端这三个按钮已在 `.toolbar-btn` 中，移动端只需确保显示（当前桌面也有 input-meta 工具栏，无需新增逻辑）

### 5.4 安全区说明

移动端主内容区已通过 `padding-bottom: calc(56px + env(safe-area-inset-bottom))` 为 Tab Bar + Home Indicator 留出空间（见第二节）。`input-area` 作为内容区末尾元素，不额外添加 `env(safe-area-inset-bottom)`，避免双重叠加。

`index.html` 的 `viewport-fit=cover` 配合 Tab Bar 自身的 `padding-bottom: env(safe-area-inset-bottom)` 确保 Home Indicator 区域由 Tab Bar 背景填充，视觉无露底。

---

## 六、涉及文件清单

| 文件 | 操作 |
|------|------|
| `frontend/index.html` | 确认/修改 viewport meta，加 `viewport-fit=cover` |
| `frontend/src/App.vue` | 挂载 `<BottomTabBar>`，修 `100dvh`，传递 `showMorePanel` |
| `frontend/src/styles/main.css` | `body` 改 `100dvh`，移动端 `main-content` padding-bottom |
| `frontend/src/components/layout/BottomTabBar.vue` | **新建** |
| `frontend/src/components/layout/MorePanel.vue` | **新建** |
| `frontend/src/components/layout/Header.vue` | 移除汉堡菜单相关代码 |
| `frontend/src/views/ChatView.vue` | config-bar 隐藏 + 徽章 + RoleModelSheet + 浮动按钮整合 + safe-area |

---

## 七、不在本次范围内

- 桌面端导航和布局（不变）
- 其他页面（MemoryView、TasksView 等）的内容级移动适配（现有响应式已够用）
- 安卓端额外适配（`env()` 在现代 Android Chrome 也支持，行为一致）
- 深色主题在新组件中的适配（BottomTabBar / MorePanel 使用 CSS 变量，自动跟随）
