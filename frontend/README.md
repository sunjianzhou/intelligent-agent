# Frontend 模块

> Vue 3 + Vite SPA，开发端口 3000（Docker Nginx）/ 3001（Vite dev，若 3000 被占用自动递增）。

---

## 功能清单

### 聊天（ChatView）

| 功能 | 说明 |
|------|------|
| **流式对话** | WebSocket 实时接收 token，流式渲染到气泡；Shift+Enter 换行，Enter 发送 |
| **Markdown 渲染** | 支持代码块（14 种语言高亮）、表格、引用、有序/无序列表、标题、内联代码 |
| **思考计时器** | 推理等待超 3 秒后显示已等待秒数，让用户了解 CPU 推理进度 |
| **停止生成** | 流式输出中显示红色脉冲停止按钮，点击中止本轮生成 |
| **实时工具面板** | AI 调用工具时，实时显示每个工具名称、参数摘要及执行状态（旋转图标）|
| **工具结果卡** | 工具调用完成后显示汇总卡：工具名、成功/失败、可展开的返回值 |
| **消息搜索** | Ctrl+F 呼出搜索栏，关键词高亮、上/下一个导航，Esc 关闭 |
| **空状态引导** | 首屏显示 4 张示例提示词卡片（数学、时间、写作、提醒），点击自动填入输入框 |
| **Token 用量** | 输入区底部实时显示估算 token / 上下文上限（橙→红色梯度警告）|
| **超限 Banner** | token 用量 ≥90% 时顶部出现警告横幅，一键新开对话或忽略 |
| **响应时间** | 每条助手消息下方显示本次响应耗时（秒）|
| **点赞 / 踩** | 助手消息生成完毕后显示 👍/👎，评价结果持久化至 localStorage，同步到后端统计 |
| **对话导出** | 悬浮下载按钮，支持 Markdown 和 TXT 两种格式，文件名含时间戳 |
| **清空对话** | 同时清除前端消息和 Python 短期记忆（二次确认弹窗）|
| **新开对话** | 仅清前端显示，保留 AI 后端记忆，创建新的 IndexedDB 会话 |
| **定时通知** | 调度任务完成时以橙色通知气泡推送到聊天区，含"查看任务管理"跳转链接 |
| **项目关联** | 输入区底部显示当前激活项目名，点击跳转项目管理 |
| **无限制模式徽章** | 检测到 dolphin 系列模型时，空状态展示"🐬 无限制模式"标识 |
| **云端/本地模式** | 自动识别云端模型，底部提示推理等待时间参考值随之变化 |

---

### 角色编辑器（RoleEditorView）

路由：`/roles/editor`

| 功能 | 说明 |
|------|------|
| **角色卡片列表** | 展示全部角色，当前激活角色显示"当前"标签 |
| **新建/编辑角色** | 六标签表单：基本信息 / 核心身份 / 用户画像 / 场景知识 / 限制条件 / 提示预览 |
| **提示预览 Tab** | 实时将表单字段编译为 system prompt Markdown，`marked + DOMPurify` 渲染 |
| **切换角色** | 点击"使用"立即激活，POST `/api/roles/activate`，同步到 AI 后端 |
| **删除角色** | 单条删除（内置系统角色不可删）|
| **内容预览** | 卡片中截取描述前 120 字预览 |

---

### 记忆管理（MemoryView）

| 功能 | 说明 |
|------|------|
| **长期记忆列表** | 显示 ChromaDB 向量库中的持久化条目，含重要性评分 |
| **短期记忆列表** | 显示内存中最近对话条目，含 TTL |
| **摘要时间线** | 每 N 轮对话自动生成的阶段摘要，按时间排列 |
| **搜索** | 长期记忆使用语义搜索；短期记忆使用关键词匹配 |
| **知识提炼** | 点击"提炼知识"按钮，AI 从短期对话中提取知识写入长期记忆 |
| **单条删除** | 长期/短期记忆均可单条删除 |
| **清空全部** | 一键清空所有记忆（二次确认）|
| **导出** | 支持 Markdown、JSON、全量迁移包（ZIP）三种格式 |
| **导入** | 支持 TXT/JSON 批量导入；支持迁移包恢复全量数据 |
| **统计卡片** | 显示长期数量、短期数量、平均重要性 |

---

### 调度任务（TasksView）

| 功能 | 说明 |
|------|------|
| **任务列表** | 显示全部调度任务，支持按状态筛选（全部/待执行/运行中/已完成/失败）|
| **新建任务** | 对话框填写名称、描述、动作类型、调度类型（立即/延迟/周期/定时）及参数 |
| **编辑任务** | 修改名称、描述、prompt 等字段 |
| **删除任务** | 单条删除 |
| **启用/禁用** | 周期任务可随时暂停/恢复 |
| **状态倒计时** | 待执行任务实时显示距下次执行的倒计时 |
| **统计卡片** | 显示全部/待执行/已完成/失败数量 |
| **任务跳转** | 工具调用卡和通知气泡均含"查看任务管理"跳转链接 |

---

### 项目管理（ProjectView）

| 功能 | 说明 |
|------|------|
| **三栏布局** | 左：项目列表；中：规格文档；右：任务树 |
| **新建/删除项目** | 项目列表中快速创建和删除 |
| **激活项目** | 选中项目后与聊天关联，AI 自动携带项目上下文 |
| **规格文档编辑** | Markdown 编辑器 + 实时预览切换，内容同步至 Python 后端 |
| **AI 任务分解** | 点击"AI 分解"将规格文档拆解为多级任务树，写入后端 |
| **手动任务节点** | 在任务树中手动新增/编辑/删除任务节点 |
| **任务状态流转** | 每个任务节点可手动切换状态（待办→进行中→完成）|
| **递归展开** | 任务树支持多层嵌套，可折叠/展开 |

---

### Skill 管理（SkillView）

| 功能 | 说明 |
|------|------|
| **Skill 列表** | 按标签分类过滤，展示名称、描述、触发词、步骤 |
| **新建 Skill** | 填写名称、描述、整体策略、触发词、步骤（含强制工具/工具提示）|
| **编辑 Skill** | 修改全部字段 |
| **删除 Skill** | 单条删除 |
| **启用/禁用** | 切换 Skill 激活状态 |
| **从 MD 导入** | 粘贴 Markdown 格式的 Skill 定义，一键解析并导入 |
| **步骤预览** | 卡片中直接展示步骤列表、强制工具徽章、工具提示徽章 |

---

### 工具列表（ToolsView）

| 功能 | 说明 |
|------|------|
| **工具总览** | 按分类过滤，显示每个工具的名称、描述、状态（可用/禁用）|
| **API Key 配置** | 内嵌配置面板，支持天气/搜索/地图等工具的 API Key 配置，支持显示/隐藏密码 |

---

### 系统监控（SystemView）

| 功能 | 说明 |
|------|------|
| **三服务健康检测** | 实时检测前端/Java 后端/Python Agent 连通状态 |
| **LLM 状态** | 显示当前 Ollama 模型或云端模型名称及连接状态 |
| **CPU 监控** | 整体占用率折线图 + 逐核心柱状图 |
| **内存监控** | 已用/总量折线图 + 百分比 |
| **磁盘监控** | 已用/总量折线图 + 百分比 |
| **自动刷新** | 默认 10 秒自动刷新，顶部显示倒计时 |
| **运行时配置** | 滑块调整 `temperature`、`max_tokens` 等参数，立即生效 |

---

### 统计分析（StatsView）

| 功能 | 说明 |
|------|------|
| **概览卡片** | 总对话数、点赞数、点踩数、满意率、平均响应时间 |
| **每日对话柱状图** | 展示最近 N 天每日对话次数 |
| **响应时间趋势图** | SVG 折线图展示每次对话响应时间变化 |
| **评价工具使用分析** | 各工具调用次数及关联的评价分布 |
| **评价筛选** | 可按全部/点赞/点踩过滤记录 |

---

### 全局 / 布局

| 功能 | 说明 |
|------|------|
| **JWT 鉴权** | 登录后颁发 JWT，所有受保护路由均校验 token；过期自动跳转登录页 |
| **深色模式** | Header 右上角切换，持久化至 localStorage |
| **Config-bar** | 聊天输入框上方常驻条：左侧角色选择器 + 右侧模型切换下拉（原来在 Header，TODO-24 移入）|
| **侧边栏会话历史** | 左侧列出最近会话，点击恢复历史记录；无预览时显"新对话"；删除当前会话自动切到下一条 |
| **移动端响应** | 768px 以下折叠为汉堡菜单抽屉；含完整主导航 + 管理后台 + 聊天页历史会话快捷入口 |
| **ConfirmDialog** | 危险操作（删除/清空）使用自研纯 Vue 弹窗，不使用 `window.confirm`（在 PWA/WebView 下会被静默拦截）|
| **PWA 安装** | 支持浏览器"添加到主屏幕"，离线可访问静态资源 |
| **全局错误通知** | API 异常统一通过 Element Plus toast 提示，401 自动退出 |

---

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Vue 3 (Composition API) |
| 状态管理 | Pinia |
| 路由 | Vue Router 4（history mode）|
| UI 组件库 | Element Plus |
| 图标 | Font Awesome 6（本地 npm 包，不走 CDN）|
| Markdown 渲染 | marked + DOMPurify |
| 代码高亮 | highlight.js（按需注册 14 种语言）|
| HTTP | 原生 fetch（封装在 `services/api.js`）|
| 实时通信 | WebSocket（封装在 `stores/websocket.js`）|
| 本地存储 | IndexedDB v2（会话 + 项目，封装在 `services/localDB.js`）|
| PWA | vite-plugin-pwa（可安装）|
| 构建 | Vite 4 |

---

## 目录结构

```
frontend/src/
├── views/
│   ├── ChatView.vue            主聊天界面（思考计时、工具卡片、会话历史、config-bar）
│   ├── LoginView.vue           登录页
│   ├── RoleEditorView.vue      角色编辑器（六标签表单 + 提示词实时预览，路由 /roles/editor）
│   ├── ProjectView.vue         项目管理（三栏：列表 / 规格 / 任务树）
│   ├── MemoryView.vue          记忆管理（搜索、长期、短期、摘要、导入导出）
│   ├── SkillView.vue           Skill 管理
│   ├── TasksView.vue           调度任务管理
│   ├── SystemView.vue          系统监控 + 运行时配置（可折叠卡片、滑块调参）
│   ├── ToolsView.vue           工具列表 + API Key 配置
│   └── StatsView.vue           统计分析
├── components/
│   ├── layout/
│   │   ├── Header.vue          顶部（连接状态、深色模式、清空按钮、管理后台入口）
│   │   ├── Sidebar.vue         左侧导航（聊天/角色/记忆/项目 + 历史会话列表）
│   │   └── StatusBar.vue       状态栏
│   ├── ConfirmDialog.vue       自研危险操作确认弹窗（不用 window.confirm）
│   ├── InstallPrompt.vue       PWA 安装提示
│   └── project/
│       ├── SpecEditor.vue      规格文档编辑器（Markdown 实时预览）
│       ├── TaskTree.vue        任务树（AI 分解 + 手动 + 递归渲染）
│       └── TaskNode.vue        递归任务节点（展开/折叠/状态流转）
├── stores/
│   ├── websocket.js            WS 连接管理、消息处理、模型/角色切换 + openHistorySignal 跨组件总线
│   ├── auth.js                 登录状态、token 刷新
│   ├── localSession.js         会话持久化到 IndexedDB（历史侧边栏）
│   ├── project.js              项目 CRUD + localStorage 轻量缓存
│   ├── confirmDialog.js        全局确认弹窗状态（useConfirmDialogStore）
│   └── errorBus.js             全局错误通知总线
├── services/
│   ├── api.js                  REST 调用封装（自动附 JWT、401 跳登录、错误 toast）
│   └── localDB.js              IndexedDB v2（sessions store + projects store）
├── utils/
│   └── jwt.js                  JWT 解码工具（isTokenExpired）
└── router/
    └── index.js                路由配置（含导航守卫：未登录跳 /login）
```

---

## 状态管理

### useWebSocketStore（核心）

```
connect() → ws = new WebSocket(`ws://host/ws?token=...`)
    │
    onmessage → switch(type):
        thinking         → isThinking = true，启动思考计时器
        chat_token       → streaming message 追加 token
        tool_call_start  → activeToolSteps.push()
        tool_calls_done  → 构造工具卡片 message
        chat_done        → isThinking = false，记录响应时间
        task_update      → useProjectStore.markTaskDone(task_id)
        notification     → 聊天区推送通知气泡
        system_info      → 更新 systemInfo（模型、健康状态）
```

**跨组件信号总线**：
- `openHistorySignal`（ref）+ `triggerOpenHistory()`：Header 汉堡菜单 → ChatView 打开历史侧边栏，解决跨层级通信问题（无父子关系时不能用 emit）

**持久化**：
- `currentPersona` / `currentModel`：内存状态（通过 API 与服务端同步）
- 聊天历史：`localStorage`（最近 50 条，页面刷新恢复，与 IndexedDB 会话并存）

### useLocalSessionStore（会话历史）

```
startNewSession() → _persist() → saveSession(IndexedDB)
addMessage(msg)   → messages.push() → _persist()
loadSessions()    → sessions = listSessions(IndexedDB)
```

**关键修复**：`_persist()` 中 `messages` 必须用 `JSON.parse(JSON.stringify(messages.value))` 序列化，否则 Vue Proxy 对象导致 `DataCloneError`（IndexedDB structured clone 不支持 Proxy）。

### useProjectStore

项目数据存 `localStorage`（轻量，无需 IndexedDB）；规格文档和任务树同步至 Python `/api/project/*`。

---

## 关键 UI 功能

### 思考计时器（ChatView）

`isThinking` 为 true 时，3 秒后开始显示秒数计时（"16s"、"93s"...），让用户知道 CPU 推理进度。

### 流式 token 渲染

使用 Markdown 增量解析（marked），每个 token 到来时重新渲染，支持代码高亮。

### 工具调用卡片

`tool_call_start` 事件触发时，在思考气泡下方实时显示进行中的工具名 + 参数摘要（折叠/展开）。

### 移动端适配

768px 以下：
- 侧边栏收起为汉堡菜单（抽屉式），宽度 `min(240px, 85vw)` 适配小屏
- 汉堡菜单主导航：聊天 / 角色配置 / 记忆 / 项目 / 系统
- 汉堡菜单管理后台：任务 / 工具 / Skill / 系统 / 统计
- 聊天页额外显示「历史会话」快捷入口，点击通过 Pinia 信号触发 ChatView 打开历史面板
- 模型/角色选择器位于 ChatView config-bar，移动端同样可用

### 深色模式

`data-theme="dark"` 写入 `document.documentElement`，持久化至 `localStorage`。

---

## 路由说明

| 路径 | 视图 | 说明 |
|------|------|------|
| `/` | → `/chat` redirect | |
| `/login` | LoginView | 公开路由 |
| `/chat` | ChatView | 需登录 |
| `/roles/editor` | RoleEditorView | 需登录（角色编辑器）|
| `/memory` | MemoryView | 需登录 |
| `/project` | ProjectView | 需登录 |
| `/skills` | SkillView | 需登录 |
| `/admin/tools` | ToolsView | 需登录 |
| `/admin/tasks` | TasksView | 需登录 |
| `/admin/system` | SystemView | 需登录 |
| `/admin/stats` | StatsView | 需登录 |

所有非 `/login` 路由通过导航守卫统一鉴权，token 过期自动跳 `/login`。

---

## 开发配置

### Vite 代理（开发模式）

```js
// vite.config.js
proxy: {
  '/api': { target: process.env.VITE_BACKEND_URL || 'http://localhost:8080' },
  '/ws':  { target: process.env.VITE_WS_URL      || 'ws://localhost:8080', ws: true }
}
```

开发时所有请求经 Vite 代理转发到 Java（8080），Java 再转 Python。

### 环境变量

| 变量 | 说明 |
|------|------|
| `VITE_BACKEND_URL` | Java 后端地址（默认 `http://localhost:8080`）|
| `VITE_WS_URL` | WebSocket 地址（默认 `ws://localhost:8080`）|
| `VITE_USE_MOCK` | 设为 `true` 启用 mock 模式（离线开发）|

---

## 构建与部署

```bash
cd frontend

npm install
npm run dev      # 开发服务器（端口 3000，3000 被占自动 +1）
npm run build    # 生产构建 → dist/
npm run preview  # 预览 dist/
```

**Docker 热更新**：
- 修改前端代码后，本地 `npm run build` 生成新 dist，然后：
  ```bash
  docker cp frontend/dist ia-frontend:/usr/share/nginx/html_new
  docker exec ia-frontend sh -c "rm -rf /usr/share/nginx/html_old && mv /usr/share/nginx/html /usr/share/nginx/html_old && mv /usr/share/nginx/html_new /usr/share/nginx/html && nginx -s reload"
  ```
  无需重建 Docker 镜像（比 `docker compose build frontend` 快 5-10 分钟）。

---

## 已知问题与待优化

| 编号 | 问题 | 状态 |
|------|------|------|
| F-01 | 移动端汉堡菜单缺少"项目"和"角色"入口 | ✅ 已修复（2026-06-02）|
| F-02 | 角色名歧义（文件标题 vs 功能名）| ✅ 已修复（展示名字段，2026-06-02）|
| F-03 | clearMessages() 只清前端，不清 Python 短期记忆 | ✅ 已修复（2026-05-30）|
| F-04 | 删除/清空使用 window.confirm，PWA/WebView 下被静默拦截 | ✅ 已修复（ConfirmDialog 组件，2026-06-07）|
| F-05 | 会话历史标题更新依赖 `_persist()` 异步，sidebar 可能延迟刷新 | 低优先级 |
| F-06 | api.js 中 `switchModel` 动态 import 导致代码分割警告 | 低优先级 |
