# Frontend 视觉升级设计：深色科技感全站改版

日期：2026-06-19
范围：`frontend/`（Vue 3 + Vite 网页前端）。不涉及 `client/`（Python CLI 客户端，无 UI）。

## 背景与目标

用户反馈当前网页前端整体偏"丑"：配色平淡、缺乏科技感，布局上侧边栏导航分区不清晰、各页面卡片间距不统一、关键信息（状态/操作/页面标题）没有被视觉上着重强调。用户没有专业前端设计经验，希望基于本仓库自带的 `skills-src/`（21 个前端设计技能，来自 `@flitzrrr/frontend-design-skills`）做一次系统性的设计提升，重点参考 `color-theory`、`ui-design`、`ux-design`、`web-typography`、`navigation-design` 这几个领域技能的原则。

目标：
1. 视觉风格转向"深色科技感"为主题（保留浅色主题，配色同源切换）。
2. 全站统一升级：设计令牌 + 共享外壳（Header/Sidebar/StatusBar）+ 通用组件样式 + 18 个业务/管理页面逐批替换。
3. 解决三个具体痛点：侧边栏导航结构不清晰、页面内容块间距不统一、重要信息缺乏视觉重点。
4. 不引入新依赖、不改路由结构、不增删功能、不重写交互逻辑——纯视觉与布局层。

## 现状分析

- `frontend/src/styles/main.css` 已有一个很简单的 CSS 变量基础（`--color-primary` 等 12 个变量），但只覆盖颜色，没有间距/圆角/阴影/字号的令牌体系。
- 已经存在 `data-theme="light"/"dark"` 的暗色主题切换机制（`Header.vue` 中的 `toggleTheme`），10 个文件里有 `data-theme` 选择器，但各文件各写各的深色配色，没有统一令牌驱动，深浅主题之间的颜色关系不规整。
- `Sidebar.vue` 已经把导航分成「常用 / 配置 / 系统」三组（来自 `routes.config.js` 的 `NAV_ITEMS` / `CONFIG_ITEMS` / `SYSTEM_ITEMS`），但三组视觉权重相同，选中态仅靠背景轻微变亮区分，层次感弱。移动端抽屉里已经把 `CONFIG_ITEMS + SYSTEM_ITEMS` 合并显示为「管理后台」一个分组（见 `Header.vue` 的 `adminNavItems`），桌面端尚未采用这种合并。
- 各 view 文件（如 `SystemView.vue`）大量使用硬编码数值（`12px` 间距、`border-radius: 12px`、`#e8eaed` 边框色、`#f8f9fa` 背景等），同类元素在不同页面写出来的数值并不完全一致，这是"间距不统一"的根因。
- 已安装 `element-plus`（用于 `ElMessageBox` 等少量弹层组件），但项目主体 UI 是手写 HTML+CSS，并未采用 Element Plus 的组件体系或主题变量。

## 设计方案

### 1. 设计令牌（写入 `frontend/src/styles/main.css` 的 `:root` 与 `[data-theme="dark"]`）

**配色（深色主题为主，浅色同源）**

层级化背景（深色不用纯黑，靠"面板比背景亮一档"区分层次）：
- `--color-bg`：最底层背景，深色 `#0d1117` / 浅色沿用现有浅灰 `#f8f9fa`
- `--color-surface`：卡片/面板，深色 `#161b22` / 浅色 `#ffffff`
- `--color-surface-raised`：悬浮/选中态，深色 `#1f2733` / 浅色 `#f0f1ff`
- `--color-border`：深色 `rgba(255,255,255,0.08)` / 浅色沿用 `#e0e3e8`

强调色（限制在两个色相，避免花）：
- `--color-primary`：主强调色（按钮/激活态），深色场景下调亮为 `#7c8cf8`（现有 `#667eea` 在深色背景对比不足），浅色主题保留偏向现有色相
- `--color-accent`：第二强调色（成功/在线/激活），延用侧边栏已经在用的青绿 `#4fc3a1`，作为跨页面统一的"在线/激活"色

文字三级（解决"重点字和说明字一样灰"的问题）：
- `--color-text`：主文字，深色 `#e6e8eb` / 浅色 `#1f2430`
- `--color-text-secondary`：次要文字
- `--color-text-muted`：弱文字/占位

状态色：`--color-danger` `--color-warn` `--color-success` 保留现有色相，分别为深浅主题各调一版满足 WCAG AA 对比度（深色背景下浅色文字组合需要逐个核对，现有部分弱文字在深色背景对比度不够）。

**间距 / 圆角 / 阴影令牌**
- 间距：`--space-1` 4px、`--space-2` 8px、`--space-3` 12px、`--space-4` 16px、`--space-5` 24px、`--space-6` 32px
- 圆角：`--radius-sm` 6px（按钮/输入框）、`--radius-md` 10px（卡片）、`--radius-lg` 16px（弹层/大面板）
- 阴影：`--shadow-sm`（卡片默认，轻微）、`--shadow-glow`（强调色光晕，用于主按钮/激活态，深色主题下科技感的主要来源；浅色主题下减弱为普通阴影）

**字号**：补充 `--text-xs/sm/base/lg/xl` 字号 token，沿用现有中文字体栈不变。

### 2. 共享外壳改造（Header / Sidebar / StatusBar）

- `Sidebar.vue`：导航由三个平铺分组改为两层——「常用」（`NAV_ITEMS`，6 项）保持一级直显；`CONFIG_ITEMS` + `SYSTEM_ITEMS`（9 项）合并为一个可折叠的「管理后台」分组，默认收起。这与移动端抽屉已有的 `adminNavItems` 合并概念一致，桌面端跟进即可，不是新发明的信息架构。
- 选中态从"背景轻微变亮"改为"左侧强调色竖线 + 背景微亮"，提升可辨识度。
- `Header.vue`：顶部加一条极细的强调色描边/分隔，强化"控制台"感；连接状态点、主题切换按钮颜色对齐新令牌。
- `StatusBar.vue`：同步令牌替换，不改变其展示的信息内容。

### 3. 通用组件样式（新增到 `main.css`，供各页面复用）

新增一组工具类：`.btn-primary` `.btn-ghost` `.card` `.badge-success/warn/danger` `.input`，全部基于上述令牌拼装，颜色对比度按 WCAG AA 核对。各业务页面后续替换为引用这些 class，不再各自重写相近但不一致的样式。

### 4. 页面铺开顺序

1. 令牌 + 通用组件落地到 `main.css`（不动任何页面，可独立验证不报错）
2. 外壳：`Header.vue` / `Sidebar.vue` / `StatusBar.vue`
3. 业务页（按使用频率）：`ChatView` → `ProjectView`/`MemoryView`/`KnowledgeView`/`ImageView`/`RoleEditorView`
4. 后台管理页：`ToolsView` / `SkillView` / `MCPView` / `ModelView` / `TasksView` / `LogView` / `StatsView` / `SystemView`
5. 其余：`LoginView` / `learning/ReviewView` / `learning/SubmitView` / `ConfirmDialog` / `InstallPrompt`
6. 每批完成后用浏览器分别看一遍深色/浅色主题，确认无看不清文字或对比度过低的控件
7. Element Plus 弹层组件（`ElMessageBox` 等）只同步调整其自带的几个 CSS 变量（如 `--el-color-primary`、`--el-bg-color`）以贴合新主题色，不做组件级替换

## 不做的事（Non-goals）

- 不改路由结构、不增删页面/功能
- 不引入 Tailwind 或其他新的 CSS/UI 依赖
- 不把现有手写组件迁移成 Element Plus 组件
- 不重写交互逻辑（WebSocket、状态管理、表单校验等行为不变）

## 验证方式

- 每个阶段完成后用浏览器走一遍该范围内页面，深色/浅色主题各看一次
- 重点检查文字与背景的对比度（深色主题下浅灰文字容易出现对比不足）
- 现有的 `frontend/src/__tests__/jwt.test.js` 等单测不应受影响（本次改动只涉及样式/模板，不涉及逻辑）
