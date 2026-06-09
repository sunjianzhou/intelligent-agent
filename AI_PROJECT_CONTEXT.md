# 智能体项目 — AI 上下文速查文档

> **本文档专为大模型阅读设计**。新对话开始时先读此文件，5 分钟内建立完整项目认知，无需再反复询问基础背景。
> 最后更新：2026-06-02

---

## 一、整体架构（三层）

```
浏览器 / CLI
    │  WebSocket + REST
    ▼
Java 后端 (Spring Boot, port 8080)   ← 纯网关，无 AI 逻辑
    │  HTTP + SSE
    ▼
Python Agent (FastAPI, port 8000)    ← 所有 AI 逻辑在此
    │
    ├── Ollama (port 11434)           ← 本地 LLM 推理
    └── ChromaDB (进程内)            ← 向量存储
```

**Docker 容器名**：`ia-frontend`(3000) / `ia-backend`(8080) / `ia-agent`(8000) / `ia-ollama`(11434)

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
├── docker-compose.yml
├── CLAUDE.md       Claude Code 项目指令
├── TODOS.md        待办事项（含已完成标记）
└── AI_PROJECT_CONTEXT.md  ← 本文件
```

---

## 三、Python Agent 详解（`agent/`）

### 3.1 入口与启动

- 入口：`api/fastapi_app.py`
- 启动：`uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000`
- 本地开发使用 conda 环境 `python310`

### 3.2 核心组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `IntelligentAgent` | `core/agent.py` | ReAct 循环主体，~2300 行 |
| `OllamaProvider` | `services/ollama_provider.py` | LLM 推理，支持原生 Function Calling 和 text-tool 两种模式 |
| `OpenAIProvider` | `services/openai_provider.py` | 云端 LLM fallback（DashScope/DeepSeek/ZhipuAI 等） |
| `MemoryManager` | `memory/manager.py` | 路由短/长期记忆 |
| `ShortTermMemory` | `memory/short_term.py` | 进程内双端队列，TTL 24h，max 100 条 |
| `LongTermMemory` | `memory/long_term.py` | ChromaDB 向量库，embedding: all-MiniLM-L6-v2 |
| `MemoryDistiller` | `memory/distiller.py` | 每 5 轮对话提炼事实到长期记忆 |
| `SemanticCache` | `memory/semantic_cache.py` | L2 语义响应缓存（余弦相似度 ≥ 0.92 命中） |
| `ContextExtractor` | `memory/context_extractor.py` | 按 project_id 提取项目上下文 nugget |
| `ToolManager` | `tools/tool_manager.py` | per-agent 独立实例（非全局单例） |
| `SimpleTaskScheduler` | `scheduler/simple_scheduler.py` | 后台线程，1s 轮询，支持 interval/delay/datetime/cron |
| `SkillManager` | `skills/manager.py` | 技能路由（意图 → 最优工具集注入 prompt） |
| `PromptManager` | `prompts/prompt_manager.py` | 加载 system_default.yaml / system_dolphin.yaml |

### 3.3 ReAct 推理流程

```
_build_messages_async()
    注入：短期记忆 + 长期语义检索 + 项目上下文 + 任务列表 + Spec（每10轮）
    │
    ▼
_call_model_with_tools()  ← 第一次 LLM 调用
    │
    ├── 有工具调用 → _execute_tool_round() → 追加结果 → 循环（max 5次）
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

**已知限制**：Java 向 Python 发请求时用固定 token `java-service`，Python 看不到真实用户 ID（所有前端用户共享同一 key）。

### 3.5 内置工具（`tools/builtin_tools/`）

| 工具 | 文件 | 说明 |
|------|------|------|
| CalculatorTool | `calculator.py` | 数学计算（eval 沙箱） |
| TimeTool | `time_tool.py` | 时间查询 |
| FileTool | `file_tool.py` | 文件读写（受 filesystem_allowed_dirs 限制） |
| WebSearchTool | `web_search.py` | DuckDuckGo 搜索 |
| ShellTool | `shell_tool.py` | Shell 命令（受目录白名单限制） |
| DatabaseTool | `database/` | MySQL 查询 |
| ImageGenerationTool | `image_tool.py` | 图片生成（SiliconFlow API 或本地 SD WebUI） |

另有通过 `FunctionTool` 动态注册的工具：`store_memory`、`search_memories`、`create_reminder`、`create_periodic_reminder` 等。

### 3.6 任务调度系统

- **动作类型**：`log`（固定文字通知）、`llm_generate`（每次调用大模型生成内容）、`system_info`、`test`、任意工具名
- **调度类型**：`immediate`、`delay`（N秒后一次）、`interval`（每N秒循环）、`datetime`（指定时间）、`cron`（表达式）
- **LLM 生成任务**：`llm_generate` 动作收到 `{prompt, role}` 参数，调用 `agent.chat(prompt)` 后将结果 push 到通知队列，前端轮询 `/api/notifications/poll` 每 30s 拉取，以 AI 气泡形式推入聊天
- **持久化**：任务状态保存到 `data/tasks.json`，重启恢复

### 3.7 角色（Persona）系统

- 文件位置：`personas/*.md`（第一行 `# 标题` 作为展示名）
- 内置角色：`default.md`、`creative.md`（创意写手）、`technical.md`（技术专家）、`超级女友.md`
- 持久化：`data/user_persona_prefs.json`（按 user_id）
- Dolphin 专用：`prompts/system_dolphin.yaml` 有 `persona_template` 和 `overlay`，防止中文角色内容触发安全微调回退

### 3.8 项目系统（Project）

- 每个项目有：名称、规格文档（Spec）、任务树（TaskTree）
- Spec 存入 ChromaDB（每项目独立集合 `project_{id}_context`）
- ContextExtractor 每 8 轮对话提取 nugget 写入该集合
- 下次聊天时注入 `[PROJECT CONTEXT]` 和 `[SPEC]` 区块（Spec 每 10 轮注入一次）
- `[TASK_DONE:<task_id>]` sentinel：LLM 回复中检测到后 yield `task_update`，前端更新任务树状态

### 3.9 关键配置（`config/settings.py`）

| 参数 | 默认 | 说明 |
|------|------|------|
| `ollama_model` | `qwen2.5:7b` | 全局默认模型 |
| `chat_timeout` | 300s | 单次推理超时（CPU 跑 dolphin 需 200-300s） |
| `max_context_tokens` | 7000 | 上下文 token 预算 |
| `inference_concurrency` | 3（Docker 设为1） | 并发推理上限，dolphin+CPU 必须为1 |
| `memory_distill_interval` | 5 | 每 N 轮触发事实提炼 |
| `memory_summary_interval` | 10 | 每 N 轮生成阶段摘要 |
| `semantic_cache_threshold` | 0.92 | L2 语义缓存命中阈值 |
| `jwt_secret` | 必填 | 与 Java 和 client 保持一致 |
| `cloud_provider/model` | 空 | 配置后启用云端 LLM fallback |
| `image_gen_provider` | `siliconflow` | 图片生成提供商（或 `sd_webui`） |
| `github_mcp_enabled` | false | 开启 MCP GitHub 工具（需安装 mcp 包） |

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
| `PersonaProxyController` | `/api/personas/*` | 角色 CRUD 代理 |
| `MemoryProxyController` | `/api/memory/*` | 记忆 CRUD 代理 |
| `TaskProxyController` | `/api/tasks/*` | 任务调度代理 |
| `ToolProxyController` | `/api/tools/*` | 工具管理代理 |
| `SkillProxyController` | `/api/skills/*` | 技能管理代理 |
| `ProjectProxyController` | `/api/project/*` | 项目规格/上下文代理 |
| `AnalyticsProxyController` | `/api/analytics/*` | 统计分析代理 |
| `ImageProxyController` | `/api/images/*` | 图片文件代理 |
| `SpaController` | `/**` | Vue Router history mode 兜底 |

### 4.3 WebSocket 消息类型

**前端 → Java**：`chat_message`、`ping`、`get_system_info`

**Java → 前端**：`connection_established`、`thinking`、`chat_token`、`tool_call_start`、`tool_calls_done`、`chat_done`、`task_update`、`task_blocked`、`pong`、`system_info`、`error`

### 4.4 JWT

- 前端用户 token：有效期 24h，活跃用户通过 `X-New-Token` 响应头自动滑动续期
- 服务间 token：Java → Python 用 `sub="java-service"` 的固定 token（临近过期自动刷新）

---

## 五、前端详解（`frontend/`）

### 5.1 技术栈

Vue 3 + Pinia + Vue Router 4 + Element Plus + Font Awesome 6 + marked + DOMPurify + highlight.js + Vite 4 + PWA

### 5.2 页面与路由

| 路径 | 视图 | 说明 |
|------|------|------|
| `/chat` | ChatView | 主聊天页，流式渲染，工具卡片，历史会话 |
| `/personas` | PersonasView | 角色卡片，新建/编辑/MD 预览 |
| `/memory` | MemoryView | 短期/长期记忆，搜索，导出 |
| `/project` | ProjectView | 三栏：项目列表 / SpecEditor / TaskTree |
| `/admin/tools` | ToolsView | 工具列表 + API Key 配置 |
| `/admin/skills` | SkillView | 技能管理（MD 导入） |
| `/admin/tasks` | TasksView | 任务调度 CRUD |
| `/admin/system` | SystemView | CPU/RAM/GPU/磁盘实时监控 + 参数调节滑块 |
| `/admin/stats` | StatsView | 满意度/响应时间/工具排名统计 |

**旧路径重定向**：`/tools` → `/admin/tools`，`/tasks` → `/admin/tasks`，`/skills` → `/admin/skills`，`/system` → `/admin/system`

### 5.3 导航结构

- **桌面**：左侧 Sidebar（聊天/角色/记忆/项目/任务）+ Header 右侧齿轮菜单（工具/Skill/系统/统计）
- **移动端（≤768px）**：侧边栏隐藏，Header 汉堡菜单展开，分两段：主导航（聊天/角色/记忆/项目）+ 管理后台（任务/工具/Skill/系统/统计）；聊天页额外显示模型/角色切换

### 5.4 状态管理（Pinia）

| Store | 文件 | 核心状态 |
|-------|------|---------|
| `useWebSocketStore` | `stores/websocket.js` | WS连接、消息流、模型/角色、通知轮询 |
| `useAuthStore` | `stores/auth.js` | JWT token、用户名 |
| `useLocalSessionStore` | `stores/localSession.js` | 会话历史（IndexedDB v2） |
| `useProjectStore` | `stores/project.js` | 项目数据（localStorage） |

### 5.5 关键前端功能

- **流式渲染**：Markdown 增量解析（marked），每 token 到来时重新渲染，代码高亮
- **思考计时器**：`isThinking=true` 后 3 秒开始显示累计秒数
- **取消流式**：`cancelStreaming()` 断开 WS 重连，Java SSE 流随之终止
- **工具进度**：`activeToolSteps` 实时显示运行中工具名+参数摘要
- **通知轮询**：WS 连接后每 30s 轮询 `/api/notifications/poll`，任务触发通知推入聊天，system/assistant 两种气泡；所有通知气泡底部带"查看任务管理"跳转链接
- **清空对话**：同步清除前端 + 调 Python `/api/memory` 清短期记忆
- **深色模式**：`data-theme="dark"` 持久化到 localStorage
- **聊天持久化**：最近 50 条存 localStorage，刷新后恢复
- **会话历史**：IndexedDB 存完整对话，侧边栏展示最近 12 条
- **PWA**：可安装到桌面，vite-plugin-pwa

---

## 六、CLI 客户端（`client/`）

直连 Python Agent（port 8000），不经 Java 后端，无 WebSocket。

| 文件 | 说明 |
|------|------|
| `main.py` | CLI 入口（argparse，单次问答 / REPL 两种模式） |
| `api.py` | AgentClient（HTTP + SSE + JWT 自动续签） |
| `session.py` | ChatSession（内存 + JSON 持久化到 `datas/`） |
| `repl.py` | 交互式 REPL（Rich 可选，支持 `!model`/`!persona`/`!history` 等命令） |
| `config.yaml` | 服务器地址、jwt_secret、用户名、超时 |

---

## 七、已知问题 & 技术债

### Python Agent

| 编号 | 问题 | 优先级 |
|------|------|--------|
| D-01 | ~~Java 用 `java-service` 固定 token，Python 无法区分真实用户~~ | ✅ 已修复（2026-06-02） |
| D-02 | ChromaDB `seq_id` INTEGER/BLOB schema mismatch（已防御 try/except；迁移脚本见 `agent/tools/migrate_chromadb.py`） | 低，可随时运行脚本修复 |
| D-03 | `_TEXT_TOOL_CALLING_PATTERNS` 硬编码，新模型需改源码 | 低 |
| D-04 | ~~L1 缓存 key 未包含 persona 维度~~ | ✅ 已修复 |
| D-05 | `asyncio.ensure_future` 在模块级别调用（lifespan 前），依赖 uvicorn 复用事件循环 | 低 |

### Java 后端

| 编号 | 问题 | 优先级 |
|------|------|--------|
| J-01 | ~~无法向 Python 透传真实用户 ID~~ | ✅ 已修复（2026-06-02） |
| J-02 | `PythonProxyService` 和 `AgentService` 各维护独立 serviceToken，重复逻辑 | 低 |
| J-03 | `CloseableHttpClient.createDefault()` 无连接池配置，高并发下可能连接耗尽 | 低 |
| J-04 | ~~WebSocket 握手无 JWT 验证~~ | ✅ 已实现（`JwtHandshakeInterceptor` 完整校验） |

### 前端

| 编号 | 问题 | 优先级 |
|------|------|--------|
| F-02 | 角色文件标题与"角色设定"功能名歧义，可增加独立 `displayName` 字段 | 低 |
| F-04 | 会话历史标题更新依赖异步 `_persist()`，Sidebar 可能延迟刷新 | 低 |
| F-05 | `api.js` 中 `switchModel` 动态 import 与静态 import 混用，有代码分割警告 | 低 |

### 基础设施

| 编号 | 问题 | 优先级 |
|------|------|--------|
| I-01 | 全程 HTTP 明文（JWT + 聊天内容），生产环境需加 Nginx + TLS | 高（公网部署前必须） |

---

## 八、值得优化的方向

以下是结合当前代码结构提炼的高价值优化点，按优先级排列：

### P0 — 解决根本缺陷

1. **真实用户 ID 透传**（D-01 / J-01）：✅ **已完成（2026-06-02）**。
   - `JwtHandshakeInterceptor`：WS 握手时提取前端 JWT sub → session attributes
   - 所有 Java 代理 Controller：通过 `proxy.extractUserIdFromRequest(req)` 提取用户身份，调用 `proxy.get/post/...(..., userId)` 透传
   - `PythonProxyService.authHeaders(userId)`：非 java-service 用户时加 `X-User-Id` 头
   - Python `jwt_auth_middleware`：优先读取 `X-User-Id`（当 JWT sub 是 java-service 时）
   - **验证**：admin/user 各自有独立的模型和角色偏好，互不干扰

2. **HTTPS/TLS**（I-01）：✅ **已实现（2026-06-02）**，`nginx/` 目录：
   - `nginx-https.conf`：Nginx 反代配置（TLS 1.2/1.3，安全响应头，WS 代理）
   - `generate-certs.sh`：生成自签名证书脚本
   - `docker-compose.yml`：新增 `nginx-https` service（`--profile https` 启动）
   - 启动：`sh nginx/generate-certs.sh && docker compose --profile https up -d`

### P1 — 体验核心

3. **任务通知延迟**：✅ **已完成（2026-06-03）** — Java `@Scheduled(fixedDelay=5000)` 每 5 秒轮询 Python，有内容时广播 `notification` WS 事件；前端移除 30s `setInterval`，改为 `handleMessage` 处理 `notification`。

4. **L1 缓存 key 加入 persona 维度**（D-04）：✅ **已完成** — `core/agent.py` `_cache_key()` 已包含 persona 签名。

5. **ChatView 大消息性能**：✅ **已完成（2026-06-03）** — 改用 `highlight.js/lib/core` + 按需语言注册，ChatView 从 958KB(320KB gz) 降至 78KB(25KB gz)，另有独立缓存的 vendor-hljs/vendor-marked/vendor-purify 三个 chunk。

### P2 — 功能补全

6. **项目任务树 `[TASK_DONE]` 检测完整实现**：✅ **已完成（2026-06-03）** — Python 已 yield `task_update`/`task_blocked`；Java 已转发；前端 `handleMessage` 新增两个 case，动态 import `useProjectStore` 更新状态。

7. **角色 Markdown 文件热加载**：✅ **已是热加载**（验证于 2026-06-03）— GET 端点每次从磁盘读，修改 `.md` 无需重启。

8. **多模型云端 fallback 完善**：✅ **已完成（2026-06-03）** — `fastapi_app.py` 新增 `CLOUD_PROVIDER_BASE_URLS` 映射（openai/dashscope/deepseek/zhipu/moonshot/baidu/siliconflow），`cloud_base_url` 留空时自动解析；`/api/models` 接口返回 `known_cloud_providers` 列表。

### P3 — 长期

9. **ChromaDB 迁移**（D-02）：✅ **迁移脚本已完成（2026-06-03）** — `agent/tools/migrate_chromadb.py`：检测 seq_id 类型问题，自动导出→重建→导入。用法：`python tools/migrate_chromadb.py`（支持 `--dry-run` 模式）。

10. **WebSocket 握手 JWT 验证**（J-04）：✅ **已实现** — `JwtHandshakeInterceptor` 在握手阶段验证 query string token，token 缺失或无效直接返回 401。

---

## 九、当前运行状态（2026-06-03）

- **已部署到 Docker**：所有修改均已构建并运行中
- **活跃任务**：任务管理页有 4 个任务（3 个旧的 log 型提醒 + 1 个新建的 llm_generate 每30s鼓励任务）
- **使用的模型**：`dolphin:latest`（无限制人格），用户也配置了 dolphin3:8b 等
- **Python 环境**：conda `python310`（Python 3.10）

---

## 十、开发者常用命令速查

```bash
# 本地启动顺序
ollama serve
conda activate python310
cd agent && python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload
cd backend/web && ./mvnw spring-boot:run
cd frontend && npm run dev

# Docker 全栈
docker compose up -d

# 强杀 Windows 上卡住的 Python agent
wmic process where "commandline like '%uvicorn%'" delete

# 前端构建 + 热更新容器
cd frontend && npm run build
docker cp frontend/dist ia-frontend:/usr/share/nginx/html_new
docker exec ia-frontend sh -c "rm -rf /usr/share/nginx/html_old && mv /usr/share/nginx/html /usr/share/nginx/html_old && mv /usr/share/nginx/html_new /usr/share/nginx/html && nginx -s reload"

# 查看 agent 日志
docker logs ia-agent -f --tail 50

# 重建 agent（Python 代码改动）
docker compose build agent && docker compose up -d agent
```
