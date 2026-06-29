# Mobile PWA Layout Design — iPhone 16 适配

**日期**：2026-06-29（v2，代码评审后更新）
**目标设备**：

| 机型 | CSS 分辨率 | safe-area-inset-top | safe-area-inset-bottom |
|------|-----------|---------------------|------------------------|
| iPhone 16 | 393×852 | ≈ 59px | ≈ 34px |
| iPhone 16 Pro | 402×874 | ≈ 51px（Dynamic Island 较窄） | ≈ 34px |
| iPhone 16 Pro Max | 430×932 | ≈ 59px | ≈ 34px |
| iPhone SE 3 | 375×667 | 0（Home 键机型） | 0 |

> `env(safe-area-inset-*)` 由 CSS 动态读取，以实际设备值为准，上表仅供参考。

**范围**：`≤768px` 媒体查询，桌面端行为不变

---

## 一、问题现状

| 问题 | 影响 |
|------|------|
| `body { height: 100vh }` | iOS Safari 地址栏展开/收起时布局跳动 |
| 无 `env(safe-area-inset-bottom)` | 输入框被 Home Indicator 遮挡 |
| 无 `viewport-fit=cover` | Dynamic Island 区域留白，内容缩在安全边距内 |
| iOS 键盘弹出未处理 | input-area 被键盘遮挡，用户必须手动滚屏 |
| 汉堡菜单在左上角 | 拇指要跨屏操作，导航两次点击才可达 |
| config-bar 常驻 | 占用聊天区垂直空间，393px 宽下拥挤 |
| 浮动按钮硬编码 bottom 值 | 与键盘/底部区域冲突，视觉干扰 |
| 缺少 PWA theme-color/status-bar meta | 状态栏与 App 颜色割裂，安装到主屏后体验差 |
| 新组件缺 a11y 属性 | VoiceOver 无法使用底部导航和底部面板 |

---

## 二、整体布局（移动端）

```
┌─────────────────────────────────────────┐
│  ← env(safe-area-inset-top) padding →   │  Dynamic Island / 状态栏留空
├─────────────────────────────────────────┤
│  Header（精简）                          │  60px，无汉堡按钮
│  页面标题 + 连接状态 + 主题切换            │
├─────────────────────────────────────────┤
│                                         │
│  <router-view>（页面内容）               │  flex: 1, overflow: hidden
│                                         │
├─────────────────────────────────────────┤
│  Bottom Tab Bar                         │  56px
│  聊天  角色  记忆  更多                   │  + env(safe-area-inset-bottom)
└─────────────────────────────────────────┘
```

**关键 CSS 变更：**

```css
/* dvh 兜底写法（iOS 15.3 及以下不支持 dvh） */
body {
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}
.main-layout {
  height: 100vh;
  height: 100dvh;
}

/* 移动端主内容区为 tab bar 留出空间 */
@media (max-width: 768px) {
  .main-content {
    padding-bottom: calc(56px + env(safe-area-inset-bottom));
  }
}

/* 全局：防止 iOS 自动放大 <16px 表单字体触发页面缩放 */
input,
textarea,
select {
  font-size: 16px;
  -webkit-text-size-adjust: 100%;
}
```

**`index.html` `<head>` 新增/确认：**

```html
<!-- safe-area + viewport-fit -->
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">

<!-- PWA 主题色（跟随系统暗色模式） -->
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0a0a0a" media="(prefers-color-scheme: dark)">

<!-- iOS PWA 状态栏 -->
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
```

**iOS 键盘处理（`App.vue` mounted）：**

```js
// iOS PWA 键盘弹出时 visualViewport 收缩，用 CSS 变量通知各组件
if (window.visualViewport) {
  window.visualViewport.addEventListener('resize', () => {
    const kb = window.innerHeight - window.visualViewport.height
    document.documentElement.style.setProperty('--keyboard-height', `${Math.max(0, kb)}px`)
  })
}
```

```css
/* ChatView input-area 跟随键盘 */
@media (max-width: 768px) {
  .input-area {
    padding-bottom: var(--keyboard-height, 0px);
  }
}
```

---

## 三、新增组件

### 3.1 `BottomSheet.vue`（公共底部抽屉，被 MorePanel 和 RoleModelSheet 复用）

**位置**：`frontend/src/components/common/BottomSheet.vue`

**Props：**
- `modelValue: Boolean` — 显示/隐藏（v-model）
- `title: String` — 面板标题（可选）
- `maxHeight: String` — 默认 `'75vh'`

**行为：**
- 遮罩点击、ESC 键均触发关闭（`emit('update:modelValue', false)`）
- 打开时：`document.body.style.overflow = 'hidden'`；关闭时还原
- 打开时面板自动获取焦点（`ref.focus()`）
- 动画：`transform: translateY(100%) → translateY(0)`，`transition: 0.28s ease`

**a11y：**
```html
<div
  role="dialog"
  aria-modal="true"
  :aria-labelledby="title ? 'bs-title' : undefined"
  tabindex="-1"
  @keydown.esc="close"
>
  <h3 v-if="title" id="bs-title" class="sheet-title">{{ title }}</h3>
  <slot />
</div>
```

**样式：**
- 面板：`border-radius: 20px 20px 0 0`，背景 `var(--color-surface)`
- 顶部拖拽条：`4px × 40px` 圆角灰条，`aria-hidden="true"`
- 内部 `overflow-y: auto`，`max-height` 由 prop 决定

---

### 3.2 `BottomTabBar.vue`

**位置**：`frontend/src/components/layout/BottomTabBar.vue`
**挂载**：`App.vue` 中已登录块内；`@media (max-width: 768px)` 控制显隐

**Tab 定义（4 个，4 等分更宽敞）：**

| index | label | icon | 行为 |
|-------|-------|------|------|
| 0 | 聊天 | `fa-comment` | `router.push('/chat')` |
| 1 | 角色 | `fa-id-card` | `router.push('/roles')` |
| 2 | 记忆 | `fa-brain` | `router.push('/memory')` |
| 3 | 更多 | `fa-ellipsis-h` | 打开 `MorePanel`（不走路由） |

> "项目"从原设计的第 4 个常驻 Tab 移入 MorePanel，原因：4 Tab × 393px = 98px/Tab，比 5 Tab 的 78px/Tab 宽敞 25%，文字不截断。

**激活态判断：**
- Tab 0-2：`route.path === tab.path`
- Tab 3（更多）：当前路由不匹配前 3 项时高亮

**样式要点：**
- `position: fixed; bottom: 0; left: 0; right: 0; z-index: 50`
- `height: calc(56px + env(safe-area-inset-bottom))`
- `padding-bottom: env(safe-area-inset-bottom)`
- 背景：`var(--color-surface)`，顶部 `1px solid var(--color-border)`
- 激活 Tab：图标+文字 `var(--color-primary)`，顶部 `2px solid var(--color-primary)` 色条
- 未激活：`var(--color-text-muted)`

**a11y：**
```html
<nav class="bottom-tab-bar" role="tablist" aria-label="主导航">
  <button
    v-for="tab in tabs"
    role="tab"
    :aria-selected="isActive(tab)"
    :aria-label="tab.label"
    @click="tab.action()"
  >
    <i :class="tab.icon" aria-hidden="true" />
    <span>{{ tab.label }}</span>
  </button>
</nav>
```

---

### 3.3 `MorePanel.vue`

**位置**：`frontend/src/components/layout/MorePanel.vue`
**基于**：复用 `<BottomSheet>` 组件
**触发**：`BottomTabBar` emit `'open-more'` → `App.vue` 控制 `showMorePanel`

**内容分组（三级）：**

| 分组 | 入口 |
|------|------|
| **常用** | 项目（`/project`）、知识库（`/knowledge`）、图片生成（`/image`） |
| **AI 能力** | 模型管理、Skill 管理、MCP 配置 |
| **运维与系统** | 工具管理、任务管理、操作日志、统计分析、系统信息 |
| — | 退出登录（红色，底部固定） |

每项点击后关闭 MorePanel 并导航。

---

## 四、Header.vue 移动端精简

**移除：**
- `mobile-menu-btn`（汉堡按钮）
- `showMobileMenu` ref
- `mobile-drawer` 整个模板块
- `mobile-nav`、`mobile-nav-item`、`mobile-nav-divider`、`mobile-nav-section-title` 相关 CSS

**保留：** 页面标题 + 连接状态 + 主题切换（三者均已适配移动端）

> Sidebar 在移动端已是 `display: none`，不需要改动。

---

## 五、ChatView.vue 移动端改造

### 5.1 config-bar 隐藏 + 角色/模型徽章

```css
@media (max-width: 768px) {
  .config-bar { display: none; }
}
```

在 `input-area` 顶部插入 `.mobile-config-chips` 行（仅移动端显示）：

```
[🎭 <角色名>]  [🤖 <模型名>]
```

- 角色徽章：`#eef2ff` 背景，`max-width: min(140px, 35vw)`，超出省略
- 模型徽章：`var(--color-border)` 背景，`max-width: min(140px, 35vw)`，超出省略
- 点击任一徽章 → 打开 `RoleModelSheet`

### 5.2 `RoleModelSheet`（内联在 ChatView，基于 BottomSheet 组件）

```html
<BottomSheet v-model="showRoleModelSheet" title="角色与模型">
  <!-- 角色单选列表 -->
  <section class="sheet-section">
    <h4>角色</h4>
    <div v-for="role in roles" :key="role.id" class="sheet-option" ...>
      <span>{{ role.name }}</span>
      <i v-if="role.id === activeRoleId" class="fas fa-check" />
    </div>
  </section>
  <!-- 模型单选列表 -->
  <section class="sheet-section">
    <h4>模型</h4>
    <div v-for="m in availableModels" :key="m" class="sheet-option" ...>
      <span>{{ m }}</span>
      <i v-if="m === currentModel" class="fas fa-check" />
    </div>
  </section>
</BottomSheet>
```

点击角色项调用现有 `activateRole(id)`；点击模型项调用现有 `store.switchModel(m)`，选中后自动关闭。

### 5.3 浮动按钮处理 + input-meta 整合

**移除**（CSS + 模板）：`.export-float`、`.clear-float`、`.history-float` 三个悬浮按钮

**移动端 input-meta 工具栏显示：**
- **历史**（`fa-history`）— 保留，高频操作不应收进 MorePanel
- **清空**（`fa-trash`）— 保留
- **导出**（`fa-download`）— 移入 MorePanel「运维与系统」分组（低频）

桌面端三按钮已在 `.toolbar-btn`，移动端 CSS 确保 `.input-toolbar` 显示即可，无需新增逻辑。

### 5.4 安全区说明

- 移动端 `main-content` 的 `padding-bottom: calc(56px + env(safe-area-inset-bottom))` 为整体内容留出 Tab Bar + Home Indicator 空间
- `input-area` 自身通过 `var(--keyboard-height, 0px)` 跟随键盘（见第二节 CSS）
- 两者不叠加：`padding-bottom: calc(56px + env(safe-area-inset-bottom))` 是页面级；`var(--keyboard-height)` 是键盘弹出时的临时附加量，键盘收起后归零

---

## 六、涉及文件清单

| 文件 | 操作 |
|------|------|
| `frontend/index.html` | 修改 viewport meta（加 `viewport-fit=cover`）；新增 theme-color / iOS PWA meta |
| `frontend/src/App.vue` | 挂载 `<BottomTabBar>`；`100dvh` 兜底写法；`visualViewport` 键盘监听；传递 `showMorePanel` |
| `frontend/src/styles/main.css` | `body/main-layout` 改 `100dvh` 兜底；移动端 `main-content` padding-bottom；全局 `font-size: 16px` |
| `frontend/src/components/common/BottomSheet.vue` | **新建**（公共底部抽屉） |
| `frontend/src/components/layout/BottomTabBar.vue` | **新建**（4 Tab 导航） |
| `frontend/src/components/layout/MorePanel.vue` | **新建**（基于 BottomSheet） |
| `frontend/src/components/layout/Header.vue` | 移除全部汉堡菜单代码 |
| `frontend/src/views/ChatView.vue` | config-bar 隐藏 + 角色/模型徽章 + RoleModelSheet（基于 BottomSheet）+ 浮动按钮整合 |

---

## 七、不在本次范围内

- 桌面端导航和布局（不变）
- 其他页面内容级移动适配（现有响应式已够用）
- 安卓端额外适配（`env()` 在现代 Android Chrome 同样支持）
- 深色主题在新组件中的适配（全部使用 CSS 变量，自动跟随）
- InstallPrompt（已有 `InstallPrompt.vue` 并已挂载，无需改动）

---

## 八、测试清单

### 必测机型（iOS）

| 机型 | 重点 |
|------|------|
| iPhone 16 | 主目标，全功能验证 |
| iPhone 16 Pro | Dynamic Island，`env(safe-area-inset-top)` ≈ 51px |
| iPhone 16 Plus / Pro Max | 大屏 430px，Tab Bar 等分宽度 |
| iPhone SE 3 | 375px 小屏 + Home 键（无 safe-area-inset-bottom） |

### 兜底机型（Android）

- Pixel 8（412×915）
- 三星 Galaxy S24（360×800）

### 调试步骤

1. **Safari DevTools 模拟**：Mac + iPhone 模拟器 + Safari Web Inspector
2. **真机扫码**：前端热更新后扫码访问 `intelligent.eu.cc`
3. **键盘测试**：点击输入框，确认 input-area 不被软键盘遮挡
4. **安全区验证**：横屏/竖屏切换，确认 Tab Bar 底部贴合 Home Indicator
5. **Lighthouse PWA 审计**：Chrome DevTools → Lighthouse → PWA 类别目标 ≥ 90 分
