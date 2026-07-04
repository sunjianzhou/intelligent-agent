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

### TODO-84: soul/heart.md 心证永久档 + SoulLoader 加载

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

**待完成**：
- [ ] 创建 `soul/heart.md`（git-tracked，含上述四段结构 + 占位说明）
- [ ] `agent/soul/loader.py`：`SoulData` 新增 `heart: str` 字段；`OPTIONAL` 列表追加 `"heart"`；`load()` 按 `whisper` 同模式读取（不存在时 `heart=""`）
- [ ] `agent/tests/test_soul_loader.py`：追加 `test_missing_heart_is_silent`（heart.md 缺失时 `data.heart == ""`）和 `test_heart_content_loaded`（文件存在时内容正确读入）

**涉及文件**：`soul/heart.md`, `agent/soul/loader.py`, `agent/tests/test_soul_loader.py`

---

### TODO-85: SystemPromptBuilder 插入 heart 段

**目标**：在 system prompt 组装顺序中插入 heart 段，位置在 ③MEMORY 之后、④HEARTBEAT 之前。

**修改后顺序**：①SOUL → ②USER → ③MEMORY → **③.5 HEART（心证铁卷）**→ ④HEARTBEAT → ⑤persona → ⑥whisper → ⑦tool_overlay

**理由**：心证优先级高于自动蒸馏的 MEMORY（用户显式标记 > LLM 自动归并），低于 HEARTBEAT（先知道记住什么，再按铁规思考）。

**待完成**：
- [ ] `agent/core/system_prompt_builder.py`：在 ③MEMORY 段之后插入心证段 `self._wrap("【心证铁卷】", d.heart)`，非空时追加
- [ ] `agent/core/system_prompt_builder.py`：新增 `_HEART_EXCLUDED_CHANNELS = {"feishu_im", "wecom"}`，心证内容不发送到外部 IM 渠道（与 whisper 同策略）
- [ ] `agent/tests/test_system_prompt_builder.py`：追加 `test_heart_nonempty_appears` + `test_heart_empty_absent` + `test_heart_before_heartbeat_order` 三个测试

**涉及文件**：`agent/core/system_prompt_builder.py`, `agent/tests/test_system_prompt_builder.py`

---

### TODO-86: heart_record 工具（append/list/delete）

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

**待完成**：
- [ ] 创建 `agent/tools/builtin_tools/heart_record.py`
- [ ] `agent/core/tool_dispatcher.py` `_init_tools` 注册 `heart_record`（无环境变量依赖，始终注册）
- [ ] `agent/tests/test_heart_record.py`：append/list/delete 各 1 用例 + 分区归类 1 用例 + 备份 1 用例 = 5 用例

**涉及文件**：`agent/tools/builtin_tools/heart_record.py`, `agent/core/tool_dispatcher.py`, `agent/tests/test_heart_record.py`

---

### TODO-87: _detect_branch_failure() 5 信号整合

**目标**：在 `conversation_flow.py` 的 `chat()` 和 `chat_stream()` 两个 ReAct 循环中，每轮 `_execute_tool_round()` 之后调用 `_detect_branch_failure()`，检测到分支失败时自动撤回最近 2 轮 + 注入 `[BRANCH_RESET]` 系统消息 + 重新进入循环。

**5 信号**：
1. 同工具同错误 3 次（`tool_dispatcher.py` 的 `_execute_tool_round` 统计）
2. LLM 输出连续 2 轮重复 >80% 相似度（Jaccard 词级相似度，无需外部库）
3. 用户显式纠偏（"不对/重来/换个思路"，由 chat_router 预检注入 flag）
4. 5 轮内 1 次 RuntimeError + 1 次空响应 → 立即触发
5. 工具调用失败分级超限：业务错（401/403）重试 1 次、系统错（5xx/超时）重试 3 次，超限触发

**检测窗口**：最近 5 轮（`_BRANCH_FAILURE_WINDOW = 5`）

**待完成**：
- [ ] `agent/core/conversation_flow.py`：新增 `_detect_branch_failure(round_history, iteration, max_iterations) -> Optional[str]` 私有方法
- [ ] `agent/core/conversation_flow.py`：新增 `_text_similarity(a, b) -> float` 静态方法（Jaccard）
- [ ] `agent/core/conversation_flow.py`：新增 `_auto_retract_last_n_rounds(messages, n, user_id) -> None` 方法
- [ ] `agent/core/conversation_flow.py`：`chat()` L394 后插入 `_detect_branch_failure` 检测 + 撤回逻辑
- [ ] `agent/core/conversation_flow.py`：`chat_stream()` L638 后插入同逻辑
- [ ] `agent/core/conversation_flow.py`：`chat()`/`chat_stream()` 签名追加 `retract_on_failure: bool = True` 参数（默认开启，测试时可关闭）
- [ ] `agent/core/tool_dispatcher.py`：`_execute_tool_round` 内对每个工具调用加入错误分级重试（`_is_auth_error` 判定 → 业务错 1 次 / 系统错 3 次），轮次结果标记 `_retry_exhausted`
- [ ] `agent/core/tool_dispatcher.py`：新增 `_is_auth_error(exec_result) -> bool` 静态方法
- [ ] `agent/tests/test_branch_detector.py`：5 信号各 1 用例 + 集成场景 2 用例 + 边界（关闭开关）1 用例 = 8 用例

**涉及文件**：`agent/core/conversation_flow.py`, `agent/core/tool_dispatcher.py`, `agent/tests/test_branch_detector.py`

---

## W2 PWA 导航统一 + L1/L2 缓存（2026-07-07 ~ 2026-07-14）

---

### TODO-88: L1 响应缓存（prometheus 风格，5min TTL）

**目标**：在 `agent/core/` 新增 `l1_cache.py`，对高频 prompt 模板做 hash 化缓存，命中即返回不调 LLM。插入到 `agent.chat()` 的最早入口。

**缓存 key**：`sha256(prompt + model_name + persona_hash + memory_snapshot_id)[:16]`

**TTL**：5min（`settings.l1_cache_ttl_seconds = 300`）

**边界保护**：TTL 过期后自动淘汰；缓存条目上限 100 条（LRU）；命中时写 `cache_hits_total` metrics；memory 发生写入时清空当前 user 的 L1（防止脏读）

**待完成**：
- [ ] 创建 `agent/core/l1_cache.py`：`L1Cache` 类（`get(key) / set(key, response) / invalidate_user(user_id)`，线程安全 `threading.Lock`）
- [ ] `agent/config/settings.py`：追加 `l1_cache_ttl_seconds: int = 300` + `l1_cache_max_entries: int = 100`
- [ ] `agent/core/agent.py`：在 `chat()` 的 `_build_messages_async` 之前插入 L1 查询；在 `chat()` 返回前写 L1
- [ ] `agent/tests/test_l1_cache.py`：命中/未命中/TTL 过期（mock time）/LRU 淘汰/用户隔离 各 1 用例 = 5 用例
- [ ] **边界测试**：`test_l1_boundary_ttl_expired_not_hit`（5min 后同一 prompt 不命中）+ `test_l1_boundary_prompt_diff_no_false_hit`（不同 prompt 不互串）

**涉及文件**：`agent/core/l1_cache.py`, `agent/config/settings.py`, `agent/core/agent.py`, `agent/tests/test_l1_cache.py`

---

### TODO-89: L2 语义缓存（ChromaDB 风格，24h TTL）

**目标**：对短期对话上下文做语义缓存——用户问题与 24h 内已编码的历史问题向量相似度 ≥ 0.85 时，返回缓存响应。

**缓存 key**：`user_id + embedding(prompt)`，存入 ChromaDB 独立 collection `l2_semantic_cache`

**TTL**：24h（`settings.l2_cache_ttl_hours = 24`）

**相似度阈值**：0.85（`settings.l2_similarity_threshold = 0.85`）

**待完成**：
- [ ] 创建 `agent/core/l2_cache.py`：`L2SemanticCache` 类（`search(user_id, prompt_vec) / store(user_id, prompt_vec, response)`，复用 `embedding_model`）
- [ ] `agent/config/settings.py`：追加 `l2_cache_ttl_hours` + `l2_similarity_threshold`
- [ ] `agent/core/agent.py`：在 L1 未命中后、LLM 调用前插入 L2 查询；返回后写 L2
- [ ] `agent/tests/test_l2_cache.py`：语义命中/未命中/用户隔离/过期淘汰 各 1 用例 = 4 用例

**涉及文件**：`agent/core/l2_cache.py`, `agent/config/settings.py`, `agent/core/agent.py`, `agent/tests/test_l2_cache.py`

---

## W3 模型量化 + 图片补齐 + L3/L4 命中率（2026-07-14 ~ 2026-07-21）

---

### TODO-90: Ollama 量化模型拉取 + keep_alive 调优

**目标**：拉取 dolphin 量化版本，降低显存占用；调整 `keep_alive` 参数使模型常驻内存以复用 KV cache。

**量化格式**：`q4_K_M`（4-bit 量化，精度损失 < 2%，显存降低 ~60%）

**keep_alive 参数**：当前默认 `OLLAMA_KEEP_ALIVE` 为 5min，改为 `-1`（永久驻留）或 `24h`（兼顾其他模型切换）

**待完成**：
- [ ] `ollama pull dolphin:7b-q4_K_M`（或当前使用的模型对应量化版）
- [ ] `.env.docker` / `.env` 追加 `OLLAMA_KEEP_ALIVE=-1`
- [ ] 在 `agent/services/ollama_provider.py` 的 `chat()` 调用中确认 `keep_alive` 参数已透传

**涉及文件**：`.env.docker`, `.env`, `agent/services/ollama_provider.py`（只读确认）

---

### TODO-91: L3/L4 命中率监控

**目标**：在 `/api/metrics` 中暴露 ChromaDB 长期记忆检索命中率 + 蒸馏快照追溯完整性。

**L3 指标**：长期记忆检索命中率（`retrieve()` 返回非空结果的比例）、平均相似度分数、检索耗时 p50/p99

**L4 指标**：蒸馏源追溯覆盖率（有 `source_message_ids` 的长期记忆条目占比）、快照备份数

**待完成**：
- [ ] `agent/api/metrics.py`：新增 `l3_retrieve_hits / l3_retrieve_total / l3_avg_similarity / l3_retrieve_ms_p50 / l3_retrieve_ms_p99`
- [ ] `agent/memory/long_term.py`：`retrieve()` 中埋点统计命中率
- [ ] `agent/memory/distiller.py`：蒸馏完成后统计 `source_message_ids` 覆盖率
- [ ] `agent/tests/test_metrics.py`：L3 指标端点可返回数据（1 用例）

**涉及文件**：`agent/api/metrics.py`, `agent/memory/long_term.py`, `agent/memory/distiller.py`, `agent/tests/test_metrics.py`

---

## W4 全量回归 + 迁移验证首跑 + 文档同步（2026-07-21 ~ 2026-07-28）

---

### TODO-92: 迁移验证检查表首跑 + 文档同步

**目标**：用户在府邸对话框中触发 `@verify migration-readiness`，跑首次 3 维 7 项检查表，记录打分；四个根目录 MD 同步至 2026-07-28。

**迁移验证检查表（每 2 周 1 次）**：

| 维度 | 检查项 | 打分（0-100） |
|------|--------|--------------|
| 对话体验 | 响应速度：府邸与飞书同 prompt 回复耗时差异 < 20% | |
| | 上下文深度：10 轮连续对话后能准确引用前 3 轮细节 | |
| | 工具调用成功率 ≥ 飞书（相同任务集） | |
| 记忆持久化 | 无丢失：5 条跨会话测试记忆 24h 后全部可检索 | |
| | 无错位：蒸馏后不张冠李戴 | |
| 心证管理 | heart.md 同步率：飞书端心证 5 条全在府邸 heart.md 中 | |
| | 府邸能准确复述 3 条心证铁卷 | |

**三项全部 100% 的那天 = 迁移日。**

**待完成**：
- [ ] 在 `@verify migration-readiness` 触发后 Agent 按检查表逐项提问用户打分，记录到 `soul/heart.md` 底部 `## 迁移验证记录` 段
- [ ] 全量测试回归：`pytest tests/ -v`（含 W1/W2 新增 ~23 用例）全绿
- [ ] `mvn test` BUILD SUCCESS；`npm run test` 全绿
- [ ] `README.md` / `TODOS.md` / `CLAUDE.md` / `AI_PROJECT_CONTEXT.md` 全部日期更新至 2026-07-28，内容一致
- [ ] `docs/superpowers/plans/2026-07-01-heart-record.md` 归档标记完成

**涉及文件**：四个根目录 MD、`docs/superpowers/plans/2026-07-01-heart-record.md`

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

## TODO-95: 跨 session 记忆继承增强（LTM 任务进度感知）

**背景**：LTM (ChromaDB) 已有，`MemoryDistiller` 会自动蒸馏对话中的 facts。但它蒸馏出来的是零散知识点（"霖君喜欢 xxx"），不包含"上次任务做到第几步了"的状态信息。跨 session 恢复任务时，LTM 检索无法提供进度上下文。这是原始文档的 #6（P1）能力。

**目标**：让 LTM 能感知并检索任务进度。不是替代 progress_state.md（TODO-94），而是在蒸馏时识别 schedule/task 类消息，打上 `task_progress` 标签，使 task 恢复场景的检索命中率更高。

**待完成**：
- [ ] `agent/memory/distiller.py`：`_extract_facts()` 新增进度感知规则——若消息含 `[TASK_DONE]`/`[TASK_BLOCKED]`/`progress_state`/`scheduler` 关键词，自动打 `meta: {type: "task_progress"}` 标签
- [ ] `agent/memory/long_term.py`：`retrieve()` 新增 `type_filter` 参数，允许只检索 `task_progress` 类记忆
- [ ] `ConversationFlowMixin._build_messages_async()`：在注入 LTM 记忆时，新增 task 场景判断——若检测到 progress 恢复信号（TODO-94），额外查询 `type=task_progress` 的记忆
- [ ] `agent/tests/test_memory_task_progress.py`：蒸馏识别标签 / 检索 filter 各 1 用例 = 2 用例

**涉及文件**：`agent/memory/distiller.py`, `agent/memory/long_term.py`, `agent/core/conversation_flow.py`

**依赖**：TODO-94 落地后再启动此项（蒸馏标签的设计需配合 progress_state.md 的标准格式）。

---

### 排期总览

```
W1 (7/01-7/07): TODO-84~87  ✅ 已完成（2026-07-02） 心证层 + 5 信号自动撤回触发器
W2 (7/07-7/14): TODO-PWA-1 + TODO-88~89  ✅ 已完成（2026-07-02） PWA 导航统一 + L1/L2 缓存
W3 (7/14-7/21): TODO-90~91  ✅ 已完成（2026-07-02） 模型量化 + L3/L4 命中率
W4 (7/21-7/28): TODO-92  ✅ 已完成（2026-07-02） 全量回归 + 迁移验证首跑 + 文档同步
```

> **2026-07-02 更新**：heart-record plan 提前全部落地。W1-W4 在一个会话中连续完成，新增 52 个测试（32 heart/branch + 8 L1 + 2 L3/L4 + 10 已有覆盖），350/354 通过。

```
W5 (7/03-7/10): TODO-93 ✅ + TODO-94 ✅  失职自查钩子 + 进度恢复协议（均已完成 2026-07-04）
W6 (7/10-7/17): TODO-95     跨 session 记忆继承增强（依赖 TODO-94）
```

> **2026-07-03 新增**：TODO-93~95 来自"糖糖失职问题"分析，承接 HEARTBEAT 能力边界自检 + heart.md 铁律。TODO-95 依赖 TODO-94 的 progress_state 标准格式。

---
