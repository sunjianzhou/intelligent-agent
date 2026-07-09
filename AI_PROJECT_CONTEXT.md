# 智能体项目 — AI 上下文速查文档

> **本文档专为大模型阅读设计**。新对话开始时先读此文件，5 分钟内建立完整项目认知，无需再反复询问基础背景。
> 最后更新：2026-07-09（W1-W12 全部落地：主人永久铁律 + Channel Adapter 抽象层 + 双通道并行广播 + 可观测性）

---

## 一、整体架构（三层 + 公网接入 + IM 渠道）

```
浏览器（PWA）/ CLI
    │
企业微信 / 飞书 / Telegram（IM 渠道）
    │  HTTPS 回调 / WS 长连接
    │
    ▼
[公网入口] intelligent.eu.cc（Cloudflare Tunnel → ia-frontend:80）
    │
    │  前端 Nginx 路由：
    │  /         → Vue SPA（PWA 可安装）
    │  /api/*    → proxy → backend:8080
    │  /ws       → proxy → backend:8080（WebSocket Upgrade）
    │  /wecom/*  → proxy → backend:8080（企业微信回调）
    │  /feishu/* → proxy → backend:8080（飞书回调）
    ▼
Java 后端 (Spring Boot, port 8080)   ← 纯网关，无 AI 逻辑
    │                                   含 ChannelAdapterManager（FeishuChannelAdapter + broadcast）
    │                                   含 WeComCallbackController、FeishuWebSocketClient
    │  HTTP + SSE
    ▼
Python Agent (FastAPI, port 8000)    ← 所有 AI 逻辑在此
    │
    ├── Channel Adapter 层             ← 4 channel 统一接口（飞书/企微/Web/Telegram）
    │   ├── ChannelAdapter（ABC）      ← send_text/card/file/image + TokenBucket + RetryConfig + ChannelMetric
    │   ├── FeishuAdapter             ← 按操作独立限流（text 50/s, card 1.67/s），Session 连接池
    │   ├── WeComAdapter              ← 限流 1.67/s，4KB card 截断
    │   ├── WebAdapter                ← WS 推送，无限流
    │   ├── TelegramAdapter           ← 限流 30/s，Inline Keyboard card
    │   ├── ChannelRouter             ← 单通道/广播/去重/fallback/指标聚合，全局单例
    │   ├── ChannelAdapterFactory     ← 自动发现 4 adapter
    │   ├── ChannelMessageTool        ← LLM 统一 IM 工具（替代 FeishuIMTool）
    │   └── ChannelNotifier           ← 整合通知系统 + GET /health/channels 可观测性
    │
    ├── Ollama (port 11434)           ← 本地 LLM 推理（--profile local，默认模型 dolphin:latest）
    ├── 云端 LLM（按需在 /admin/models 激活，不作全局默认）
    └── ChromaDB (进程内)            ← 向量存储（具名 Docker 卷）
```

**Docker 容器名**：
- 核心：`ia-frontend`(3000) / `ia-backend`(8080) / `ia-agent`(8000)
- 可选：`ia-ollama`(11434, `--profile local`) / `ia-comfyui`(8188, `--profile local`) / `ia-cloudflared`(`--profile tunnel`)

**profile 组合**：
| 命令 | 启动内容 |
|------|---------|
| `docker compose up -d` | 核心三件套（无公网隧道） |
| `docker compose --profile tunnel up -d` | + cloudflared（公网 + IM 回调） |
| `docker compose --profile local up -d` | + ollama + comfyui（本地推理 + 图片生成） |
| `docker compose --profile local --profile tunnel up -d` | 全量 |

**前端热更新命令**（不需要重建镜像，~10秒）：
```bash
npm run build   # 在 frontend/ 下
docker cp frontend/dist ia-frontend:/usr/share/nginx/html_new
docker exec ia-frontend sh -c "rm -rf /usr/share/nginx/html_old && mv /usr/share/nginx/html /usr/share/nginx/html_old && mv /usr/share/nginx/html_new /usr/share/nginx/html && nginx -s reload"
```

**Python Agent 重建**（修改 .py 文件时）：
```bash
docker compose build agent && docker compose up -d agent
```

---

## 二、模块目录

```
intelligent_agent/
├── agent/          Python FastAPI 服务（AI 核心）
├── backend/web/    Spring Boot 网关
├── frontend/       Vue 3 SPA
├── client/         Python CLI 客户端（直连 agent，不经 Java）
├── tests/e2e/      端到端测试（63 个用例，pytest + httpx）
├── docker-compose.yml
├── CLAUDE.md       Claude Code 项目指令
├── TODOS.md        待办事项（含已完成标记）
└── AI_PROJECT_CONTEXT.md  ← 本文件
```

---

## 三、Python Agent 详解（`agent/`）

### 3.1 入口与启动

- 入口：`api/fastapi_app.py`（335行，已从1876行 God Module 拆分）
- 启动：`uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000`
- 本地开发使用 conda 环境 `python310`

### 3.2 核心组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `IntelligentAgent` | `core/agent.py` | 薄门面（~320行），继承三个 Mixin |
| `ConversationFlowMixin` | `core/conversation_flow.py` | 消息构建/chat/stream（~460行） |
| `ToolDispatcherMixin` | `core/tool_dispatcher.py` | 工具注册/意图/LLM调用（~1130行） |
| `MemoryWriterMixin` | `core/memory_writer.py` | 预热/MCP/蒸馏/清理（~310行） |
| `_context_vars` | `core/_context_vars.py` | 共享 ContextVar（避免循环导入） |
| `OllamaProvider` | `services/ollama_provider.py` | LLM 推理，原生 Function Calling + text-tool 两种模式，支持 images 字段（多模态） |
| `OpenAIProvider` | `services/openai_provider.py` | 云端 LLM（DashScope/DeepSeek/ZhipuAI/Moonshot 等） |
| `MemoryManager` | `memory/manager.py` | 路由短/长期记忆 |
| `ShortTermMemory` | `memory/short_term.py` | 进程内双端队列，TTL 24h，max 100 条 |
| `LongTermMemory` | `memory/long_term.py` | ChromaDB 向量库，embedding: all-MiniLM-L6-v2 |
| `MemoryDistiller` | `memory/distiller.py` | 每 5 轮提炼事实；进度关键词自动打 `task_progress` 标签（TODO-95） |
| `SemanticCache` | `memory/semantic_cache.py` | L2 语义响应缓存（余弦相似度 ≥ 0.92 命中） |
| `ContextExtractor` | `memory/context_extractor.py` | 按 project_id 提取项目上下文 nugget |
| `ToolManager` | `tools/tool_manager.py` | per-agent 独立实例（非全局单例） |
| `SimpleTaskScheduler` | `scheduler/simple_scheduler.py` | 后台线程，2s 轮询，支持 interval/delay/datetime/cron |
| `SkillManager` | `skills/manager.py` | 技能路由（意图 → 最优工具集注入 prompt） |
| `PromptBuilder` | `personas/prompt_builder.py` | 单例，构建 System Prompt（角色 + 工具指令） |
| `RoleManager` | `personas/role_manager.py` | 角色 CRUD + 持久化（data/roles.json） |

### 3.3 ReAct 推理流程

```
_build_messages_async()
    注入：短期记忆 + 长期语义检索 + 项目上下文 + 任务列表 + Spec（每10轮）
          + 【心证铁卷】+ 【主人铁律】（隐私分层：public/private/secret + token退化）
    │
    ▼
_call_model_with_tools()  ← 第一次 LLM 调用
    │
    ├── 有工具调用 → _execute_tool_round() → 追加结果 → 循环（max 5次）
    │                    │
    │                    └── _detect_branch_failure() 6信号检测
    │                         ├── 信号1-5：同工具错误/重复输出/用户纠偏/RuntimeError/重试耗尽
    │                         └── 信号6：铁律违反扫描（rm -rf/os.system/eval/DROP TABLE等15个模式）
    │                         命中 → 撤回2轮 + [BRANCH_RESET] → 继续循环
    │
    └── 无工具调用 → _stream_tokens_async()  ← SSE 流式输出
```

**Text-tool 模式**（dolphin/phi2/orca-mini/orca2）：这些模型不支持 Ollama 原生 Function Calling，改用文本解析，支持 4 种格式：JSON、`<tool_call>` 标签、Markdown 代码块、Plain text。

### 3.4 多用户隔离机制

| 层 | 机制 |
|----|------|
| Provider（模型） | `_request_provider_ctx` ContextVar，per-asyncio-Task |
| Persona（角色） | `_request_persona_ctx` ContextVar，per-asyncio-Task |
| ToolManager | per-IntelligentAgent 独立实例 |
| 项目上下文 | turn counter 键为 `{user_id}:{project_id}` |
| 用户 ID 透传 | Java 从请求 JWT 提取 `user_id`，通过 `X-User-Id` 头透传到 Python |

> ✅ 真实用户 ID 透传已完成（2026-06-02），Python `jwt_auth_middleware` 优先读 `X-User-Id`（当 JWT sub 是 java-service 时）。

### 3.5 内置工具（`tools/builtin_tools/`）

| 工具 | 文件 | 说明 |
|------|------|------|
| CalculatorTool | `calculator.py` | 数学计算（eval 沙箱） |
| TimeTool | `time_tool.py` | 时间查询 |
| FileTool | `file_tool.py` | 文件读写（受 filesystem_allowed_dirs 限制；`soul/MEMORY.md` 白名单） |
| WebSearchTool | `web_search.py` | DuckDuckGo 搜索 |
| ShellTool | `shell_tool.py` | Shell 命令（受目录白名单限制） |
| DatabaseTool | `database/` | MySQL 查询 |
| ImageGenerationTool | `image_tool.py` | 图片生成（ComfyUI/SD WebUI/diffusers/SiliconFlow 四种 Provider） |
| feishu_calendar_list | `feishu_calendar.py` | 日历查询（tenant 身份，支持 user_access_token 优先） |
| feishu_task_list | `feishu_task.py` | 待办查询（tenant 身份，支持 user_access_token 优先） |
| feishu_calendar_create | `feishu_calendar_create.py` | 创建日历事件（user_access_token，OAuth 授权必需） |
| feishu_task_write | `feishu_task_write.py` | 创建/完成任务（user_access_token，OAuth 授权必需） |
| im_message | `im/feishu_client.py`（`FeishuIMTool`） | 发送飞书 IM 消息（tenant access token，bot 身份；旧版，已委托 FeishuAdapter） |
| channel_message | `im/channel_message_tool.py` | LLM 统一 IM 工具，通过 ChannelRouter 路由到飞书/企微/Web/Telegram（替代 FeishuIMTool） |

另有通过 `FunctionTool` 动态注册的工具：`store_memory`、`search_memories`、`create_reminder`、`create_periodic_reminder` 等。

**Feishu OAuth**（2026-06-27）：新增 `agent/services/feishu_oauth.py` Token Manager 和 `agent/api/feishu_oauth_router.py` 端点支持用户 OAuth 授权，获取 `user_access_token` 访问个人日历/待办。

**知识库**（`api/knowledge_router.py`）：独立 FastAPI 路由，上传 .txt/.md/.pdf/.json（≤10MB），按段落/句子边界分块后写入 ChromaDB 独立集合（`knowledge_{user_id}`）。每次 `_build_messages_async()` 时语义检索注入 `[KNOWLEDGE]` 区块。

**多模态输入**：`chat_router.py` 接收 `image_base64` 字段，`conversation_flow.py` 在构建 LLM 消息时附加 `images` 列表，OllamaProvider 将其透传给 Ollama API（llava / qwen-vl 等模型可直接理解图片内容）。

### 3.6 任务调度系统

- **动作类型**：`log`、`llm_generate`（调用 LLM 生成内容）、任意工具名
- **调度类型**：`immediate`、`delay`（N秒后一次）、`interval`（每N秒循环）、`datetime`（指定时间）、`cron`（表达式）
- **LLM 生成任务**：结果 push 到通知队列，Java `@Scheduled(5000ms)` 主动推送 `notification` WS 事件到前端
- **持久化**：任务状态保存到 `data/tasks.json`，重启恢复

### 3.7 角色（Persona）系统

- **角色存储**：`data/roles.json`（完整结构化 JSON），无需重启热加载
- **内置角色**：默认助手、创意写手、技术专家
- **激活持久化**：`data/user_persona_prefs.json`（按 user_id），重启后自动恢复上次激活角色
- **Prompt 构建**：`PromptBuilder`（单例）—— 角色描述 → 叠加模型覆盖层（dolphin 专用无限制覆盖）→ 追加工具指令（text-tool 模式）
- **前端编辑器**：`/roles/editor` 六标签全字段表单，支持实时 Markdown 提示预览

### 3.8 项目系统（Project）

- 每个项目有：名称、规格文档（Spec）、任务树（TaskTree）
- Spec 存入 ChromaDB（每项目独立集合 `project_{id}_context`）
- ContextExtractor 每 8 轮对话提取 nugget 写入该集合
- 下次聊天注入 `[PROJECT CONTEXT]` 和 `[SPEC]` 区块（Spec 每 10 轮注入一次）
- `[TASK_DONE:<task_id>]` sentinel：LLM 回复中检测到后 yield `task_update`，前端更新任务树状态

### 3.9 关键配置（`config/settings.py`）

| 参数 | 默认 | 说明 |
|------|------|------|
| `ollama_model` | `qwen2.5:7b` | 全局默认模型 |
| `chat_timeout` | 300s | 单次推理超时 |
| `max_context_tokens` | 7000 | 上下文 token 预算 |
| `inference_concurrency` | 3（Docker 设为1） | 并发推理上限 |
| `memory_distill_interval` | 5 | 每 N 轮触发事实提炼 |
| `memory_summary_interval` | 10 | 每 N 轮生成阶段摘要 |
| `semantic_cache_threshold` | 0.92 | L2 语义缓存命中阈值 |
| `jwt_secret` | 必填 | 与 Java 和 client 保持一致 |
| `cloud_provider/model` | 空 | 配置后启用云端 LLM fallback |
| `image_gen_provider` | `siliconflow` | 图片生成提供商 |
| `feishu_app_id` | 空 | 飞书应用 App ID（环境变量 `FEISHU_APP_ID`） |
| `feishu_app_secret` | 空 | 飞书应用 App Secret（环境变量 `FEISHU_APP_SECRET`） |
| `feishu_oauth_redirect_uri` | 空 | OAuth 回调地址（需公网可达） |
| `feishu_oauth_encryption_key` | 空 | user_access_token 加密密钥（Fernet 格式） |

所有参数可通过 Web UI（`/admin/mcp` → 系统资源配置）动态修改，写入 `data/runtime_config.json`，重启后自动恢复。

### 3.10 Channel Adapter 抽象层（`agent/im/`）

统一的 IM Channel 抽象层，4 channel（飞书/企微/Web/Telegram）走统一接口，支持多通道并行广播和可观测性。

**核心组件**：

| 组件 | 文件 | 说明 |
|------|------|------|
| `ChannelAdapter`（ABC） | `im/channel_adapter.py` | 统一接口：`send_text`/`send_card`/`send_file`/`send_image`，内置 `TokenBucket` 限流 + `RetryConfig` 指数退避重试 + `ChannelMetric` 指标 + HTTP Session 连接池复用 |
| `FeishuAdapter` | `im/adapters/feishu_adapter.py` | 飞书适配器：按操作独立限流（text 50/s, card 1.67/s, image 10/s），card 30KB 截断，TODO-93 失职自查钩子 |
| `WeComAdapter` | `im/adapters/wecom_adapter.py` | 企业微信适配器：限流 1.67/s，card 4KB 截断 |
| `WebAdapter` | `im/adapters/web_adapter.py` | Web 适配器：WS 推送，无限流无重试，始终可用（fallback 目标） |
| `TelegramAdapter` | `im/adapters/telegram_adapter.py` | Telegram 适配器：限流 30/s，Inline Keyboard card |
| `ChannelRouter` | `im/channel_router.py` | 多通道路由器：单通道发送（`send_to`）、多通道并行广播（`broadcast_text`，asyncio.gather + 失败隔离）、去重（dedup_key）、fallback 降级到 Web（`send_with_fallback`）、全局单例（`_get_global_router`） |
| `ChannelAdapterFactory` | `im/adapter_factory.py` | 按 `ChannelType` 自动发现并创建 adapter |
| `ChannelMessageTool` | `im/channel_message_tool.py` | LLM 统一 IM 工具（替代旧 `FeishuIMTool`），通过 ChannelRouter 路由到任意 channel |
| `ChannelNotifier` | `im/channel_notifier.py` | 整合 ChannelRouter 到通知系统（`notify_user`/`notify_user_sync`） |

**数据模型**（`channel_adapter.py`）：
- `ChannelType`：枚举（FEISHU / WECOM / WEB / TELEGRAM / CLI）
- `MessageType`：枚举（TEXT / CARD / IMAGE / FILE）
- `MessageStatus`：生命周期枚举（PENDING → SENT → DELIVERED → READ / FAILED）
- `ChannelMessage`：跨 channel 统一消息模型（含 dedup_key、归一化 message_id）
- `SendResult`：发送操作统一结果（success / message_id / error / latency_ms）
- `UserInfo`：channel-agnostic 用户信息
- `RetryConfig`：指数退避重试配置（max_retries / base_delay / backoff_multiplier）
- `TokenBucket`：令牌桶限流器（线程安全，rate + burst）
- `ChannelMetric`：单 channel 发送指标（attempts / successes / failures / retries / rate_limit_hits / latency）

**Java 侧对应**（`backend/web/im/`，10 个文件）：
- `ChannelAdapter`（interface）+ `FeishuChannelAdapter`（委托 `FeishuMessageSender`）
- `ChannelAdapterManager` — Spring Bean，管理 adapter 注册 + `broadcast()` 并行广播
- 数据模型与 Python 侧一一对应

**可观测性**：`GET /health/channels` 返回各 channel 的 `ChannelMetric`（成功率/平均延迟/限流拒绝次数），用于生产监控。

---

## 四、Java 后端详解（`backend/web/`）

### 4.1 职责

纯粹的 WebSocket 网关 + HTTP 反向代理，不含任何 AI 业务逻辑。

### 4.2 控制器

| 控制器 | 路由前缀 | 说明 |
|--------|---------|------|
| `WebSocketController` | `/ws` | WS 连接，处理 chat_message/ping/get_system_info |
| `ChatController` | `/api/chat` | 同步 REST 聊天（飞书/直接 API 调用） |
| `HealthController` | `/api/health`, `/api/models`, `/api/config/*` | 健康检查、模型管理、运行时配置 |
| `AuthController` | `/api/auth/*` | 登录、token 刷新 |
| `PersonaProxyController` | `/api/personas/*` | 旧版角色代理（保留兼容） |
| `RoleController` | `/api/roles/*` | 新版角色完整 CRUD 代理 |
| `ConversationsProxyController` | `/api/conversations/*` | 历史会话代理 |
| `MemoryProxyController` | `/api/memory/*` | 记忆 CRUD 代理 |
| `TaskProxyController` | `/api/tasks/*` | 任务调度代理 |
| `ToolProxyController` | `/api/tools/*` | 工具管理代理 |
| `SkillProxyController` | `/api/skills/*` | 技能管理代理 |
| `ProjectProxyController` | `/api/project/*` | 项目规格/上下文代理 |
| `CloudProxyController` | `/api/cloud/*` | 云端服务商 CRUD + 激活切换代理 |
| `AnalyticsProxyController` | `/api/analytics/*` | 统计分析代理 |
| `ImageProxyController` | `/api/images/*` | 图片文件代理 |
| `FeishuOAuthController` | `/feishu/oauth/*` | 飞书 OAuth 三端点透传（authorize / callback / status） |
| `WeComCallbackController` | `/wecom/callback` | 企业微信 URL 验证（GET）+ 消息接收（POST）；异步处理，立即返回 200 |
| `ChannelAdapterManager` | —（Spring Bean） | IM Channel 适配器管理：注册 FeishuChannelAdapter + `broadcast()` 并行广播 |
| `SpaController` | `/**` | Vue Router history mode 兜底 |

所有代理 Controller 均继承 `AbstractProxyController`，统一 `proxy.get/post/put/delete/patch(path, userId)` 调用。`proxyGetRaw()` 方法返回原始 String（供 HTML 响应端点，如 OAuth callback 使用）。

**企业微信相关类**：
- `WeComConfig`：读取 `WECOM_*` 环境变量（corpId / agentId / secret / token / aesKey）
- `WeComCrypto`：SHA1 签名验证 + AES-CBC 消息解密 + 加密（PKCS#7 block=32）
- `WeComMessageSender`：调用微信 API 发送消息（维护 access_token，自动刷新）
- `AgentService.chatFull()`：通过 `X-User-Id` 头将真实用户 ID（`wecom:SunJianZhou`、`feishu:ou_xxx`）透传到 Python Agent；2026-06-28 修复前此头缺失，导致 IM 消息以 `java-service` 身份处理（影响模型选择、记忆隔离）

### 4.3 WebSocket 消息类型

**前端 → Java**：`chat_message`、`ping`、`get_system_info`

**Java → 前端**：`connection_established`、`thinking`、`chat_token`、`tool_call_start`、`tool_calls_done`、`chat_done`、`task_update`、`task_blocked`、`notification`、`pong`、`system_info`、`error`

### 4.4 通知推送

Java `AgentService` 中有 `@Scheduled(fixedDelay=5000)` 方法，每 5 秒轮询 Python `/api/notifications/poll`，有内容时主动广播 `notification` WS 事件到所有在线前端连接。

### 4.5 JWT

- 前端用户 token：有效期 24h，活跃用户通过 `X-New-Token` 响应头自动滑动续期
- 服务间 token：Java → Python 用 `sub="java-service"` 的固定 token（临近过期自动刷新）
- WS 握手：`JwtHandshakeInterceptor` 在握手阶段验证 query string token，无效直接 401
- `JwtAuthFilter` 白名单精确化（2026-06-27）：从宽泛 `/feishu/` 改为三条精确路径：`/feishu/event`、`/feishu/callback/interactive`、`/feishu/oauth/callback`

---

## 五、前端详解（`frontend/`）

### 5.1 技术栈

Vue 3 + Pinia + Vue Router 4 + Element Plus + Font Awesome 6 + marked + DOMPurify + highlight.js + Vite + PWA

### 5.2 页面与路由

| 路径 | 视图 | 说明 |
|------|------|------|
| `/chat` | ChatView | 主聊天页，流式渲染，工具卡片，历史会话侧边栏，图片附件/粘贴多模态输入 |
| `/roles/editor` | RoleEditorView | 六标签角色配置表单（保存/激活/删除） |
| `/memory` | MemoryView | 短期/长期记忆，搜索，导出 |
| `/knowledge` | KnowledgeView | 知识库管理：拖拽上传、分块统计、文件列表（含描述/大小/创建时间）、删除 |
| `/project` | ProjectView | 三栏：项目列表 / SpecEditor / TaskTree |
| `/image` | ImageView | 图片生成：Prompt/风格预设/尺寸/步数/CFG 参数面板；Provider 状态徽章；生成结果 + 历史 Gallery |
| `/admin/tools` | ToolsView | 工具列表（按分类过滤），跳转链接至 MCP 配置 |
| `/admin/skills` | SkillView | 技能管理（MD 导入） |
| `/admin/mcp` | MCPView | 工具 API Key + 推理参数 + 系统资源配置（三个卡片） |
| `/admin/models` | ModelView | 当前激活模型、云端服务商 CRUD、本地模型列表 |
| `/admin/tasks` | TasksView | 任务调度 CRUD |
| `/admin/logs` | LogView | 操作日志时间线（用户/AI/工具/任务/错误颜色区分） |
| `/admin/system` | SystemView | CPU/RAM/GPU/磁盘实时监控，资源用量 bars |
| `/admin/stats` | StatsView | 满意度/响应时间/工具排名统计 |

**路由导航单一来源**：`src/config/routes.config.js`，导出 `NAV_ITEMS`（常用区）/ `CONFIG_ITEMS`（配置区）/ `SYSTEM_ITEMS`（系统区），Sidebar 和 Header 均从此读取，新增页面只改一个文件。

### 5.3 导航结构

**桌面侧边栏（三分区）**：
- 常用：聊天 / 角色配置 / 记忆 / 项目
- 配置：工具管理 / Skill 管理 / MCP 配置
- 系统：模型管理 / 任务管理 / 操作日志 / 统计分析 / 系统信息

**移动端（≤768px）**：底部 4-Tab Bar 固定导航（聊天 / 角色 / 记忆 / 更多），"更多"弹出 MorePanel（三分组：常用 / AI 能力 / 运维与系统 + 退出登录）；聊天页 config-bar 隐藏，改为角色/模型徽章（点击弹出 RoleModelSheet 底部抽屉）；iOS safe-area / dvh / keyboard-height 已适配（iPhone 16 PWA）。

### 5.4 状态管理（Pinia）

| Store | 文件 | 核心状态 |
|-------|------|---------|
| `useWebSocketStore` | `stores/websocket.js` | WS连接、消息流、模型/角色、通知处理 |
| `useAuthStore` | `stores/auth.js` | JWT token、用户名 |
| `useLocalSessionStore` | `stores/localSession.js` | 会话历史（IndexedDB v2） |
| `useProjectStore` | `stores/project.js` | 项目数据（localStorage） |
| `useConfirmDialogStore` | `stores/confirmDialog.js` | 统一二次确认弹窗（禁止 window.confirm） |

### 5.5 关键前端功能

- **流式渲染**：Markdown 增量解析，完成后整体重渲染（避免逐 token 重排版损耗）
- **取消流式**：`cancelStreaming()` 断开 WS 重连，Java SSE 流随之终止
- **工具进度**：`activeToolSteps` 实时显示运行中工具名+参数摘要
- **通知**：`handleMessage` 处理 `notification` WS 事件，任务通知以 AI 气泡形式推入聊天，带跳转链接
- **深色模式**：`data-theme="dark"` 持久化，App.vue 全局 CSS 覆盖
- **聊天持久化**：最近 50 条 localStorage，会话历史 IndexedDB 最近 12 条
- **PWA**：可安装到桌面，Service Worker 三级缓存策略
- **二次确认**：统一用 `useConfirmDialogStore`，禁止 `window.confirm/alert`（被浏览器静默拦截）

---

## 六、CLI 客户端（`client/`）

直连 Python Agent（port 8000），不经 Java 后端，无 WebSocket。

| 文件 | 说明 |
|------|------|
| `main.py` | CLI 入口（argparse，单次问答 / REPL 两种模式；`--model`/`--persona` 启动时直接指定） |
| `api.py` | AgentClient（HTTP + SSE + JWT 自动续签；含 `get_personas()`/`switch_persona()` 角色 API） |
| `session.py` | ChatSession（内存 + JSON 持久化到 `datas/`，记录当前 model/persona） |
| `repl.py` | 交互式 REPL（Rich 可选，支持 `!models`/`!model`/`!personas`/`!persona`/`!history`/`!sessions`/`!clear` 命令） |
| `config.yaml` | 服务器地址、jwt_secret、用户名、超时 |

**角色支持**：CLI 完整支持角色切换，`!personas` 列出所有可用角色（当前激活角色标星号），`!persona <name>` 立即切换，等价于 Web 端角色选择器。`main.py --persona <name>` 可在进入 REPL 前预设角色。

---

## 七、已知问题 & 技术债

### Python Agent

| 编号 | 问题 | 优先级 |
|------|------|--------|
| D-01 | ✅ Java 用户 ID 透传已修复（2026-06-02） | — |
| D-02 | ChromaDB `seq_id` schema mismatch（已防御 try/except；迁移脚本见 `agent/tools/migrate_chromadb.py`） | 低 |
| D-03 | `_TEXT_TOOL_CALLING_PATTERNS` 硬编码，新模型需改源码 | 低 |
| D-04 | ✅ L1 缓存 key 已包含 persona 维度 | — |
| D-05 | `asyncio.ensure_future` 在模块级别调用，依赖 uvicorn 复用事件循环 | 低 |
| TODO-60 | ✅ 多模态图片持久化到对话历史（2026-06-16） | — |
| TODO-62 | ✅ diffusers `_progress_state` 加锁，无锁并发问题修复（2026-06-16） | — |
| TODO-63 | ✅ `knowledge_router` 上传日志去除物理路径（2026-06-16） | — |
| TODO-64 | ✅ 多模态图片前缀提取为 `MULTIMODAL_IMAGE_PREFIX` 常量（早期实现） | — |
| TODO-65 | ✅ diffusers 裸 `except` 补 `exc_info=True`（2026-06-16） | — |
| TODO-66 | ✅ `project_id` 写入对话历史 metadata（2026-06-16） | — |
| TODO-71 | ✅ `knowledge_router` 文件大小优先检查 + 状态码 413（2026-06-16） | — |
| TODO-74 | ✅ `knowledge_router` 段落/句子感知智能分块 + overlap（2026-06-16） | — |
| TODO-75 | ✅ 请求 traceID 全链路：`ChatRequest.request_id` + 前端 `crypto.randomUUID()`（2026-06-16） | — |

### Java 后端

| 编号 | 问题 | 优先级 |
|------|------|--------|
| J-01 | ✅ 用户 ID 透传已实现（2026-06-02） | — |
| J-02 | `PythonProxyService` 和 `AgentService` 各维护独立 serviceToken，重复逻辑 | 低 |
| J-03 | `CloseableHttpClient.createDefault()` 无连接池配置，高并发下可能连接耗尽 | 低 |
| J-04 | ✅ WS 握手 JWT 验证已实现（`JwtHandshakeInterceptor`） | — |
| TODO-85 | ✅ 飞书个人日历/任务 OAuth 授权全栈（2026-06-27）：`feishu_oauth.py` + `feishu_oauth_router.py` + `FeishuOAuthController.java` | — |

### 前端

| 编号 | 问题 | 优先级 |
|------|------|--------|
| F-02 | 角色文件标题与"角色设定"功能名歧义 | 低 |
| F-04 | 会话历史标题更新依赖异步 `_persist()`，Sidebar 可能延迟刷新 | 低 |
| F-05 | `api.js` 中 `switchModel` 动态 import 与静态 import 混用 | 低 |
| TODO-61 | ✅ 前端历史会话加载恢复图片数据（2026-06-16） | — |
| TODO-67 | ✅ `api.js` 全局请求超时（AbortController + 30s，早期实现） | — |
| TODO-68 | ✅ `websocket.js` console.log 全部改为 warn/error（早期实现） | — |
| TODO-69 | ✅ 反馈提交失败有 `ElMessage.error` 提示（早期实现） | — |
| TODO-70 | ✅ 附图按钮上传中 `isReadingImage` disabled 绑定（早期实现） | — |
| TODO-72 | ✅ `uploadKnowledgeFile` 改用通用 `request()`，60s 超时（2026-06-16） | — |
| TODO-73 | ✅ 分支对话 `branchFromMessage` 补复制 `imagePreview`/`images_b64`（2026-06-16） | — |

### 基础设施

| 编号 | 问题 | 优先级 |
|------|------|--------|
| I-01 | ✅ HTTPS/TLS 配置已提供（`nginx/` 目录 + `--profile https`），本地开发暂不影响 | — |

---

## 八、路线图（已全部实现）

以下均已完成：

| 项目 | 完成时间 |
|------|---------|
| 真实用户 ID 透传（D-01/J-01） | 2026-06-02 |
| HTTPS/TLS Nginx 配置 | 2026-06-02 |
| 任务通知 Java `@Scheduled` 主动推送 | 2026-06-03 |
| ChatView 大消息性能优化（82% bundle 减小） | 2026-06-03 |
| 项目 `[TASK_DONE]` 全链路 | 2026-06-03 |
| 角色系统全栈（Python CRUD + Java 代理 + Vue 六标签编辑器） | 2026-06-09 |
| 角色激活持久化 + PromptBuilder 单例化 | 2026-06-10 |
| ChromaDB Docker 具名卷迁移 | 2026-06-09 |
| IntelligentAgent God Class 拆分（4 个文件 Mixin） | 2026-06-11 |
| 历史对话侧边栏 | 2026-06-11 |
| 全局导航重构（routes.config.js 单源） | 2026-06-13 |
| 云端服务商 CRUD 全栈（ModelView） | 2026-06-13 |
| MCP 配置页（API Key / 推理参数 / 系统资源配置） | 2026-06-13 |
| 操作日志页（LogView） | 2026-06-14 |
| SystemView 重构（移除重复面板） | 2026-06-14 |
| 全局 UX 优化 12 项 | 2026-06-14 |
| 知识库 UI 全栈（KnowledgeView + knowledge_router） | 2026-06-15 |
| 多模态聊天输入（图片附件/粘贴，全链路 base64 透传） | 2026-06-15 |
| diffusers 进程内推理增强（进度/img2img/热切换/锁） | 2026-06-15 |
| LOW 级安全/质量问题全部清零（路径遍历/消息上限配置/函数拆分） | 2026-06-15 |
| 多模态图片持久化 + 前端历史恢复 + diffusers 并发锁 + 知识库智能分块 + 请求 traceID 等（TODO-60~75） | 2026-06-16 |
| 飞书 OAuth 用户授权全栈（TODO-85）：OAuth Token Manager + 3 端点 + Java 透传 + 5 个飞书内置工具（日历读写/任务读写/IM） | 2026-06-27 |
| iPhone 16 PWA 移动端布局全量优化：底部 4-Tab Bar + MorePanel + BottomSheet 公共组件 + safe-area/dvh/keyboard 适配 + ChatView 角色/模型徽章 + 汉堡菜单移除 | 2026-06-29 |

---

## 九、当前运行状态（2026-07-09）

- **已提交到 GitHub**：所有修改均已推送 master 分支
- **测试覆盖**：350 个 Agent 单元测试 + 63 个 E2E 测试，348 通过（4 预存失败，sentence_transformers 未安装）
- **全局默认模型**：`dolphin:latest`（所有渠道统一，云端配置已注释，按需在 `/admin/models` 激活）
- **Python 环境**：conda `python310`（Python 3.10）
- **Ollama keep_alive**：`-1`（永久常驻显存，避免冷启动延迟）
- **公网接入**：Cloudflare Tunnel（`ia-cloudflared`）→ `intelligent.eu.cc` → `ia-frontend:80`
  - PWA 已可从手机公网安装（iOS Safari：分享 → 添加到主屏幕）
- **已接通的 IM 渠道**：
  - 飞书（Feishu）：WS 长连接，启动自动建立，P2P + 群聊均支持
  - 企业微信（WeCom）：HTTP 回调 `https://intelligent.eu.cc/wecom/callback`，已验证端到端收发
  - Telegram：adapter 已实现（限流 30/s + Inline Keyboard），待配置 Bot Token 后接通
- **Channel Adapter 抽象层**（2026-07-08，TODO-99~106）：
  - Python 侧：`ChannelAdapter` ABC + 4 adapter（飞书/企微/Web/Telegram）+ `ChannelRouter`（多通道并行广播 + fallback）+ `ChannelMessageTool`（LLM 统一 IM 工具）+ `ChannelNotifier`（整合通知系统）
  - Java 侧：`ChannelAdapter` interface + `FeishuChannelAdapter` + `ChannelAdapterManager`（Spring Bean，broadcast 并行）
  - 可观测性：`GET /health/channels` 各 channel 指标端点（成功率/延迟/限流拒绝）
  - 测试：`test_channel_adapter.py`（28 用例）+ `test_channel_router.py`（14 用例）+ `test_channel_phase3.py`（7 用例）全通过
- **2026-07-02 新增能力（heart-record plan W1-W3）**：
  - 心证层：`soul/heart.md` + SoulLoader + SystemPromptBuilder heart 段 + heart_record 工具
  - 分支保护：6 信号 `_detect_branch_failure` + 自动撤回 + 错误分级重试
  - 缓存层：L1 精确缓存（5min TTL/LRU）+ L2 语义缓存（ChromaDB 24h TTL）
  - 可观测性：L3 长期记忆检索命中率 + L4 蒸馏源覆盖率监控埋点
  - 移动端：BottomTabBar / MorePanel 导航从 `routes.config.js` 单源派生
  - 失职自查：飞书推送前后 verify + scheduler 任务执行后 verify + heart_record 写入后读回确认（TODO-93）
  - 进度恢复：`progress_state.md` 自动扫描 → 注入 `[PROGRESS RECOVERY]` 上下文（TODO-94）
  - 跨 session 记忆增强：蒸馏时识别任务进度关键词自动打 `task_progress` 标签，进度恢复时额外查询 LTM（TODO-95）
- **2026-07-07 新增能力（heart-record plan W7-W9，主人永久铁律）**：
  - 数据层：`soul/rules.md`（7 分类/21 条铁律模板）+ heart_record 扩展 rule_add/rule_list/rule_delete（TODO-96）
  - 检索层：SystemPromptBuilder ③.6 RULES 段 + 隐私分层（public/private/secret）+ token 预算退化（<4096 仅 critical）+ 内容 hash 缓存（TODO-97）
  - 执行层：分支失败检测新增信号 6（铁律违反扫描：15 个硬编码危险模式 rm -rf/os.system/eval/DROP TABLE 等 + rules.md 禁止性关键词提取）（TODO-98）
- **IM 渠道用户隔离**：`feishu:{open_id}` / `wecom:{userName}` 各有独立记忆和模型偏好
- **飞书心跳巡检**：已暂停（dolphin 无法正确执行开放式主动联系决策，每次输出无意义占位消息；如需恢复建议搭配云端模型）
- **移动端 PWA**：iPhone 16 适配完成（底部 Tab Bar / safe-area / dvh / 键盘遮挡修复）
- **待办**：TODO-12（性能优化 #4/#5/#8，待触发条件）/ TODO-90（Ollama 量化模型 + keep_alive 调优）/ TODO-91（L3/L4 命中率监控）/ TODO-92（迁移验证首跑 + 全量回归 + 归档）/ TODO-IMG P3 远期遗留（ComfyUI 工作流热重载/LoRA/多模型模板等）

---

## 十、开发者常用命令速查

```bash
# 本地启动顺序
ollama serve
conda activate python310
cd agent && python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload
cd backend/web && ./mvnw spring-boot:run
cd frontend && npm run dev

# 测试
cd agent && pytest tests/ -v                      # 单元测试（~370个）
cd tests/e2e && pytest -v                         # E2E 测试（63个，需服务运行）

# Docker 全栈（按需选 profile）
docker compose up -d                                            # 核心三件套
docker compose --profile tunnel up -d --build                  # + 公网隧道（IM 回调）
docker compose --profile local up -d --build                   # + ollama + comfyui（本地 GPU）
docker compose --profile local --profile tunnel up -d --build  # 全量

# 强杀 Windows 上卡住的 Python agent
wmic process where "commandline like '%uvicorn%'" delete

# 知识库迁移（ChromaDB schema 问题时）
cd agent && python tools/migrate_chromadb.py --dry-run
cd agent && python tools/migrate_chromadb.py

# CLI 客户端角色切换
cd client && python main.py --persona "技术专家"  # 启动时指定角色
# 进入 REPL 后：!personas 列出角色，!persona 创意写手 切换

# 前端构建 + 热更新容器
cd frontend && npm run build
docker cp frontend/dist ia-frontend:/usr/share/nginx/html_new
docker exec ia-frontend sh -c "rm -rf /usr/share/nginx/html_old && mv /usr/share/nginx/html /usr/share/nginx/html_old && mv /usr/share/nginx/html_new /usr/share/nginx/html && nginx -s reload"

# 查看 agent 日志
docker logs ia-agent -f --tail 50

# 重建 agent（Python 代码改动）
docker compose build agent && docker compose up -d agent
```
