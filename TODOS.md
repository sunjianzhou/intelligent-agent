# TODOS

这份文件记录已明确但暂未纳入当前 PR 的工作项。每一条都包含足够上下文，任何人拿起来都能知道从哪里开始。

---

## 图片生成模块（本地优先）— 框架 + Docker 集成已完成（2026-06-20）

> **框架状态（2026-06-14 更新）：**
> - `ComfyUIProvider` 已全量实现：txt2img / img2img / 采样器映射 / WS 进度 / HTTP 轮询降级 / 底图上传
> - `SDWebUIProvider` 已可用：txt2img / img2img / 进度 / 采样器 / 模型切换
> - `DiffusersProvider` 骨架，进程内推理（可选，无需外部服务）
> - **默认 provider 已切换为 `comfyui`（端口 8188）**；SD WebUI 用 7860
> - `ImageGenerationTool` 注册逻辑修复：本地 Provider 无需 API Key 即可激活
> - 图片输出目录改为配置驱动（`IMAGE_GEN_OUTPUT_DIR`），默认 `agent/data/images`
>
> **✅ Docker 集成已完成（2026-06-20）**：ComfyUI 已容器化接入 docker-compose 整体启动流程，端到端验证通过（真实 txt2img 出图成功）：
> 1. `docker compose --profile local up -d --build` 自动构建并启动 `comfyui` 容器（复用宿主机 `D:/software/ComfyUI` 的 models/checkpoints，首次启动安装依赖较慢，之后跳过）
> 2. `.env.docker` 默认 `IMAGE_GEN_PROVIDER=comfyui` + `IMAGE_GEN_BASE_URL=http://comfyui:8188`（容器间用 service 名互通，无需 `host.docker.internal`）
> 3. 对话中说"画一只猫"即可触发；详见 `comfyui/Dockerfile` + `docker-compose.yml` 的 `comfyui` service（commit `41bb665`）
>
> **本机非 Docker 直跑 ComfyUI（旧方案，仍兼容）**：`python main.py --listen 0.0.0.0 --port 8188`，`.env.docker` 改 `IMAGE_GEN_BASE_URL=http://host.docker.internal:8188`
>
> **切换回 SD WebUI**：`.env.docker` 设置 `IMAGE_GEN_PROVIDER=sd_webui IMAGE_GEN_BASE_URL=http://host.docker.internal:7860`

---

### ~~TODO-IMG-1：SD WebUI Provider 增强~~ ✅ 已完成（2026-06-14）

**结果**：img2img / 进度轮询 / 采样器选择 / 模型切换均已实现。

---

### ~~TODO-IMG-1（归档）：SD WebUI Provider 增强（P1 — 功能完善）~~ ✅ 全部完成（2026-07-03 核实）

**结果**：
- [x] **模型列表 API**：`GET /api/image/models` 调用 `/sdapi/v1/sd-models` 返回可用检查点列表 ✅
- [x] **运行时换模型**：`POST /api/image/switch-model`，调用 `/sdapi/v1/options` 热切换模型 ✅
- [x] **生成进度查询**：`get_progress()` 调用 `/sdapi/v1/progress` 轮询并返回 progress/eta/state/step ✅
- [x] **img2img 支持**：`generate()` 中 `req.init_image_base64` 存在时走 `/sdapi/v1/img2img`，含 denoising_strength ✅
- [x] **ControlNet 支持**：在 payload 中加入 `alwayson_scripts.controlnet`，需 WebUI 安装 ControlNet 扩展 ✅
- [x] **采样器/调度器选择**：`sampler_name` 已从 `req.sampler_name` 读取，透传至 API payload ✅

**涉及文件**：`agent/services/image/sd_webui_provider.py`

---

### ~~TODO-IMG-2：ComfyUI Provider 完整实现~~ ✅ 已完成（2026-06-14）

**结果**：
- txt2img / img2img 均已实现（img2img 先上传底图至 `/upload/image`，再用 LoadImage 节点）
- 采样器名称自动映射（SD WebUI 风格 → ComfyUI 原生格式）
- WebSocket 实时进度追踪（模块级 `_progress_state`，降级 HTTP 轮询时按耗时估算）
- 内置 txt2img / img2img 两套默认工作流；自定义工作流由 `IMAGE_GEN_COMFYUI_WORKFLOW` 指定
- 前端采样器列表根据 provider 自动切换（SD/ComfyUI 各自选项）

**遗留（P3）**：
- 工作流热重载 API（PUT /api/image/comfyui-workflow）
- LoRA/Embedding 节点注入
- 多模型类型自动匹配（SDXL/FLUX 用不同工作流模板）

**涉及文件**：`agent/services/image/comfyui_provider.py`

---

### ~~TODO-IMG-3：diffusers 进程内推理优化~~ ✅ 已完成（2026-06-15）

**结果**：
- 模块级 `_pipeline_cache`（线程安全）+ `switch_model()` 热切换（清除缓存 + CUDA empty_cache）
- `callback_on_step_end` 实时更新 `_progress_state`，`get_progress()` 跨请求读取
- img2img 支持（`StableDiffusionImg2ImgPipeline`，底图 base64 解码 + resize，失败时降级 txt2img）
- `GET /api/image/progress` 已加 diffusers 分支；前端 ImageView 进度轮询扩展至三个 provider

**遗留（P3）**：
- LoRA 动态加载（`pipe.load_lora_weights(lora_path)`）
- bitsandbytes 量化（Windows 兼容性问题，暂缓）

**涉及文件**：`agent/services/image/diffusers_provider.py`, `agent/api/image_router.py`

> **2026-08-08 归档说明**：图片生成模块随 Python Agent 退役（commit `354bf33`）。
> Java 侧保留 `integration/comfyui/ComfyUiClient`（提交工作流 + 轮询进度）。
> 原 P3 遗留（工作流热重载 API / LoRA 注入 / 多模型自动匹配 / diffusers LoRA /
> bitsandbytes 量化）在 Java 侧需要新的图片生成实现，标注为"需求驱动再做"，
> 不再作为迁移队列待办。

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

### ~~TODO-IMG-6：图片生成安全与限流（P3 — 生产加固）~~ ✅ 已完成（2026-07-03）

**结果**：
- 新增 `image_gen_diffusers_enable_safety_checker` 配置项（默认 `False`），生产通过 `IMAGE_GEN_DIFFUSERS_ENABLE_SAFETY_CHECKER=true` 开启
- `_load_safety_checker()` 懒加载 CompVis/stable-diffusion-safety-checker（~600MB）
- `_build_base_pipeline()` + `_generate_img2img()` 条件性注入 safety_checker / feature_extractor
- `_check_nsfw()` 统一检查 `nsfw_content_detected`，三个路径（txt2img/img2img/降级txt2img）均覆盖
- NSFW 被检测到时 safety checker 自动涂黑图片 + logger.warning

**涉及文件**：`agent/services/image/diffusers_provider.py`, `agent/config/settings.py`

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

> **2026-08-08 更新（Java 单后端迁移后）**：
> - 项 4（L1 响应缓存锁）：Python `l1_cache.py` 已随退役删除；Java 侧 `SemanticResponseCache`
>   使用 ConcurrentHashMap，无锁热点问题 —— 视为已解决。
> - 项 5（Java 侧 token 批量转发）：原指网关转发 Python SSE 事件；Java 单后端不再转发，
>   事件本地直发 —— 视为已解决。
> - 项 8（Scheduler 轮询改事件驱动）：Java `TaskSchedulerService` 为 1 秒 tick；
>   计划风险声明（调度器历史并发/绑定 bug）仍适用，保持"任务量增长后再评估"。

---

## ~~TODO-85: 飞书个人日历/任务 OAuth 授权~~ ✅ 已完成（2026-06-27）

**结果**：
- `agent/services/feishu_oauth.py` — OAuth Token Manager（Fernet 加密 / threading.Lock 刷新 / state CSRF 防护）
- `agent/api/feishu_oauth_router.py` — 3 个端点（authorize / callback / status）
- `agent/tools/builtin_tools/feishu_calendar_create.py` — 创建日历事件（user_access_token）
- `agent/tools/builtin_tools/feishu_task_write.py` — 创建/完成任务（user_access_token）
- `feishu_calendar.py` / `feishu_task.py` — 升级为 user_access_token 优先，tenant fallback
- `backend/.../feishu/FeishuOAuthController.java` — callback 透传无 JWT，authorize/status 有 JWT
- Token 多用户 JSON（含 refresh_expires_at 30 天监控）+ Fernet 加密存储

---

## ~~TODO-84: [SETUP] 从零接入飞书自建应用（用户侧手动操作）~~ ✅ 已完成（2026-06-26）

**背景**：代码侧（Java WS 客户端、事件解析、卡片发送、渠道感知 system prompt、群聊静默、心跳主动联系、日历/待办只读工具）早在 TODO-21/79~82 全部落地，且 `feishu.enabled=false` 时 `FeishuWebSocketClient.isAutoStartup()` 直接跳过，不影响现有 PWA/CLI 使用。目前唯一缺的是真实的飞书自建应用凭证——这一步只能用户自己在浏览器里完成，AI 无法代为操作。

**已完成：**
- [x] 在 [飞书开放平台](https://open.feishu.cn) 创建自建应用，开启权限 `im:message:send_as_bot` + `im:message`
- [x] 「事件订阅」开启长连接接收，订阅 `im.message.receive_v1`（无需公网 IP）
- [x] 开通「读取用户发给机器人的单聊消息」权限（此权限是 P2P 事件的关键，最初缺失导致事件日志为空，详见 `docs/feishu-integration.md` 排查节）
- [x] 记录凭证，`FEISHU_BOT_OPEN_ID=ou_8788d2ac4f9c24f15bc74ea1859bf9c5` 已填入 `.env.docker`
- [x] 飞书 App 里给机器人发消息，确认全链路通（事件触达 WS → Java → Python agent → dolphin 推理 → 回复卡片）
- [x] 创建心跳巡检定时任务（用户 open_id: `ou_1d2e0c80f6feffa546a1b28664bb39c2`，见 `docs/feishu-integration.md`）✅ 已完成（2026-06-28）

**涉及文件**：`docs/feishu-integration.md`（已补充排查节 + 已知账号信息）、`.env.docker`（已填入 FEISHU_BOT_OPEN_ID）

---

## ~~TODO-20: Docker 中间无前缀卷清理~~ ✅ 已完成（2026-06-14）

`agent_chroma_data` 和 `agent_chroma_data_longterm` 两个中间卷已删除。正式卷 `intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm` 保留正常。

---

## ~~TODO-21: Feishu Bot 接入~~ ✅ 已完成（2026-06-16）

**结果**：飞书长连接（WebSocket）通道全量落地，无需公网 IP / HTTPS 回调即可使用：
- `agent/im/feishu_client.py` — Python 侧 `FeishuIMTool`，7 类消息类型
- `backend/.../web/feishu/` — Java 侧 `FeishuWebSocketClient`（SmartLifecycle + 重连状态机）、`FeishuEventController`、`FeishuCardBuilder`、`FeishuCrypto`、`FeishuMessageSender`
- `FeishuIntegrationTest` 4 个端到端场景全通过；docker-compose 飞书环境变量 + `docs/feishu-integration.md` 接入文档

**涉及 commit**：`58083eb` `cadbef4` `70f3866` `b618afc` `fab51d2`

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

## ~~TODO-55: [REFACTOR] SystemView 清理重复的"可用模型"和"云端服务商"面板~~ ✅ 已完成

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

## ~~TODO-56: [REFACTOR] SystemView 可配置参数面板移至 MCPView~~ ✅ 已完成

**什么**: SystemView 中的「资源配置」右列（可配置参数：并发数/队列/缓存/记忆）与 MCPView（工具配置中心）位置不符，且系统信息页应只展示状态，不做配置。

**为什么**: 页面职责混淆；用户在"系统信息"页找不到应在"配置"页的设置项。

**如何实现**:
- MCPView 新增第三个 card 「系统资源配置」，包含所有可编辑参数 + 保存按钮
- SystemView 「资源配置」卡只保留左侧"实时用量"部分，去掉右侧"可配置参数"列；底部加「→ 前往 MCP配置页调整参数」跳转链接
- MCPView 引入 `getRuntimeConfig, updateRuntimeConfig` API，复制状态变量和保存逻辑

**入口文件**: `frontend/src/views/SystemView.vue`, `frontend/src/views/MCPView.vue`

---

## ~~TODO-57: [NEW PAGE] 操作日志管理页~~ ✅ 已完成

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

## ~~TODO-58: [NEW PAGE] 知识库管理页面~~ ✅ 已完成（2026-06-15）

**结果**：`frontend/src/views/KnowledgeView.vue` 全新独立页面：
- 拖拽 + 点击上传区（.txt/.md/.pdf/.json，≤10MB），可填描述
- 文件列表：名称/分块数/大小/描述/上传时间/删除；删除用 `useConfirmDialogStore`
- 路由 `/knowledge`，加入 NAV_ITEMS（高频区）

**涉及文件**：`frontend/src/views/KnowledgeView.vue`, `frontend/src/router/index.js`, `frontend/src/config/routes.config.js`

---

## ~~TODO-59: [FEATURE] 多模态聊天输入（图片附件）~~ ✅ 已完成（2026-06-15）

**结果**：
- 前端：聊天输入区新增"附图"按钮 + 粘贴图片支持；显示缩略图预览；发送时 base64 附在 WS 消息
- Java：`ChatRequest` 加 `imageBase64` 字段；`WebSocketController` 提取并透传；`AgentService` 写入 Python body
- Python：`ChatMessage` 加 `images` 字段；`chat_router.py` 接收 `image_base64`；Ollama provider 透传 images 给 Ollama API（支持 llava 等多模态模型）

**涉及文件**：`agent/services/base_provider.py`, `agent/services/ollama_provider.py`, `agent/api/chat_router.py`, `agent/core/conversation_flow.py`, `ChatRequest.java`, `WebSocketController.java`, `AgentService.java`, `ChatView.vue`, `websocket.js`

---

## ~~TODO-60: [BUG-HIGH] 多模态图片未持久化到对话历史~~ ✅ 已完成（2026-06-16）

Python 侧图片早已存入 JSON；修复前端 sendMessage 缺少 images_b64 字段 + onMounted 恢复路径不携带图片。commit `98f69d6`

---

## ~~TODO-61: [BUG-HIGH] 前端历史会话加载丢弃图片数据~~ ✅ 已完成（2026-06-16）

同 TODO-60，两个前端恢复路径一并修复。commit `98f69d6`

---

## ~~TODO-62: [BUG-HIGH] diffusers `_progress_state` 无锁全局变量~~ ✅ 已完成（2026-06-16）

3 处写操作加 `threading.Lock`。commit `b33b044`

---

## ~~TODO-63: [SEC-HIGH] knowledge_router 上传日志泄露物理路径~~ ✅ 已完成（2026-06-16）

日志只保留文件名+chunk数，去掉完整路径。commit `b33b044`

---

## ~~TODO-64: [REFACTOR-HIGH] 多模态"图片前缀"文本两处重复~~ ✅ 已完成（早期实现）

常量 `MULTIMODAL_IMAGE_PREFIX` 已在 `base_provider.py` 定义，两处均 import，早已正确实现。

---

## ~~TODO-65: [BUG-HIGH] diffusers 模型加载裸 `except Exception` 吞掉异常~~ ✅ 已完成（2026-06-16）

3 处裸 except 补 `exc_info=True`。commit `b33b044`

---

## ~~TODO-66: [BUG-HIGH] `project_id` 未写入对话历史 metadata~~ ✅ 已完成（2026-06-16）

`project_id` 写入会话 JSON 顶层字段，API 返回携带。commit `9cab32b`

---

## ~~TODO-67: [BUG-HIGH] 前端 API 无全局请求超时~~ ✅ 已完成（早期实现）

AbortController + 30s 超时早已实现；补 `options.timeout` 支持。commit `f8796b0`

---

## ~~TODO-68: [CLEANUP-MEDIUM] websocket.js 遗留 6 条 console.log~~ ✅ 已完成（早期实现）

早已全用 `console.warn/error`，无 `console.log`，无需改动。

---

## ~~TODO-69: [UX-MEDIUM] 反馈提交失败无用户提示~~ ✅ 已完成（早期实现）

早已有 `ElMessage.error` + 状态重置，无需改动。

---

## ~~TODO-70: [UX-MEDIUM] 附图按钮上传中无禁用态~~ ✅ 已完成（早期实现）

早已有 `isReadingImage` disabled 绑定，无需改动。

---

## ~~TODO-71: [PERF-MEDIUM] knowledge_router 先读文件再检查大小~~ ✅ 已完成（2026-06-16）

`file.size` 提前检查 + 读后 double-check，状态码改为 413。commit `090d90c`

---

## ~~TODO-72: [REFACTOR-MEDIUM] uploadKnowledgeFile 绕过通用 request()~~ ✅ 已完成（2026-06-16）

改用通用 `request()`，FormData 时自动跳过 Content-Type，60s 超时。commit `f8796b0`

---

## ~~TODO-73: [UX-MEDIUM] 分支对话丢弃附图~~ ✅ 已完成（2026-06-16）

`branchFromMessage` 补 `imagePreview`/`images_b64` 复制。commit `f8796b0`

---

## ~~TODO-74: [QUALITY-MEDIUM] knowledge_router 固定字符数分块~~ ✅ 已完成（2026-06-16）

新增 `_smart_chunk()`：段落→句子感知分块 + overlap，无需外部库。commit `b1a93fa`

---

## ~~TODO-75: [OBSERV-MEDIUM] 缺少请求 traceID~~ ✅ 已完成（2026-06-16）

`ChatRequest.request_id` 字段 + 前端 `crypto.randomUUID()` + 日志携带 traceID。commit `b1a93fa`

---

## ~~TODO-76: [SEC-LOW] knowledge_router 路径遍历防护缺失~~ ✅ 已完成（2026-06-14）

**问题**：`knowledge_router.py:25-28` 对 `user_id`/`filename` 参数未做路径净化，恶意 `../` 可能越目录。

**修复方向**：用 `pathlib.Path.resolve()` 验证最终路径在 `_KF_BASE` 内。

**涉及文件**：`agent/api/knowledge_router.py`

---

## ~~TODO-77: [CLEANUP-LOW] 会话消息数上限 200 硬编码~~ ✅ 已完成（2026-06-14）

**问题**：`conversations_router.py:107` 截断上限写死，大型项目或长对话无法调整。

**修复方向**：提取为 `settings.conversation_max_messages`（默认 200），通过 `.env` 可配置。

**涉及文件**：`agent/api/conversations_router.py`, `agent/config/settings.py`

---

## ~~TODO-78: [CLEANUP-LOW] diffusers `_load_pipeline()` 函数超 170 行~~ ✅ 已完成（2026-06-14）

**问题**：`diffusers_provider.py:55-100` 模型加载、dtype 选择、内存优化混在一起，可读性差。

**修复方向**：拆为 `_pick_dtype()`、`_apply_memory_opts()`、`_load_pipeline()` 三个小函数。

**涉及文件**：`agent/services/image/diffusers_provider.py`

---

## ~~TODO-79: [FEATURE-P0] system prompt 渠道感知（channel-aware）~~ ✅ 已完成（2026-06-23）

新增 `_request_channel_ctx` ContextVar（默认 `"web"`），`chat()`/`chat_stream()` 新增 `channel` 参数并通过 `ChatRequest.channel` 透传；`SystemPromptBuilder.build()` 新增 `channel` 参数，命中 `_WHISPER_EXCLUDED_CHANNELS`（当前仅 `feishu_im`）时跳过私密档案段注入。Java 侧 `ChatRequest` DTO 加 `channel` 字段，`FeishuEventController` 固定传 `feishu_im`，`AgentService` 两处调用（非流式 + 流式）均透传。Python 263 + Java 17 测试全绿。

---

## ~~TODO-80: [FEATURE-P1] 心跳执行器（heartbeat_check 调度动作）~~ ✅ 已完成（2026-06-23）

`SimpleTaskScheduler` 新增 `heartbeat_check` action：安静时段（默认 23:00-08:00，可配）直接跳过不调用 LLM；非安静时段调一次 `agent.chat(channel="feishu_im", use_tools=False)` 让模型判定 `SILENT`/`SPEAK: <内容>`，只有 `SPEAK` 才通过 `im_message` 工具实际发送，否则静默不打扰。判定阶段固定走 `feishu_im` 渠道（依赖 TODO-79），保证一旦决定发送，内容已经是 IM 风格，不会带出私密档案段。

暂未接入日历/待办查询（依赖 TODO-82 的只读工具），当前判定依据仅为短期/长期记忆；原文件里的 `larksuite-cli`、`artifacts/` 等不存在的工具/路径未被原样迁移，待对应能力落地后再补充判定上下文。新增 5 个测试（`test_heartbeat_check.py`），Python 268 测试全绿。

**涉及文件**：`agent/scheduler/simple_scheduler.py`, `agent/scheduler/simple_models.py`

---

## ~~TODO-81: [FEATURE-P1] 飞书群聊场景识别 + 静默/表情回应~~ ✅ 已完成（2026-06-23）

`FeishuEventController` 解析 `message.chat_type`（p2p/group）与 `mentions` 列表（新增 `feishu.bot-open-id` 配置精确匹配机器人，未配置时退化为低精度启发式），通过 `ChatRequest.sceneChatType/sceneMentioned` → `/api/chat` 的 `scene_chat_type`/`scene_mentioned` 字段透传给 Python。`conversation_flow.py` 在 `scene_chat_type="group"` 时注入 `[GROUP SCENE]` 系统消息：未被 @ 默认要求模型输出唯一一行 `NO_REPLY` 静默；被 @ 时正常作答。Java 侧命中 `NO_REPLY` 即静默丢弃（不发卡片、不注册撤回），群聊未被 @ 时还会跳过"思考中"占位提示避免刷屏。表情回应未做（标为后续可选项，飞书 `emoji` 消息类型已具备，仅缺业务判断）。新增 Java 3 个 + Python 3 个测试，Python 271 + Java 65 测试全绿。

---

## ~~TODO-82: [FEATURE-P2] 飞书日历 / 待办只读工具~~ ✅ 已完成（2026-06-23）

新增 `feishu_calendar_list`（`/calendar/v4/calendars/:id/events`，按 `calendar_id`+时间范围查询）与 `feishu_task_list`（`/task/v2/tasks`，可选按 `tasklist_guid` 过滤）两个只读工具，复用 `im.feishu_client._get_tenant_access_token`，随 `FeishuIMTool` 一起在 `tool_dispatcher.py` 按 `FEISHU_APP_ID` 配置注册。

**重要权限边界（务必记住）**：两者都用 `tenant_access_token`（应用身份），只能访问应用被授权访问的日历/任务清单（应用自建或被显式共享的），**不能**直接读取某个普通用户的私人日历/待办——那需要 `user_access_token`（OAuth 用户授权流程），未实现。原方案 `larksuite-cli calendar +agenda --as user` 用的是个人身份访问，与此处的应用身份访问是两种不同的权限模型；心跳巡检若要真正查"用户的"日程待办，需要先补 OAuth 授权流程（新增待办，未单独立项，下次涉及个人日历场景时优先考虑）。

新增 5 个测试（`test_feishu_readonly_tools.py`），Python 277 测试全绿。

---

## ~~TODO-83: [FEATURE-P3] 自维护版本化记忆技能~~ ✅ 已完成（2026-06-24）

**结果**：复用 TODO-80 的心跳节奏，不新增调度器入口。三处改动协同：

- `FileTool` 新增单文件白名单（`MEMORY_MD_PATH`，由文件位置锚定）：允许 `soul/MEMORY.md` 在常规 `safe_directories`（home + cwd）之外读写，但 `_check_path_safety` 显式禁止对它执行 delete/move，且不放宽到 `soul/` 下的其他文件（`SOUL.md`/`IDENTITY.md`/`USER.md` 等身份文件不受影响）
- `chat()` / `_call_model_with_tools` 新增 `allowed_tool_categories` 参数：代码层硬限制，在意图过滤和 skill 应用之后做最终交集过滤，不依赖关键词软匹配，防止任何上游逻辑悄悄放宽工具集合
- `SimpleTaskScheduler._consolidate_memory`：心跳非安静时段触发，节流时间戳（24h，写在 `soul/MEMORY.md` 头部 HTML 注释里）完全由代码读写，不依赖 LLM 维护；写入前自动轮转备份（`.bak.1`~`.bak.5`），LLM 仅获得 `allowed_tool_categories=["file"]` 的受限会话去自主判断是否归并、写回时用原子替换（temp + replace）

新增 14 个测试（`test_file_tool_whitelist.py` 4 个、`test_memory_consolidate.py` 5 个、`test_agent_core.py`/`test_heartbeat_check.py` 各若干），Python 287 测试全绿。

**评审中明确不做（YAGNI，附理由）**：独立 `agent/prompts/` 模板文件（与现有模块常量写法不一致）、事件计数双触发（无埋点基础设施）、`soul/` 黑名单清单（白名单设计本身已排除其他文件，黑名单防御一个不可达的攻击面）、checksum 校验行（无消费者代码）、RFC3339 时区感知（与仓库现有 naive isoformat 约定不一致）。

**涉及文件**：`agent/tools/builtin_tools/file_tool.py`, `agent/core/conversation_flow.py`, `agent/core/tool_dispatcher.py`, `agent/scheduler/simple_scheduler.py`, `soul/MEMORY.md`

---

## ~~TODO-PWA-1: BottomTabBar / MorePanel 导航数据源统一~~ ✅ 已完成（2026-07-03）

**结果**：
- `BottomTabBar.vue` 已从 `NAV_ITEMS` 派生底部 Tab（`BOTTOM_TAB_NAMES = ['chat', 'role-editor', 'memory']`），"更多" Tab emit `open-more`
- `MorePanel.vue` 三组导航（常用/AI能力/运维）全部从 `NAV_ITEMS`/`CONFIG_ITEMS`/`SYSTEM_ITEMS` 按分组规则派生，无硬编码数组

**涉及文件**：`frontend/src/components/layout/BottomTabBar.vue`, `frontend/src/components/layout/MorePanel.vue`, `frontend/src/config/routes.config.js`

---

## W1 心证层 + 5 信号自动撤回触发器（2026-07-01 ~ 2026-07-07）

> **设计依据**：2026-07-01 顶层战略对齐报告（府邸底层能力建设），含 heart-record plan 三段设计 + 5 信号整合。

---

### ~~TODO-84: soul/heart.md 心证永久档 + SoulLoader 加载~~ ✅ 已完成（2026-07-02）

**目标**：在 `soul/` 下新增 `heart.md`（心证铁卷），SoulLoader 作为可选文件加载（缺失时不报错）。

**文件结构**：
```markdown
# 心证铁卷

## 主人心证
<!-- 用户主动标记的永久记忆：关键决策 / 不可逆教训 / 已验证的规律 -->

## 主人教诲
<!-- 用户对 Agent 的长期行为指令 -->

## 智能体对主人的承诺
<!-- Agent 对用户的承诺 -->

## 主人对智能体的承诺
<!-- 用户对 Agent 的承诺 -->
```

**结果**：
- [x] 创建 `soul/heart.md`（git-tracked，含上述四段结构 + 占位说明）
- [x] `agent/soul/loader.py`：`SoulData` 新增 `heart: str` 字段；`OPTIONAL` 列表追加 `"heart"`；`load()` 按 `whisper` 同模式读取（不存在时 `heart=""`）
- [x] `agent/tests/test_soul_loader.py`：追加 `test_missing_heart_is_silent`（heart.md 缺失时 `data.heart == ""`）和 `test_heart_content_loaded`（文件存在时内容正确读入）

**涉及文件**：`soul/heart.md`, `agent/soul/loader.py`, `agent/tests/test_soul_loader.py`

---

### ~~TODO-85: SystemPromptBuilder 插入 heart 段~~ ✅ 已完成（2026-07-02）

**目标**：在 system prompt 组装顺序中插入 heart 段，位置在 ③MEMORY 之后、④HEARTBEAT 之前。

**修改后顺序**：①SOUL → ②USER → ③MEMORY → **③.5 HEART（心证铁卷）**→ ④HEARTBEAT → ⑤persona → ⑥whisper → ⑦tool_overlay

**理由**：心证优先级高于自动蒸馏的 MEMORY（用户显式标记 > LLM 自动归并），低于 HEARTBEAT（先知道记住什么，再按铁规思考）。

**结果**：
- [x] `agent/core/system_prompt_builder.py`：在 ③MEMORY 段之后插入心证段 `self._wrap("【心证铁卷】", d.heart)`，非空时追加
- [x] `agent/core/system_prompt_builder.py`：新增 `_HEART_EXCLUDED_CHANNELS = {"feishu_im", "wecom"}`，心证内容不发送到外部 IM 渠道（与 whisper 同策略）
- [x] `agent/tests/test_system_prompt_builder.py`：追加 `test_heart_nonempty_appears` + `test_heart_empty_absent` + `test_heart_before_heartbeat_order` 三个测试

**涉及文件**：`agent/core/system_prompt_builder.py`, `agent/tests/test_system_prompt_builder.py`

---

### ~~TODO-86: heart_record 工具（append/list/delete）~~ ✅ 已完成（2026-07-02）

**目标**：注册 `heart_record` 为 builtin tool，供 LLM 在用户说"记住这个"时自动调用。三个 action：
- `append(content, tags=None, weight="normal")` — 追加心证到合适分区
- `list(category=None)` — 列出心证（可按分区筛选）
- `delete(id)` — 删除心证（v2.0 考虑）

**工具注册参数 schema**：
```python
parameters = [
    ToolParameter(name="action", type="string", required=True,
                  description="append | list | delete"),
    ToolParameter(name="content", type="string", required=False,
                  description="心证内容（action=append 时必填）"),
    ToolParameter(name="category", type="string", required=False,
                  description="分区：主人心证 | 主人教诲 | 智能体对主人的承诺 | 主人对智能体的承诺"),
    ToolParameter(name="tags", type="string", required=False,
                  description="逗号分隔标签（可选）"),
    ToolParameter(name="weight", type="string", required=False,
                  description="normal | high | critical，默认 normal"),
]
```

**写入规则**：
- `append`：在 `soul/heart.md` 对应 `## 分区名` 下追加 `- [{{date}}] {{content}}` 行，按分区归类
- `list`：读取 heart.md，按分区 + 标签筛选返回
- `delete`：从 heart.md 中移除对应行，先备份 `.bak`

**边界保护**：
- 写入前做轮转备份（`.bak.1`~`.bak.5`，与 MEMORY.md 同策略）
- 只允许操作 `soul/heart.md`，不操作其他 soul 文件

**结果**：
- [x] 创建 `agent/tools/builtin_tools/heart_record.py`
- [x] `agent/core/tool_dispatcher.py` `_init_tools` 注册 `heart_record`（无环境变量依赖，始终注册）
- [x] `agent/tests/test_heart_record.py`：append/list/delete 各 1 用例 + 分区归类 1 用例 + 备份 1 用例 = 5 用例

**涉及文件**：`agent/tools/builtin_tools/heart_record.py`, `agent/core/tool_dispatcher.py`, `agent/tests/test_heart_record.py`

---

### ~~TODO-87: _detect_branch_failure() 5 信号整合~~ ✅ 已完成（2026-07-02）

**目标**：在 `conversation_flow.py` 的 `chat()` 和 `chat_stream()` 两个 ReAct 循环中，每轮 `_execute_tool_round()` 之后调用 `_detect_branch_failure()`，检测到分支失败时自动撤回最近 2 轮 + 注入 `[BRANCH_RESET]` 系统消息 + 重新进入循环。

**5 信号**：
1. 同工具同错误 3 次（`tool_dispatcher.py` 的 `_execute_tool_round` 统计）
2. LLM 输出连续 2 轮重复 >80% 相似度（Jaccard 词级相似度，无需外部库）
3. 用户显式纠偏（"不对/重来/换个思路"，由 chat_router 预检注入 flag）
4. 5 轮内 1 次 RuntimeError + 1 次空响应 → 立即触发
5. 工具调用失败分级超限：业务错（401/403）重试 1 次、系统错（5xx/超时）重试 3 次，超限触发

**检测窗口**：最近 5 轮（`_BRANCH_FAILURE_WINDOW = 5`）

**结果**：
- [x] `agent/core/conversation_flow.py`：新增 `_detect_branch_failure(round_history, iteration, max_iterations) -> Optional[str]` 私有方法
- [x] `agent/core/conversation_flow.py`：新增 `_text_similarity(a, b) -> float` 静态方法（Jaccard）
- [x] `agent/core/conversation_flow.py`：新增 `_auto_retract_last_n_rounds(messages, n, user_id) -> None` 方法
- [x] `agent/core/conversation_flow.py`：`chat()` L394 后插入 `_detect_branch_failure` 检测 + 撤回逻辑
- [x] `agent/core/conversation_flow.py`：`chat_stream()` L638 后插入同逻辑
- [x] `agent/core/conversation_flow.py`：`chat()`/`chat_stream()` 签名追加 `retract_on_failure: bool = True` 参数（默认开启，测试时可关闭）
- [x] `agent/core/tool_dispatcher.py`：`_execute_tool_round` 内对每个工具调用加入错误分级重试（`_is_auth_error` 判定 → 业务错 1 次 / 系统错 3 次），轮次结果标记 `_retry_exhausted`
- [x] `agent/core/tool_dispatcher.py`：新增 `_is_auth_error(exec_result) -> bool` 静态方法
- [x] `agent/tests/test_branch_detector.py`：5 信号各 1 用例 + 集成场景 2 用例 + 边界（关闭开关）1 用例 = 8 用例

**涉及文件**：`agent/core/conversation_flow.py`, `agent/core/tool_dispatcher.py`, `agent/tests/test_branch_detector.py`

---

## W2 PWA 导航统一 + L1/L2 缓存（2026-07-07 ~ 2026-07-14）

---

### ~~TODO-88: L1 响应缓存（prometheus 风格，5min TTL）~~ ✅ 已完成（2026-07-02）

**目标**：在 `agent/core/` 新增 `l1_cache.py`，对高频 prompt 模板做 hash 化缓存，命中即返回不调 LLM。插入到 `agent.chat()` 的最早入口。

**缓存 key**：`sha256(prompt + model_name + persona_hash + memory_snapshot_id)[:16]`

**TTL**：5min（`settings.l1_cache_ttl_seconds = 300`）

**边界保护**：TTL 过期后自动淘汰；缓存条目上限 100 条（LRU）；命中时写 `cache_hits_total` metrics；memory 发生写入时清空当前 user 的 L1（防止脏读）

**结果**：
- [x] 创建 `agent/core/l1_cache.py`：`L1Cache` 类（`get(key) / set(key, response) / invalidate_user(user_id)`，线程安全 `threading.Lock`）
- [x] `agent/config/settings.py`：追加 `l1_cache_ttl_seconds: int = 300` + `l1_cache_max_entries: int = 100`
- [x] `agent/core/agent.py`：在 `chat()` 的 `_build_messages_async` 之前插入 L1 查询；在 `chat()` 返回前写 L1
- [x] `agent/tests/test_l1_cache.py`：命中/未命中/TTL 过期（mock time）/LRU 淘汰/用户隔离 各 1 用例 = 5 用例
- [x] **边界测试**：`test_l1_boundary_ttl_expired_not_hit`（5min 后同一 prompt 不命中）+ `test_l1_boundary_prompt_diff_no_false_hit`（不同 prompt 不互串）

**涉及文件**：`agent/core/l1_cache.py`, `agent/config/settings.py`, `agent/core/agent.py`, `agent/tests/test_l1_cache.py`

---

### ~~TODO-89: L2 语义缓存（ChromaDB 风格，24h TTL）~~ ✅ 已完成（早期实现，2026-07-02 确认）

**目标**：对短期对话上下文做语义响应缓存；相似问题命中时直接返回 24 小时内的历史回答。

**缓存数据**：问题 embedding 与回答存入 ChromaDB `response_cache` collection；可按模型过滤，避免跨模型复用。

**TTL**：24h（`settings.semantic_cache_ttl_secs = 86400`）

**相似度阈值**：0.92（`settings.semantic_cache_threshold = 0.92`）

**结果**：
- [x] `agent/memory/semantic_cache.py`：以 `SemanticCache` 实现 L2 语义缓存，复用 embedding 模型与 ChromaDB `response_cache` collection
- [x] `agent/config/settings.py`：已配置 `semantic_cache_threshold=0.92`、24 小时 TTL 与容量上限
- [x] `agent/core/agent.py` / `agent/core/conversation_flow.py`：L1 未命中后查询 L2，成功响应写回 L2
- [x] `agent/tests/test_agent_core.py`：覆盖 L2 集成及不可用时的降级；实现还会清理过期项并限制容量

**涉及文件**：`agent/memory/semantic_cache.py`, `agent/config/settings.py`, `agent/core/agent.py`, `agent/core/conversation_flow.py`, `agent/tests/test_agent_core.py`

---

## W3 模型量化 + 图片补齐 + L3/L4 命中率（2026-07-14 ~ 2026-07-21）

---

### ~~TODO-90: Ollama 量化模型拉取 + keep_alive 调优~~ ✅ 已完成（2026-07-09）

**结果**：
- `ollama_keep_alive` 已在 `settings.py` 设为 `-1`（永久驻留显存），`ollama_provider.py` 三处透传 ✅
- `.env.docker` 显式追加 `OLLAMA_KEEP_ALIVE=-1` 配置项 + 注释说明 ✅
- dolphin 量化版：Ollama 官方库无预构建量化标签（`q4_K_M`/`q4_0` 均不存在），替代方案：
  - 项目已内置 `qwen2.5:7b`（4.7GB，已量化，比 dolphin F16 16GB 小 70%）
  - 如需 dolphin 量化：手动下载 dolphin GGUF → `ollama create` 导入

**涉及文件**：`.env.docker`, `agent/config/settings.py`, `agent/services/ollama_provider.py`（只读确认）

---

### ~~TODO-91: L3/L4 命中率监控~~ ✅ 已完成（早期实现，2026-07-02）

**结果**：
- `agent/api/metrics.py`：L3 指标 4 个（`l3_retrieve_hits` / `l3_retrieve_total` / `l3_retrieve_avg_similarity` / `l3_retrieve_duration_ms`）+ L4 指标 2 个（`l4_distill_source_coverage` / `l4_distill_snapshot_backups`） ✅
- `agent/memory/long_term.py`：`retrieve()` L531-542 埋点统计命中率 + 相似度 + 耗时 ✅
- `agent/memory/distiller.py`：蒸馏完成后 L160-181 统计 source_message_ids 覆盖率 + 快照备份数 ✅
- `agent/tests/test_metrics.py`：L3/L4 指标注册 + 调用验证（2 用例） ✅

**涉及文件**：`agent/api/metrics.py`, `agent/memory/long_term.py`, `agent/memory/distiller.py`, `agent/tests/test_metrics.py`

---

## W4 全量回归 + 迁移验证首跑 + 文档同步（2026-07-21 ~ 2026-07-28）

---

### ~~TODO-92: 迁移验证检查表首跑 + 文档同步~~ ✅ 已完成（2026-07-09）

**结果**：
- [x] `README.md` / `TODOS.md` / `CLAUDE.md` / `AI_PROJECT_CONTEXT.md` 日期同步至 2026-07-09，内容一致（含 Channel Adapter 架构图 + 3.10 节）
- [x] `docs/superpowers/plans/2026-07-01-heart-record.md` 归档标记完成（W1-W12 全时间线）
- [x] 全量回归：Python 525/530 passed（5 预存环境问题：sentence_transformers 未安装）
- [x] 前端 `npm run test`：14/14 passed
- [x] 修复：`test_verify_hooks.py` `_do_send` → `_do_send_original`（Channel Adapter 重构遗留测试名）
        - [x] 迁移验证首跑（`@verify migration-readiness`）：Python 会话式技能，随 Python Agent 退役
              归档；由 Java 侧数据对账/恢复演练/全量回归（`LegacyMigrationReconciliationTest` +
              168/12/14 用例 + java 模式冒烟）替代，见 `docs/migration/acceptance-record.md`

**涉及文件**：四个根目录 MD、`docs/superpowers/plans/2026-07-01-heart-record.md`、`agent/tests/test_verify_hooks.py`

---

## ~~TODO-93: 失职自查钩子（飞书推送 / scheduler / heart_record 前后验证）~~ ✅ 已完成（2026-07-04）

**背景**：2026-07-03 "糖糖失职问题"分析暴露 Agent 在关键操作前后无验证机制——推送前没 grep 确认内容、推送后没 verify 是否真的发出了、heart_record 写入后没读回确认。这是原始文档 12 项能力中的 #8（P1）。

**结果**：
- [x] **飞书 IM 推送前 verify**：`FeishuMessageSender.verifyMessageContent()` + `feishu_client._verify_message_content()` — 检查 content 非空、text 长度合规（≤15000）、JSON 可序列化
- [x] **飞书 IM 推送后 verify**：`FeishuMessageSender.verifyMessageId()` 抛异常触发 sendWithRetry 重试 + `feishu_client.execute()` message_id 缺失时重试 1 次 + logger.error 告警
- [x] **scheduler 任务执行后 verify**：`SimpleTaskScheduler._verify_task_file_write()` — heartbeat_check 后验证 MEMORY.md 存在且非空；result 中 file_path 字段自动检查
- [x] **heart_record 写入后 verify**：`_verify_write_contains()` / `_verify_write_excludes()` — append 后读回确认内容在文件中，delete 后读回确认已删除内容不在文件中
- [x] `agent/tests/test_verify_hooks.py`：19 个测试（heart_record 5 + feishu 8 + scheduler 6），全通过

**涉及文件**：`agent/im/feishu_client.py`, `backend/.../feishu/FeishuMessageSender.java`, `agent/scheduler/simple_scheduler.py`, `agent/tools/builtin_tools/heart_record.py`, `agent/tests/test_verify_hooks.py`

---

## ~~TODO-94: 进度恢复协议（progress_state.md 自动 sync）~~ ✅ 已完成（2026-07-04）

**背景**：heart.md「主人教诲」已写入铁律——"超当前窗口任务必写 progress 文件"。但 Agent 写了进度文件后，下次 session 启动时系统并不会自动检测和提示恢复。规则写了，但没有自动化承接。这是原始文档的 #11（P1）能力。

**结果**：
- [x] `agent/core/progress_recovery.py` — 新模块：`parse_progress_file()` 解析标准格式 + `find_incomplete_tasks()` 扫描 memory/work/ + `build_recovery_context()` 构建 [PROGRESS RECOVERY] 消息
- [x] 检测逻辑：最后更新 < 24h + 当前步骤 < 总步骤 → "未完成任务"；已完成/过期/缺时间戳 → 跳过
- [x] `agent/core/conversation_flow.py`：`_build_messages_async()` 首次消息时自动扫描 `memory/work/` 目录，注入 `[PROGRESS RECOVERY]` 系统消息段（`_recovery_injected` 集合防重复注入）
- [x] 用户说"继续上次的"→ 自动命中（每次 `chat()` 调用都走 `_build_messages_async`，_recovery_injected 已在首次注入后标记，不会重复注入但 Agent 已在上下文中记住了任务进度）
- [x] `agent/tests/test_progress_recovery.py`：18 个测试（parse 6 + find 8 + build 4），全通过

**涉及文件**：`agent/core/progress_recovery.py`, `agent/core/conversation_flow.py`, `agent/tests/test_progress_recovery.py`

---

## ~~TODO-95: 跨 session 记忆继承增强（LTM 任务进度感知）~~ ✅ 已完成（2026-07-05）

**背景**：LTM (ChromaDB) 已有，`MemoryDistiller` 会自动蒸馏对话中的 facts。但它蒸馏出来的是零散知识点（"霖君喜欢 xxx"），不包含"上次任务做到第几步了"的状态信息。跨 session 恢复任务时，LTM 检索无法提供进度上下文。这是原始文档的 #6（P1）能力。

**结果**：
- [x] `agent/memory/distiller.py`：新增 `_TASK_PROGRESS_KEYWORDS` + `_detect_task_progress()` 函数 —— 消息窗口含 `[TASK_DONE]`/`[TASK_BLOCKED]`/`progress_state`/`scheduler` 关键词时，蒸馏 facts 自动标记 `type: "task_progress"`（否则默认 `"fact"`）
- [x] `agent/memory/long_term.py`：`type` 提升为 ChromaDB 顶层字段（与 `user_id` 同级，支持 where 过滤）；`retrieve()` 新增 `type_filter` 参数，自动转为 `metadata_filter={"type": ...}`；`_save_to_vector_db()` / `store_batch()` / `_batch_update_access_records()` 三处同步更新
- [x] `ConversationFlowMixin._build_messages_async()`：TODO-94 进度恢复信号触发后，额外调用 `long_term.retrieve(type_filter="task_progress")` 查询跨 session 进度记忆，注入 `[TASK PROGRESS MEMORY]` 系统消息段
- [x] `agent/tests/test_memory_task_progress.py`：10 个测试（关键词检测 7 + 蒸馏标签 2 + 检索 filter 1），全通过

**涉及文件**：`agent/memory/distiller.py`, `agent/memory/long_term.py`, `agent/core/conversation_flow.py`, `agent/tests/test_memory_task_progress.py`

---

### 排期总览

```
W1 (7/01-7/07): TODO-84~87  ✅ 已完成（2026-07-02） 心证层 + 5 信号自动撤回触发器
W2 (7/07-7/14): TODO-PWA-1 + TODO-88~89  ✅ 已完成（2026-07-02） PWA 导航统一 + L1/L2 缓存
W3 (7/14-7/21): TODO-90~91  ✅ 已完成（2026-07-02） 模型量化 + L3/L4 命中率
W4 (7/21-7/28): TODO-92  ✅ 已完成（2026-07-09） 迁移验证检查表归档 + 全量回归 525/530 + 四 MD 同步
```

> **2026-07-09 更新**：W1-W4 全部收尾——TODO-90 keep_alive 调优 + TODO-91 L3/L4 确认已落地 + TODO-92 全量回归（525/530 pass，修复 verify_hooks 测试）+ 四 MD 同步 + heart-record 归档 W1-W12 完整时间线。新增 SoulLayer v1.1（大文件承载能力：SoulLoader 告警不阻断 + SoulData 可观测性 + token 预算上调）。

```
W5 (7/03-7/10): TODO-93 ✅ + TODO-94 ✅  失职自查钩子 + 进度恢复协议（均已完成 2026-07-04）
W6 (7/10-7/17): TODO-95 ✅  跨 session 记忆继承增强（已完成 2026-07-05）
```

> **2026-07-03 新增**：TODO-93~95 来自"糖糖失职问题"分析，承接 HEARTBEAT 能力边界自检 + heart.md 铁律。TODO-95 依赖 TODO-94 的 progress_state 标准格式。
>
> **2026-07-05 更新**：TODO-93~95 全部落地，W1-W6 全部完成。
>
> **2026-07-06 新增**：TODO-96~98 "主人永久铁律"机制——用户长期协作中沉淀 21 条不可违反规则，Agent 每次回答时严格遵守。

---

## W7 主人铁律 — 数据层（2026-07-06 ~ 2026-07-13）

---

### ~~TODO-96: soul/rules.md + heart_record 扩展（数据层）~~ ✅ 已完成（2026-07-06）

**目标**：创建 `soul/rules.md` 模板文件（7 个作用分类），heart_record 新增 rule_add/rule_list/rule_delete 三个 action，含冲突检测 + 版本管理 + 回滚 + 21 条校验。

**7 个作用分类**：安全边界 / 模型绑定 / 工具使用 / 失职与自查 / 记忆与持久化 / 用户交互 / 隐私与数据

**规则字段**：
- 必填：rule_id（RULE-XXX 编号）、rule_title（标题）、rule_category（作用分类）、rule_requirement（具体诉求）
- 可选：rule_trigger（触发场景）、rule_consequence（违反后果）
- 版本：version（v1/v2/...）、status（现行/已废止）
- 分级：rule_priority（critical ★★★★★ / high ★★★★ / normal ★★★）、rule_privacy（public/private/secret）

**heart_record.py 扩展**：
- [x] 新增 `RULES_MD_PATH` 常量 + `_RULE_CATEGORIES` 7 分类枚举
- [x] 新增 `rule_add` action：字段校验 + 版本管理（同 ID 升级时废止旧版）+ 幂等检测 + 写入后 verify
- [x] 新增 `rule_list` action：按分类/状态筛选列出
- [x] 新增 `rule_delete` action：软删除（标注已废止）而非物理删除
- [x] 新增 `_check_rule_conflict()`：时间约束矛盾 / 模型绑定矛盾 / 行为指令互斥 三类冲突静态检测
- [x] 新增 `_deprecate_rule_version()`：升级时废止旧版
- [x] 新增 `_rollback_to_bak()`：从 .bak.n 回滚，回滚前抢救快照 .bak.0
- [x] 新增 `_validate_21_rules()`：校验 21 条全量加载（缺失/废止/现行计数）
- [x] 复用 `_rotate_backup(RULES_MD_PATH)` 5 份轮转备份

**SoulLoader 扩展**：
- [x] `SoulData` 新增 `rules: str = ""` 字段
- [x] `OPTIONAL` 列表追加 `"rules"`
- [x] `load()` 中按可选文件模式加载 `rules.md`

**测试（test_iron_rules.py W7 部分）**：
- [x] rule_add 结构化写入（RULE-001 含全部字段）→ 读回验证
- [x] 缺少必填字段 → 返回失败
- [x] 无效作用分类 → 被拒绝
- [x] 冲突检测：时间约束矛盾 → 警告但不阻断
- [x] 冲突检测：模型绑定矛盾 → 警告但不阻断
- [x] 版本升级：同 ID 二次录入 → 旧版废止 + 新版 v2
- [x] 幂等性：同 ID 同 title 同 requirement → 拒绝重复录入
- [x] 回滚：_rollback_to_bak(n=3) → 内容与 .bak.3 一致
- [x] 21 条校验：_validate_21_rules() → ok/missing/deprecated 正确
- [x] SoulLoader 可选加载：rules.md 缺失 → rules="" 不报错

**涉及文件**：`soul/rules.md`, `agent/tools/builtin_tools/heart_record.py`, `agent/soul/loader.py`, `agent/tests/test_iron_rules.py`

---

## ~~W8 主人铁律 — 检索层（2026-07-07 ~ 2026-07-07）~~ ✅ 提前完成

---

### ~~TODO-97: SystemPromptBuilder ③.6 RULES 段 + 隐私分层 + 缓存~~ ✅ 已完成（2026-07-07）

**结果**：
- [x] `SystemPromptBuilder`：新增 `_RULES_PRIVACY_CHANNEL_MAP` 隐私分层字典（web/CLI 可看 public+private，IM 仅 public；secret 永不注入）
- [x] `SystemPromptBuilder.build()`：③.6 RULES 段插入（③.5 HEART 之后、④ HEARTBEAT 之前），新增 `max_context_tokens` 参数
- [x] `_filter_rules_by_privacy()`：按渠道过滤规则隐私等级
- [x] `_get_rules_cached()` / `_set_rules_cache()`：按 rules 内容 hash + channel + degrade 标志做缓存 key
- [x] `_build_rules_section()`：含摘要行 + token 预算退化（<4096 时仅注入 critical 级）
- [x] `_parse_rules_entries()`：解析 rules.md 结构化规则（含 **加粗标记处理、已废止跳过）
- [x] `invalidate_rules_cache()`：heart_record rule_add/rule_delete 写入后调用
- [x] `conversation_flow.py`：`system_prompt` property 传递 `max_context_tokens` 给 build()
- [x] `heart_record.py`：`_do_rule_add` / `_do_rule_delete` 写入后调用 `invalidate_rules_cache()`
- [x] 测试 42 用例全通过（12 原有 + 30 新增 W8），零回归：注入位置 / 隐私分层（4 渠道）/ 缓存命中与失效（6 场景）/ token 退化降级（3 场景）/ rules.md 缺失静默 / 解析健壮性

**设计偏离**（有意识选择）：
- 缓存 key 用内容 hash 而非文件 mtime —— 内容相同时复用缓存，heart_record 写入后 `invalidate_rules_cache()` 主动失效；避免测试被真实文件污染的架构问题
- RULES 数据源使用 `d.rules`（SoulLoader 加载），而非直接读文件 —— 与 heart 段保持一致模式

**涉及文件**：`agent/core/system_prompt_builder.py`, `agent/core/conversation_flow.py`, `agent/tools/builtin_tools/heart_record.py`, `agent/tests/test_system_prompt_builder.py`

---

## W9 主人铁律 — 执行层 + 集成（2026-07-21 ~ 2026-07-27）

---

### ~~TODO-98: 分支保护铁律违反扫描 + 全量回归~~ ✅ 已完成（2026-07-07）

**结果**：
- [x] `conversation_flow.py`：新增模块级 `_HARDCODED_VIOLATION_PATTERNS`（15 个硬编码危险模式：rm -rf/sudo rm/os.system/eval/exec/DROP TABLE/chmod 777/curl|sh 等）
- [x] `conversation_flow.py`：`_init_rule_violation_patterns()` — 硬编码模式 + 从 rules.md 提取"不得/禁止/不能/不可 XXX"禁止性关键词作为额外模式
- [x] `conversation_flow.py`：`_get_rule_violation_patterns()` — 懒加载 + 缓存模式列表
- [x] `conversation_flow.py`：`_check_rule_violation(text)` — 扫描 LLM 输出匹配违规模式，最多返回 3 条
- [x] `conversation_flow.py`：`_detect_branch_failure` 新增信号 6（铁律违反扫描），在信号 4 之后、return None 之前
- [x] `agent.py`：`_rule_violation_patterns = None` 初始化（懒加载首触发点）
- [x] 测试 15 用例全通过（`test_iron_rules.py` W9 追加）：危险命令检测 / 代码执行检测 / SQL 危险检测 / 正常回复无误报 / 关键词提取 / 违规触发集成 / 空文本 / 最多 3 条 / 缓存复用 / 端到端 21 条全量加载 / IM 隐私分层
- [x] 全量回归：231/232 passed（1 预存 sentence_transformers 环境问题），170/171 W1-W9 passed，零新增回归
- [x] 四 MD 同步（CLAUDE.md / AI_PROJECT_CONTEXT.md / README.md / TODOS.md）

**涉及文件**：`agent/core/conversation_flow.py`, `agent/core/agent.py`, `agent/tests/test_iron_rules.py`

---

### 排期总览（续）

```
W7 (7/07-7/13): TODO-96  ✅ 已完成（2026-07-06） 数据层（rules.md + heart_record 扩展 + SoulLoader）— 25 tests
W8 (7/14-7/20): TODO-97  ✅ 已完成（2026-07-07） 检索层（SystemPromptBuilder ③.6 + 隐私分层 + 缓存）— 30 new tests
W9 (7/21-7/27): TODO-98  ✅ 已完成（2026-07-07） 执行层（铁律违反扫描 6th 信号 + 全量回归 + MD 同步）— 15 new tests
```

> **W1-W9 全部完成！** 主人永久铁律机制（数据层 → 检索层 → 执行层）三层全部落地，累计新增 70 个测试用例，231/232 全量通过（1 预存环境问题）。
>
> **设计基准**：2026-07-06 五问完整设计方案（备份回滚/冲突检测/版本管理/隐私分层/录入UX/性能缓存），详见对话记录。

---

## W10 Channel Adapter 抽象层（2026-07-08 ~ 2026-07-21）✅ Phase 1+2 已完成

> **设计依据**：2026-07-08 Channel Adapter 完整设计方案 v1.2（`docs/channel-adapter-design.md`），含 4 风险 + 6 补充建议 + 3 微优化。
>
> **目标**：建立统一 Channel Adapter 抽象层，4 channel（飞书/企微/Web/Telegram）走统一接口，支持多通道并行广播。
>
> **已完成（2026-07-08）**：Phase 1（Python 抽象层+飞书+Web+Router+Factory）+ Phase 2（企微+Telegram+ChannelMessageTool）+ Java 侧（ChannelAdapter interface+Manager+FeishuChannelAdapter）。Phase 3（双通道并行广播+可观测性）待执行。

---

### ~~TODO-99: Channel Adapter 抽象层接口（Python + Java 两侧）~~ ✅ 已完成（2026-07-08）

**结果**：
- [x] Python 侧：`agent/im/channel_adapter.py`（~280 行，ABC + 全部数据模型 + TokenBucket + RetryConfig + ChannelMetric）
- [x] Java 侧：`backend/web/im/` 包全部 8 个文件（ChannelAdapter.java + 5 POJO + Manager）
- [x] `agent/im/tests/test_channel_adapter.py`：28 用例全通过（TokenBucket/RetryConfig/ChannelMetric/extract_message_id 归一化/接口契约）
- [x] `agent/im/__init__.py`：导出所有公共 API
- [x] `backend/web/im/FeishuChannelAdapter.java`：实现 ChannelAdapter，委托 FeishuMessageSender
- [x] Java `mvn compile` 编译通过

**涉及文件**：`agent/im/channel_adapter.py`, `backend/web/im/*.java` (8 files), `agent/im/__init__.py`, `agent/im/tests/test_channel_adapter.py`

---

### ~~TODO-100: FeishuAdapter 实现（Python + Java，Phase 1）~~ ✅ 已完成（2026-07-08）

**结果**：
- [x] `agent/im/adapters/feishu_adapter.py`（~200 行）：按操作独立限流（text 50/s, card 1.67/s, image 10/s），card 30KB 截断，TODO-93 钩子，Session 连接池
- [x] `agent/im/feishu_client.py` 改造：`FeishuIMTool.execute()` 委托给 `FeishuAdapter`，adapter 不可用时走 legacy 路径（100% 向后兼容）
- [x] `backend/web/im/FeishuChannelAdapter.java`：实现 ChannelAdapter，委托 FeishuMessageSender
- [x] 回归：5 个原有 test_feishu_client 测试 + 28 个 channel_adapter 测试全绿

---

### ~~TODO-101: WebAdapter 实现（Python 侧，Phase 1）~~ ✅ 已完成（2026-07-08）
- [x] `agent/im/adapters/web_adapter.py`（~60 行）：WS 推送，无限流无重试，始终可用

### ~~TODO-102: ChannelRouter + ChannelAdapterFactory（Python + Java，Phase 1）~~ ✅ 已完成（2026-07-08）
- [x] `agent/im/channel_router.py`（~220 行）：单通道/广播/去重/resolve_channels/指标聚合
- [x] `agent/im/adapter_factory.py`（~70 行）：自动发现 4 adapter
- [x] `agent/im/tests/test_channel_router.py`：14 用例全通过
- [x] `backend/web/im/ChannelAdapterManager.java`：Spring 注入 + broadcast + 生命周期

### ~~TODO-103: WeComAdapter 实现（Python 侧，Phase 2）~~ ✅ 已完成（2026-07-08）
- [x] `agent/im/adapters/wecom_adapter.py`（~160 行）：限流 1.67/s, max_card_size 4KB, Session 复用

### ~~TODO-104: TelegramAdapter 实现（Python 侧，Phase 2）~~ ✅ 已完成（2026-07-08）
- [x] `agent/im/adapters/telegram_adapter.py`（~170 行）：限流 30/s, Inline Keyboard card, Session 复用

### ~~TODO-105: ChannelMessageTool — LLM 工具 channel-aware 改造（Phase 2）~~ ✅ 已完成（2026-07-08）
- [x] `agent/im/channel_message_tool.py`（~100 行）：统一 LLM IM 工具，通过 ChannelRouter 路由到任意 channel

---

### ~~TODO-106: 双通道并行广播 + 可观测性（Phase 3）~~ ✅ 已完成（2026-07-09）

**目标**：ChannelRouter.broadcast_text() 生产可用，整合通知系统，暴露 `/health` channel 状态。

**结果**：
- [x] `agent/im/channel_notifier.py`：整合 ChannelRouter 到通知系统（notify_user / notify_user_sync）
- [x] `agent/im/channel_router.py`：`send_with_fallback()` 失败降级到 Web + 全局单例 `_get_global_router()`
- [x] `agent/api/health_router.py`：新增 `GET /health/channels` 端点返回各 channel 状态
- [x] `agent/tests/test_channel_phase3.py`：7 用例（fallback / broadcast_to_all / global router）
- [x] 全量回归：133 passed, 0 failed
- [x] 文档同步：`CLAUDE.md` / `AI_PROJECT_CONTEXT.md` 更新架构图

**涉及文件**：`agent/im/channel_notifier.py`, `agent/im/channel_router.py`, `agent/api/health_router.py`, `agent/tests/test_channel_phase3.py`

---

### 排期总览（续）

```
W10 (7/08-7/14): TODO-99~102  ✅ 已完成（2026-07-08） Phase 1: 抽象层 + 飞书 + Web + Router + Factory
W11 (7/14-7/21): TODO-103~105 ✅ 已完成（2026-07-08） Phase 2: 企微 + Telegram + ChannelMessageTool
W12 (7/21-7/28): TODO-106     ✅ 已完成（2026-07-09） Phase 3: 双通道并行广播 + 可观测性 + 文档同步
```

> **设计基准**：2026-07-08 Channel Adapter 完整设计 v1.2（`docs/channel-adapter-design.md`，含 4 风险 + 6 补充建议 + 3 微优化）。
>
> **2026-07-09 更新**：TODO-106 Phase 3 代码 + 文档全部完成（commit `0115d8c`），CLAUDE.md + AI_PROJECT_CONTEXT.md 架构图已更新。W10-W12 Channel Adapter 全量落地。

---

## SoulLayer v1.1 — 大文件承载能力（2026-07-09）

> **触发**：用户问"灵魂层 30K+ 字节能否承载"，5 问分析 → Phase 1 落地。

### ~~SoulLayer-1: SoulLoader 大小监控 + 告警~~ ✅ 已完成（2026-07-09）

**结果**：
- [x] `agent/soul/loader.py` v1.1：新增 `max_file_size`（50KB）/ `max_total_chars`（14K）告警，超限 WARNING 不阻断
- [x] `SoulData` 新增 `total_chars` / `file_sizes` 可观测性字段
- [x] `agent/tests/test_soul_loader.py`：14→23 用例（大文件不阻断/不截断/size 追踪/rules 加载/空文件等）
- [x] Token 预算上调：`max_context_tokens` 7000→8000，`OLLAMA_NUM_CTX` 4096→8192
- [x] 全量回归：532/538 passed（23 SoulLoader + 42 SystemPromptBuilder 全通过）

**设计决策**：
- 不拆成多 system message——单 message + `_SEP` 分段是最兼容的设计
- 不静默截断——内容保护 > 自动化裁剪，风险由日志告警提示用户
- Phase 2（token-aware 截断）等实际触发告警后再做

**涉及文件**：`agent/soul/loader.py`, `agent/tests/test_soul_loader.py`, `agent/config/settings.py`, `.env.docker`

---

## 文档同步（2026-07-09）

### ~~DOC-1: 三份 README 同步至 2026-07-09~~ ✅ 已完成（2026-07-09）

**结果**：
- [x] `README.md`：日期 + 架构图 + Channel Adapter + SoulLayer v1.1 + 工具列表 + Java 能力表 + 可观测性 + token 预算 + soul 目录 + 测试数
- [x] `agent/README.md`：日期 + `im/` 目录从 1 行展开为 10 行 + SoulLoader v1.1 标注 + 能力描述加两段
- [x] `backend/web/README.md`：日期 + 目录结构新增 `im/` 段（10 文件）+ 关键实现细节新增 Channel Adapter 小节

**涉及文件**：`README.md`, `agent/README.md`, `backend/web/README.md`


---

## W13 Java 统一迁移 — 执行队列（2026-08-05 创建，2026-08-07 开始执行）

> **来源**：`docs/superpowers/plans/README.md`（权威执行队列）+ 三份 2026-08-05 计划文件；设计见 `docs/superpowers/specs/2026-08-05-java-unification-design.md`。
> **执行规则**：严格按 Plan 1 → 2 → 3 顺序执行；每项任务需跑对应测试命令并单独提交；Plan 3 的 Python 退役需先完成六项确认并经授权，不删除任何 Python 数据/卷/源码。
> **注意**：plans/README.md 明确说明 TODOS.md 曾有无关未提交修改，因此计划文件是每任务细粒度步骤的权威来源；此处为队列级同步，勾选状态以这里为准、完成时同步回计划文件。

### ~~TODO-107: Plan 1 — Java 后端基础 + AI 运行时（backend-ai-runtime）~~ ✅ 已完成（2026-08-07）
> **2026-08-07 完成**：Boot 2.7.18→3.5.16、Java 1.8→21（本地 JDK：`D:\software\jdk21\jdk-21.0.12+8`）、javax→jakarta 22 文件、WebConfig 迁移 HttpClient 5、springdoc 2.9.0、Docker Temurin 21。BuildBaselineTest 红转绿（commit `8f0dce9`）。
> **已知问题（已修复）**：全量 `mvnw test` 曾因非 daemon 线程不退出导致 Maven 挂起——已通过 `ChannelAdapterManager.broadcastExecutor` daemon 化 + 新增 daemon `TaskScheduler` bean（`SchedulingConfig`）+ 修复 `FeishuIntegrationTest` 场景 3（发送响应补 `message_id` 避免 TODO-93 钩子无限重试、RestTemplate 加 3s 读超时、事件执行器 daemon 化）解决；全量 103 用例 30s 内全绿。
> **计划偏差**：计划片段因 `@SpringBootTest(properties=...)` 自带属性、升级前也会通过，改为断言默认 `python` 模式以获得真实红转绿。

- [x] Task 1: 升级 Java 基线（Java 21 + Spring Boot 3.x + `ai.runtime.mode` 配置 + Docker JDK 21）
- [x] Task 2: Provider 无关 LLM 契约（`ChatTurn` / `ModelEvent` / `LlmProvider`，事件限 token/tool_call_start/tool_call/tool_calls_done/done/error）— commit `ebd1c4a`
- [x] Task 3: Ollama + 云 LLM 适配器与路由（`OllamaLlmProvider` / `OpenAiCompatibleLlmProvider` / `LlmProviderRouter`，凭据脱敏，MockWebServer 验证）— commit `c054df4`
- [x] Task 4: 工具内核（`ToolExecutor`：5 轮上限 + JSON/标签/代码块/纯文本 4 种解析 + shadow 模式拒绝写工具 + readOnly/requiredRole/timeout 元数据）— commit `336d5fd`
- [x] Task 5: 本地 ReAct 编排（`AgentOrchestrator` + `LocalChatService`，`python`/`shadow`/`java` 模式切换，SSE→WS 事件映射共用）— commit `b0c1093`
- [x] Task 6: 锁定公开 chat 契约（`ChatContractTest` + `contracts/chat-stream-events.jsonl` 回归，全量 103 用例绿）— commit `071ef8b`

**涉及文件**：`backend/web/pom.xml`, `backend/web/Dockerfile`, `backend/web/src/main/resources/application.yml`, `backend/web/src/main/java/com/intelligent/agent/web/`（新增 `ai/llm/`, `ai/tool/`, `ai/agent/`, `api/chat/`, `config/SchedulingConfig.java`, `config/AgentConfig.java`, `config/LlmProviderConfig.java`）, `backend/web/src/test/`（`ai/` 测试 + `ChatContractTest` + `contracts/chat-stream-events.jsonl`）

### ~~TODO-108: Plan 2 — 记忆 / 领域 API / 调度 / 集成（domain-and-integrations）~~ ✅ 已完成（2026-08-08）

> **2026-08-08 完成**：Plan 2 全部 5 个 Task 落地，每项单独提交，全量 155 用例绿（0 失败）。
>
> - Task 1 记忆/向量端口（commit `45b3044`）：`ai/memory/{MemoryRepository,MemoryRecord,MemorySearchQuery}` + `infrastructure/vectorstore/VectorMemoryRepository`（字符 n-gram 哈希嵌入 + 余弦，用户隔离契约 7 用例）
> - Task 2 会话记忆/RAG/缓存（commit `1c1fad9`）：`ConversationMemoryService`（短期 deque TTL 24h/100 条 + 5 轮蒸馏 + 10 轮摘要 + 项目上下文）+ `SemanticResponseCache`（persona/model 感知键 + 24h TTL + 相似检索）+ `TextEmbedding` 抽取复用；`AgentOrchestrator` 注入记忆并缓存短路；11 用例
> - Task 3 领域代理逐个替换（4 次单独提交）：role `199d25d` / conversation `5257524` / project `2a18438` / task `912296c`；`DomainApiContractTest` 18 用例；project 旧路径映射顺带修正（原 `/spec` 等缺 `/api/project` 前缀导致 GET spec 405）
> - Task 4 knowledge/skills/analytics/teaching（commit `0ba4fb0`）：句子级分块写入 MemoryRepository、413 超限用例、PDF 提取（pdfbox）、内置 Skill 模板、反馈/Skill 日志/工具调用统计、教学题库 24 题 + 批改/错题本；`KnowledgeAndSkillContractTest` 7 用例；新增 Java 侧 `TeachingController`
> - Task 5 调度器 + 具名集成（commit `129f531`）：`TaskSchedulerService`（秒级 tick，immediate/delay/interval/datetime/cron 到期计算）+ `integration/*`（Feishu/WeChat/Telegram 通道客户端限流+重试+OAuth token 持久化、ComfyUiClient 进度轮询、McpToolRegistry）+ `ChannelRouter` 幂等广播去重；`ChannelDeduplicationTest` 5 用例（MockWebServer）
>
> **设计说明**：所有控制器在 `ai.runtime.mode=java|shadow` 时走本地领域服务，`python` 模式保持原代理路径（向后兼容，Plan 3 shadow 切换时回退可用）。

- [x] Task 1: 记忆与向量库端口（`MemoryRepository` + `VectorMemoryRepository`，按 user/role/project 过滤）
- [x] Task 2: 会话记忆 / RAG / 语义缓存迁移（`ConversationMemoryService` + 蒸馏 + 摘要 + persona/model 感知缓存键）
- [x] Task 3: 逐个替换 role / conversation / project / task 代理为本地服务（每片单独提交）
- [x] Task 4: 迁移 knowledge / skills / analytics / teaching（含 413 超限用例 + 句子级分块）
- [x] Task 5: 调度器 + 具名集成（ComfyUI / MCP / 飞书 / 企微 / Telegram，幂等广播 + 限流 + 重试 + 回调校验）

**涉及文件**：`backend/web/src/main/java/com/intelligent/agent/web/`（新增 `ai/memory/`, `infrastructure/`, `domain/`, `integration/` 包）+ 对应 Proxy Controller

### TODO-109: Plan 3 — Java CLI / 切换 / 数据迁移 / Python 退役（client-cutover-retirement）

> **2026-08-08 全部完成**：Task 1~5 单独提交；Task 6 退役已执行（owner 授权）。
> 实测切换（java 模式）发现并修复 2 个 bug（commit `d3c1b76`）；数据对账 12/12 通过
> （导出 + SHA-256 + dry-run + 真实导入，报告在 `docs/migration/reports/`）；Chroma 卷只读归档
> （`docs/migration/archive/`）；Python 源码/CLI 已删除（commit `354bf33`，git 可恢复）。
> 验收记录：`docs/migration/acceptance-record.md`；遗留验证项（IM 真实送达、全栈 E2E）
> 因本机无 Python/IM 凭证环境标注，不影响代码层退役。

- [x] Task 1: Java CLI + 安全登录（commit `a299336`：Picocli + `TokenStore` 权限收紧，不存 `JWT_SECRET`；后端 `/api/auth/cli-token` scope=cli 30 天）
- [x] Task 2: 聊天流式 + 本地会话（commit `c9666f7`：`BackendClient` + `SseEventParser` + `SessionStore`；后端新增 `POST /api/chat/stream` SSE 端点）
- [x] Task 3: CLI 功能对齐（commit `0d73439`：REPL + `!models/!model/!personas/!persona/!history/!retract/!sessions/!clear/!exit` + `model/persona/retract` 子命令）
- [x] Task 4: 校验式逻辑数据迁移（commit `69eda2a`：manifest + SHA-256 + 重新向量化；`MigrationValidator` 5 用例）
- [x] Task 5: Shadow 验证 + 分阶段切换（commit `586e99a`：`ShadowComparisonRecorder` 脱敏 + `AI_RUNTIME_MODE`/`AI_SHADOW_ALLOWLIST` 可回滚路由；前端 14 用例也绿）
- [x] Task 6: 六项确认（数据对账/恢复演练/E2E/IM 送达/回滚窗口/删除授权）后经授权退役 Python（2026-08-08 完成：commit `354bf33` + `0858ba3`）

**涉及文件**：`client/`（新增 Maven 工程）、`backend/web/.../infrastructure/migration/`、`backend/web/src/main/resources/application.yml`, `docker-compose.yml`, `start_all.bat`, `start_all.sh`, 四份根目录 README / AI_PROJECT_CONTEXT

---

## TODO-110: Java 迁移补缺 — 功能等价（2026-08-08 创建）

> **来源**：2026-08-08 功能等价审计。Plan 1-3 覆盖计划文件范围，但 Python 侧以下能力未纳入迁移，
> 当前 java 模式下失效或降级。按优先级逐项补齐，每项独立提交。

### Task 1: 工具系统迁移（核心缺口，工具注册表当前为空）

- [x] 迁移 calculator / time 等无外部依赖工具（`ai/tool/builtin/{CalculatorTool,TimeTool}`，exp4j 安全求值）
- [x] 迁移 file_tool（路径白名单 + 只读模式，对齐 TODO-76 安全要求，`ai/tool/builtin/file/FileTool`）
- [x] 迁移 shell_tool（命令白名单 + 敏感路径拒绝，`ai/tool/builtin/shell/ShellTool`）
- [x] 迁移 web_search（DuckDuckGo HTML 端点 + jsoup 解析，可注入 URL 便于测试，`ai/tool/builtin/web/WebSearchTool`）
- [ ] 迁移 database_tool（MySQL 连接器，按配置启用）
- [ ] 迁移 feishu_calendar / feishu_task（依赖飞书 OAuth，随 Task 3 OAuth 本地化）
- [x] 注入 ToolExecutor（AgentConfig 注册 5 个内置工具 bean），ToolExecutor 端到端验证通过

> **2026-08-08 进度**：5 个核心工具已迁移（commit 待填），`BuiltinToolTest` 8 用例 + 全量 176 绿。
> database_tool 需 MySQL 驱动与配置（随 Task 2 /api/config 本地化一起）；feishu 工具随 Task 3 OAuth。

### Task 2: 死端点本地化（前端页面恢复可用）

- [ ] /api/image/* 与 /api/images（图片生成：ComfyUiClient 已具备基础，补 generate/列表/删除/provider-status）
- [x] /api/memory/*（记忆管理：stats/list/search/delete/importance/clear 本地化，`GapFillContractTest` 覆盖）
- [x] /api/tools/list（工具列表：ToolExecutor/McpToolRegistry 聚合，java 模式本地返回）
- [ ] /api/config/*（运行时配置读写：接 application.yml + 领域服务）
- [ ] /api/cloud/*（云端服务商 CRUD + API KEY 绑定：接 LlmProviderRouter 配置）
- [x] /api/models 与 /api/model/switch（本地：`ModelService` Ollama /api/tags + per-user 偏好持久化）
- [x] /api/notifications/poll（`TaskSchedulerService` 通知队列，log action 入队）
- [ ] /api/feishu/oauth/*（authorize/callback/status 本地 OAuth 流程 + FeishuChannelClient token 持久化）
- [x] /api/python/health（java 模式返回 `java-only` 自包含状态）

> **2026-08-08 进度**：tools/memory/models/switch/python-health/notifications 已本地化（commit 待填），
> `GapFillContractTest` 7 用例 + 全量 183 绿。剩余 image/config/cloud/feishu-oauth 继续。

### Task 3: persona / prompt / soul 系统

- [ ] SystemPromptBuilder：persona 描述 → 模型覆盖层 → 工具指令（对齐 Python PromptBuilder）
- [ ] channel-aware 系统提示（web/CLI 与 IM 隐私分层）
- [ ] rules.md / heart.md 铁律注入 + 隐私分级（对齐 TODO-96~98 W7-W9）
- [ ] heart_record 工具（rule_add/list/delete，随 Task 1 工具系统落地）

### Task 4: chat 高级行为

- [ ] 多模态 image_base64 透传（ChatRequest → AgentOrchestrator → LLM）
- [ ] 群聊场景静默（scene_chat_type / scene_mentioned + NO_REPLY 规则）
- [ ] [TASK_DONE]/[TASK_BLOCKED] 任务标记 → task_update/task_blocked WS 事件
- [ ] 分支失败检测（对齐 _detect_branch_failure 信号）+ 铁律违反扫描
- [ ] 撤回级联清理记忆（retract 时 delete_by_ids 短期 + 长期排除检索）

### Task 5: 降级项提升（可选，标注依赖）

- [ ] 记忆蒸馏升级为 LLM 提取（当前规则式）
- [ ] 语义缓存真实 embedding（当前 n-gram 哈希近似，需嵌入模型/向量库）
- [ ] 项目上下文提取 LLM 化（当前简化版）
- [ ] 调度器 llm_generate action（当前仅 log）

### Task 6: CLI 补齐

- [ ] `--load` 恢复历史会话（SessionStore.load 已存在，CLI 未暴露）
- [ ] `--timeout` 参数（当前固定超时）
