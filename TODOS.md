# TODOS

这份文件记录已明确但暂未纳入当前 PR 的工作项。每一条都包含足够上下文，任何人拿起来都能知道从哪里开始。

---

## 图片生成模块（本地优先）— 已建框架，待逐步完善

> **框架状态（2026-06-14 完成）：**
> - `services/image/` 新增 `ComfyUIProvider`（轮询模式骨架）、`DiffusersProvider`（进程内推理骨架）
> - `SDWebUIProvider` 已可用（轮询 /sdapi/v1/txt2img）
> - `ImageGenerationTool` 注册逻辑修复：本地 Provider 无需 API Key 即可激活
> - `settings.py` 新增 comfyui / diffusers 相关配置项，默认 provider 改为 `sd_webui`
> - 图片输出目录改为配置驱动（`IMAGE_GEN_OUTPUT_DIR`），默认 `agent/data/images`
>
> **快速体验**：
> 1. 安装并启动 AUTOMATIC1111 SD WebUI（`python launch.py --api --listen`）
> 2. 在 `.env` 中设置 `IMAGE_GEN_PROVIDER=sd_webui`（默认值，可不填）
> 3. 重启 Python 服务，对话中说"画一只猫"即可触发

---

### TODO-IMG-1：SD WebUI Provider 增强（P1 — 功能完善）

**目标**：让 SD WebUI 接入体验达到生产可用

**待完成：**
- [x] **模型列表 API**：`GET /api/image/models` 调用 `/sdapi/v1/sd-models` 返回可用检查点列表 ✅
- [x] **运行时换模型**：`POST /api/image/switch-model`，调用 `/sdapi/v1/options` 热切换模型 ✅
- [ ] **生成进度查询**：调用 `/sdapi/v1/progress` 轮询并通过 SSE 推送进度百分比到前端
- [ ] **img2img 支持**：扩展 `ImageRequest` 增加 `init_image_base64`，调用 `/sdapi/v1/img2img`
- [ ] **ControlNet 支持**：在 payload 中加入 `alwayson_scripts.controlnet`，需 WebUI 安装 ControlNet 扩展
- [ ] **采样器/调度器选择**：将 `sampler_name` 暴露为 `ImageRequest.extra` 参数

**涉及文件**：`agent/services/image/sd_webui_provider.py`

---

### TODO-IMG-2：ComfyUI Provider 完整实现（P1 — 替代方案）

**目标**：ComfyUI 比 SD WebUI 更灵活（原生支持 FLUX / SDXL / ControlNet 工作流）

**待完成：**
- [x] **WebSocket 实时进度**：`_ws_wait_and_download()` 监听 WS 事件，降级为 HTTP 轮询 `_poll_and_download()` ✅
- [x] **ComfyUI 模型列表**：调用 `/object_info/CheckpointLoaderSimple` 获取可用模型 ✅
- [ ] **工作流热重载**：支持通过 API 动态上传/切换工作流 JSON（PUT /api/image/comfyui-workflow）
- [ ] **内置多个工作流模板**：SD1.5_txt2img、SDXL_txt2img、FLUX_txt2img，按模型类型自动选择
- [ ] **LoRA/Embedding 注入**：在工作流 LoRALoader 节点动态注入用户指定的 LoRA 文件

**涉及文件**：`agent/services/image/comfyui_provider.py`

---

### TODO-IMG-3：diffusers 进程内推理优化（P2 — 离线部署）

**目标**：无需任何外部服务，Python 进程内直接推理，适合完全离线环境

**待完成：**
- [x] **float16 + attention slicing**：自动检测 cuda/mps 用 float16，`enable_attention_slicing()` 始终启用 ✅
- [x] **SDXL 支持**：`_is_sdxl()` 检测关键字，自动切换 `StableDiffusionXLPipeline` ✅
- [x] **xformers 优化**：CUDA 设备尝试 `enable_xformers_memory_efficient_attention()` ✅
- [ ] **模型热切换**：支持在不重启服务的情况下切换 `model_id`（卸载旧 pipeline → 加载新 pipeline）
- [ ] **LoRA 动态加载**：`pipe.load_lora_weights(lora_path)` 支持用户自定义风格
- [ ] **生成进度回调**：通过 `callback` 参数实时推送步数进度
- [ ] **量化支持（bitsandbytes）**：低显存（<6GB）设备开启 8-bit / 4-bit 量化

**安装命令**：
```bash
pip install diffusers transformers accelerate torch
# GPU（CUDA）：pip install torch --index-url https://download.pytorch.org/whl/cu121
```

**涉及文件**：`agent/services/image/diffusers_provider.py`

---

### ~~TODO-IMG-4：前端图片生成 UI~~ ✅ 已完成（2026-06-14）

**结果**：`frontend/src/views/ImageView.vue` 全新独立页面，包含：
- 左侧参数面板：prompt/negative_prompt、风格预设按钮、尺寸选择、步数/CFG 滑块、Provider 状态徽章
- 右侧结果区：当前生成结果卡片、Gallery 历史网格（hover 显示下载/删除按钮）、图片预览弹窗
- 导航：已加入侧边栏 NAV_ITEMS（`/image`）、路由 `index.js`、PAGE_CONFIGS 标题

---

### ~~TODO-IMG-5：图片生成 API 端点~~ ✅ 已完成（2026-06-14）

**结果**：
- `agent/api/image_router.py`：provider-status / models / switch-model / generate / list images / delete + 5GB 自动清理
- `backend/.../ImageProxyController.java`：JSON 端点 + 二进制图片流代理（无 JWT 直传）
- `frontend/src/services/api.js`：`getImageProviderStatus / listImageModels / switchImageModel / generateImage / listGeneratedImages / deleteGeneratedImage`

---

### TODO-IMG-6：图片生成安全与限流（P3 — 生产加固）

**待完成：**
- [x] **并发限制**：`image_router.py` 复用 `_state._inference_sem` 信号量，超限返回 503 ✅
- [x] **输出目录大小限制**：`_maybe_cleanup_old_images()` 超过 5GB 时自动清理最旧文件 ✅
- [x] **文件名随机化**：`gen_{uuid12}.png` 已随机，图片流代理路径白名单 JWT 豁免 ✅
- [ ] **NSFW 过滤**：diffusers `safety_checker` 当前已关闭（本地环境），生产环境按需开启

---

## ~~TODO-1: HTTPS/TLS~~ ✅ 已完成（配置模板已提供）

**结果**: `nginx/nginx-https.conf` 完整 Nginx TLS 反向代理配置已存在；`docker-compose.yml` 支持 `--profile https` 启用；支持自签名证书（开发）和 Let's Encrypt（生产）两种模式。Java/Python 后端无需改动，Nginx 终结 TLS。

---

## TODO-12: 性能优化剩余项（⏸️ 待触发条件）

以下项目已确认可做，但当前规模不值得操作，待对应触发条件出现再处理：

4. **L1 响应缓存锁优化** — `agent/core/agent.py:108-109`；需 `inference_concurrency` 调高后出现热点再做。
5. **Java 侧 token 批量转发** — 需先有延迟抖动证据再做。
8. **Scheduler 轮询改事件驱动** — 任务量小时无所谓；调度器历史上多次出现并发/绑定 bug，改动有风险，待任务量真正增长再做。

> ChromaDB 迁移至 Docker 具名卷已于 2026-06-09 完成，具名卷为 `intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm`。
> 中间无前缀卷 `agent_chroma_data`/`agent_chroma_data_longterm` 可按需 `docker volume rm` 清理。

---

## ~~TODO-20: Docker 中间无前缀卷清理~~ ✅ 已完成（2026-06-14）

`agent_chroma_data` 和 `agent_chroma_data_longterm` 两个中间卷已删除。正式卷 `intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm` 保留正常。

---

## TODO-21: Feishu/微信 Bot 接入（MEDIUM，待公网环境）

**什么**: 实现飞书 / 微信 Bot Webhook 接入点，让用户在已有工作流中直接与 AI 对话，无需打开 Web UI。

**为什么**: 将产品从"个人工具"变为"可分享的 AI 助手"，使用频率可能显著提升。

**如何实现**: 在 Python FastAPI 新增 `POST /api/webhook/feishu`，验证飞书 AppSecret 签名，将消息正文转发给现有 `POST /api/chat`，返回结果。飞书/微信两者独立实现，代码可复用同一适配层。

**当前状态**: 需要公网 IP 和 Bot 平台审核，本地开发环境无法验证。待部署到可公网访问的服务器后再做。

**先决条件**: TODO-1（HTTPS/TLS）完成后方可上线（Bot 平台要求 HTTPS 回调）。

**代价**: Human ~1天 / CC ~45min

---

## ~~TODO-22: agent/core/agent.py God Class 拆构~~ ✅ 已完成（2026-06-11）

**结果**: 2318 行 God Class 已拆分为四个文件（commit `528b787`）：
- `core/_context_vars.py`    — 共享 ContextVar（避免循环导入）
- `core/memory_writer.py`    — MemoryWriterMixin（预热/MCP/蒸馏/清理，~310行）
- `core/tool_dispatcher.py`  — ToolDispatcherMixin（工具注册/意图/LLM调用，~1130行）
- `core/conversation_flow.py` — ConversationFlowMixin（消息构建/chat/stream，~460行）
- `core/agent.py`            — 薄门面 IntelligentAgent（__init__/provider/token/cache，~320行）

152 passed，1 个预存 flaky（cron 时序竞争），无新增失败。

---

## ~~TODO-23: 前端 P1 视觉美化~~ ✅ 已完成（2026-06-11）

基于浏览器真实访问的视觉评审，commit `5930a86`：

- **ChatView.vue** — 4张示例卡片蓝/橙/绿/紫配色主题（nth-child）；无限制 badge 独立换行
- **MemoryView.vue** — 刷新/导入/恢复三个纯图标按钮补文字标签，icon+text 间距 5px
- **TasksView.vue** — task-desc 截断至 2 行（`-webkit-line-clamp: 2`），与 task-prompt 一致
- **ProjectView.vue** — spec-panel 加右边框，三列视觉分隔完整

---

## ~~TODO-24: Header 信息密度优化~~ ✅ 已完成（2026-06-12）

模型切换器从 Header 移至 ChatView 输入框上方 config-bar；同行新增角色选择器（调用 `/api/roles/activate`）；Header 只保留连接状态/主题/清空/管理齿轮。

---

## ~~TODO-25: MemoryView 清空全部按钮视觉分离~~ ✅ 已完成（2026-06-12）

「清空全部」按钮从 `stats-row` 移出，独立为 `danger-zone` div，用 `border-top: 1px solid #fecaca` 分隔。

---

## ~~TODO-26: SystemView 信息展示优化~~ ✅ 已完成（2026-06-12）

「可用模型」和「内存优化建议」两张冗长卡片改为可折叠（点标题展开/收起），默认建议折叠、模型展开。

---

## ~~TODO-27: 历史对话侧边栏 UX 完善~~ ✅ 已完成（2026-06-12）

1. 无 preview 时显示「新对话」占位；2. 删除当前会话后自动加载下一条；3. 移动端面板宽度改 `min(240px, 85vw)`。

---

## ~~TODO-28: 角色编辑器 Markdown 预览~~ ✅ 已完成（2026-06-12）

RoleEditorView 新增第六个 Tab「提示预览」，用 `marked` + `DOMPurify` 实时渲染角色表单为 Markdown 系统提示预览。

---

## ~~TODO-29: 移动端汉堡菜单完整性~~ ✅ 已完成（2026-06-12）

Header.vue navItems 补「系统」(`/admin/system`)；聊天页抽屉新增「历史会话」项，点击触发 `store.triggerOpenHistory()`，ChatView watch 信号自动打开面板。

---

## ~~TODO-30: RoleEditorView El Plus 组件注册修复~~ ✅ 已完成（2026-06-12）

**问题**: 角色编辑器页面所有 `<el-*>` 标签渲染为未知自定义 HTML 元素，表单完全不可用。

**根因**: 项目无 `unplugin-vue-components`，`<script setup>` 中未显式 import El Plus 组件。

**修复**: `RoleEditorView.vue` 显式导入 21 个 El Plus 组件；`main.js` 补 `import 'element-plus/dist/index.css'`；补 `:deep()` 主题覆盖（品牌色 `#667eea`）。

---

## ~~TODO-31: MemoryView 工具栏两行布局~~ ✅ 已完成（2026-06-12）

工具栏 9 个控件挤一行改为两行：第一行搜索框 + 类型 Tab；第二行左对齐操作按钮（刷新/导入/提炼/导出/恢复）+ 右对齐「清空全部」危险按钮。

---

## ~~TODO-32: TasksView 操作按钮悬停动效~~ ✅ 已完成（2026-06-12）

任务卡片操作按钮从右侧竖排常驻改为悬停时底部浮出的横排按钮组（`opacity` + `translateY` 动画），减少视觉干扰，点击目标更大。

---

## ~~TODO-33: 统计页满意率语义颜色 + 柱状图可见性~~ ✅ 已完成（2026-06-12）

满意率数值改为动态颜色（≥80% 绿色 / ≥50% 橙色 / 其他红色），修复 CSS 优先级使其生效；柱状图 `min-height` 从 `4px` 提升至 `14px`，避免低值条几乎不可见。

---

## ~~TODO-33b: 角色编辑器按钮颜色语义化~~ ✅ 已完成（2026-06-12）

工具栏三按钮颜色混乱（绿色激活/浅粉删除）改为：「保存」实心紫 / 「激活角色」紫色描边（primary plain）/ 「删除」实心红（danger）；补 `:deep()` CSS 覆盖确保一致。

---

## ~~TODO-34: 聊天气泡悬停操作按钮~~ ✅ 已完成（2026-06-12）

点赞/踩从 meta 行常驻移至 `.bubble-actions` 悬停时淡出显示；同时新增「复制」按钮（`navigator.clipboard`），ElMessage 确认提示。

---

## ~~TODO-35: Token 计数器颜色分级~~ ✅ 已确认已完成

`tokenColor` computed 已实现三档（<70% 灰 / ≥70% 橙 / ≥90% 红）+ 超限 blink 动画，无需再做。

---

## ~~TODO-36: 任务过滤 Tab 窄屏溢出~~ ✅ 已完成（2026-06-12）

`.filter-tabs` 加 `overflow-x: auto; scrollbar-width: none`；`.filter-btn` 加 `white-space: nowrap; flex-shrink: 0`，窄屏横向滚动不折行。

---

## ~~TODO-37: 系统页 GPU 空状态~~ ✅ 已完成（2026-06-12）

无 GPU 时 GPU 卡片底部显示「未检测到独立 GPU」灰色占位文字（`.gpu-empty`）。

---

## ~~TODO-38: 项目页底部提示栏可读性~~ ✅ 已完成（2026-06-12）

`SpecEditor.vue` `.spec-hint`：背景 `#eff6ff`、边框 `#bfdbfe`、文字 `#1e40af`、字号 0.8rem，对比度和可读性明显提升。

---

## ~~TODO-39: 中文字体栈 + 气泡行距~~ ✅ 已完成（2026-06-13）

`frontend/src/styles/main.css` body 添加 PingFang SC / Microsoft YaHei 字体栈；ChatView `.bubble.assistant` line-height 1.6 → 1.85。

---

## ~~TODO-40: 连接状态指示器降噪~~ ✅ 已完成（2026-06-13）

websocket store 新增 `wasEverConnected`；Header 首次断开灰色无脉冲、意外断开红色+脉冲。

---

## ~~TODO-41: CSS 设计 Token~~ ✅ 已完成（2026-06-13）

`frontend/src/styles/main.css` 添加 `:root` 变量（12个 token），供渐进迁移硬编码色值。

---

## ~~TODO-42: 概览卡片顶部 Accent 色条~~ ✅ 已完成（2026-06-13）

TasksView / MemoryView / StatsView 各卡片顶部加 3px 语义色条（紫/绿/红/橙），三页统一实现。

---

## ~~TODO-43: 输入框聚焦状态增强~~ ✅ 已完成（2026-06-13）

`.input-wrap:focus-within` 新增 `box-shadow: 0 0 0 3px rgba(102,126,234,0.18)` 光晕。

---

## ~~TODO-44: 侧边栏历史区分隔线~~ ✅ 已完成（Sidebar.vue 已有）

`.history-section` 已有 `border-top: 1px solid rgba(255,255,255,0.08)`，无需额外修改。

---

## ~~TODO-45: 统计页响应时间颜色分级~~ ✅ 已完成（2026-06-13）

StatsView 新增 `responseTimeColor` computed：<10s 绿 / <60s 橙 / ≥60s 红，应用于平均响应时间卡片。

---

## ~~TODO-46: 「清空」按钮移出 Header 导航区~~ ✅ 已完成（2026-06-13）

Header.vue 「清空」按钮已移除（ChatView 已有悬浮清空按钮），Header 只保留齿轮/连接状态/深色三个系统级控件。

---

## ~~TODO-47: 角色编辑器表单引导文案~~ ✅ 已完成（2026-06-13）

角色名片 Tab 顶部加 `el-alert` 引导卡，说明名称/签名/标签三字段用途及示例。

---

## ~~TODO-48: 系统页服务检测超时降级~~ ✅ 已完成（2026-06-13）

`getJavaHealth` / `getPythonHealth` 套 8s `withTimeout` + `Promise.allSettled`；超时后显示「检测超时」橙色 badge + 重试按钮。

---

## ~~TODO-49: [BUG] Header 右上角缺少模型切换入口~~ ✅ 已完成（2026-06-13）

Header.vue `header-right` 区域新增 `.model-entry`：显示当前模型名（云端时高亮蓝色），点击展开下拉切换模型，复用 `store.availableModels` / `store.switchModel()`；移动端隐藏（`display:none`）。

---


---

## ~~TODO-50: [BUG] SystemView 模型列表重复 + "正在使用"显示错误~~ ✅ 已完成（2026-06-13）

① `model_router.py` `GET /api/models` 用 `dict.fromkeys()` 保序去重；② SystemView `currentModel` 改从 `modelData2.current_model`（per-user 正确值）读取，不再依赖 `sysd.agent_model`（全局默认）；③ 前端 `[...new Set(...)]` 双重兜底。

---


---

## ~~TODO-51: [BUG] 定时任务消息发到聊天窗口后未写入短期记忆~~ ✅ 已完成（2026-06-13）

`log_action` / `llm_generate_action` 各在 `_push_notification()` 后追加 `agent.memory.store(category="task")`，写入短期记忆。

---


---

## ~~TODO-52: [FEATURE] 云端大模型切换入口 + API KEY 管理与绑定~~ ✅ 已完成（2026-06-13，合并至 TODO-54）

ModelView.vue 已集成：云端服务商 CRUD（添加/编辑/删除/激活）+ API KEY 输入框 + 「切换回本地」按钮。原 SystemView 底部 cloud 配置卡片保留不变，两处功能互补。

---


---

## ~~TODO-53: [LAYOUT] 合并管理后台到左侧导航，重构导航分区~~ ✅ 已完成（2026-06-13）

`routes.config.js` 新增 `CONFIG_ITEMS`（工具/Skill）+ `SYSTEM_ITEMS`（任务/统计/系统），`ADMIN_ITEMS` 合并两者保留兼容；`Sidebar.vue` 重构为三分区（常用/配置/系统）带分区标签；`Header.vue` 移除齿轮下拉（`admin-entry` 及相关 CSS 全部删除），侧边栏 width 压缩为 220px。

---


---

## ~~TODO-54: [NEW PAGE] 独立模型管理页面~~ ✅ 已完成（2026-06-13）

新建 `frontend/src/views/ModelView.vue`：三区布局（当前激活模型渐变卡 / 云端服务商卡片网格含添加/编辑/删除/激活 / 本地模型卡片含显存标识/激活）；路由 `/admin/models` 已注册；`SYSTEM_ITEMS` 首项追加「模型管理」；cloud CRUD 弹窗内嵌（服务商下拉自动填写 Base URL）。

---

## TODO-55: [REFACTOR] SystemView 清理重复的"可用模型"和"云端服务商"面板✅ 已完成

**什么**: SystemView 底部的「可用模型」折叠卡 + 「云端服务商配置」卡（含全套 CRUD 弹窗）与 ModelView 功能完全重复。

**为什么**: 维护两份相同功能的代码；用户进入系统信息页看到过时的 CRUD 面板造成困惑。

**如何实现**:
- 删除 template 中的 `.model-card` 和 `.cloud-providers-card` 两个 detail-card div
- 删除相关 dialog 模板（`showCpDialog` 对话框）
- 删除 JS 中云端服务商所有 ref/computed/函数：`cloudProviders, cpPresets, showCpDialog, cpEditId, cpSaving, cpForm, CP_MODEL_SUGGESTIONS, cpModelSuggestions, loadCloudProvs, loadCpPresets, openCpAdd, openCpEdit, onCpProviderChange, saveCpForm, activateCp, deactivateCp, deleteCp`
- 删除 `models` ref（仅用于已删除的列表）；保留 `currentModel`
- 删除 import 中不再使用的 cloud API 函数（7个）
- 在原位置加跳转提示，指向 `/admin/models`
- 删除对应 CSS 块

**入口文件**: `frontend/src/views/SystemView.vue`

---

## TODO-56: [REFACTOR] SystemView 可配置参数面板移至 MCPView ✅ 已完成

**什么**: SystemView 中的「资源配置」右列（可配置参数：并发数/队列/缓存/记忆）与 MCPView（工具配置中心）位置不符，且系统信息页应只展示状态，不做配置。

**为什么**: 页面职责混淆；用户在"系统信息"页找不到应在"配置"页的设置项。

**如何实现**:
- MCPView 新增第三个 card 「系统资源配置」，包含所有可编辑参数 + 保存按钮
- SystemView 「资源配置」卡只保留左侧"实时用量"部分，去掉右侧"可配置参数"列；底部加「→ 前往 MCP配置页调整参数」跳转链接
- MCPView 引入 `getRuntimeConfig, updateRuntimeConfig` API，复制状态变量和保存逻辑

**入口文件**: `frontend/src/views/SystemView.vue`, `frontend/src/views/MCPView.vue`

---

## TODO-57: [NEW PAGE] 操作日志管理页 ✅ 已完成

**什么**: 新增一个操作日志页面，展示用户操作与 AI 操作的时间线，按类型颜色区分。

**为什么**: 方便排查问题（AI调了哪些工具、用户发了什么消息、任务何时执行）；增强系统透明度。

**如何实现**:
- 新建 `frontend/src/views/LogView.vue`
- 数据源：拉取 `/api/conversations`（用户↔AI 对话）+ `/api/tasks`（任务执行）+ websocket store 响应时间
- 类型颜色：用户消息（蓝）/ AI 回复（绿）/ 工具调用（紫）/ 任务执行（橙）/ 错误（红）
- 支持按类型过滤 + 时间范围筛选
- 路由 `/admin/logs`，加入 SYSTEM_ITEMS 导航

**入口文件**: `frontend/src/views/LogView.vue`, `frontend/src/router/index.js`, `frontend/src/config/routes.config.js`

---

