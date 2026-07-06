# Python Agent 模块

## 技术栈与运行环境

| 项目 | 版本 |
|------|------|
| **语言** | Python **≥ 3.8**（开发 / Docker 推荐 **3.10**，conda 环境名 `python310`）|
| Web 框架 | FastAPI 0.100+ / Uvicorn |
| 数据校验 | Pydantic v2（`pydantic-settings`）|
| 向量数据库 | ChromaDB（本地持久化，`./chroma_data`）|
| Embedding 模型 | `all-MiniLM-L6-v2`（sentence-transformers）|
| LLM 接口 | Ollama（本地）/ OpenAI-Compatible API（云端）|
| 日志 | Loguru |
| 测试 | pytest 6+（`conda activate python310 && pytest tests/`）|

> **重要**：代码兼容 Python 3.8，但推荐在 3.10 环境开发以获得完整类型提示支持。

---

> FastAPI 服务，port 8000。所有 AI 逻辑的唯一载体：LLM 推理、工具执行、记忆管理、任务调度、角色系统、项目上下文。

---

## 目录结构

```
agent/
├── api/
│   ├── fastapi_app.py          入口：全部 REST/SSE 端点 + 中间件
│   ├── roles_router.py         /api/roles/* 角色 CRUD（含激活状态持久化）
│   ├── conversations_router.py /api/conversations/* 历史会话 CRUD（T4）+ retract 撤回端点
│   ├── projects_router.py      /api/project/* 项目规格/任务树/上下文
│   ├── cloud_router.py         /api/cloud/* 云端服务商 CRUD + 激活切换
│   ├── feishu_oauth_router.py  /api/feishu/oauth/* 飞书用户 OAuth 授权（authorize/callback/status）
│   └── metrics.py              Prometheus 指标 (/metrics)
│
├── core/                       ReAct 推理核心（God Class 已拆分，commit 528b787）
│   ├── agent.py                IntelligentAgent 门面（__init__ / provider / token / cache，~320 行）
│   ├── conversation_flow.py    ConversationFlowMixin（消息构建 / chat / stream / 分支失败检测 / 进度恢复注入）
│   ├── tool_dispatcher.py      ToolDispatcherMixin（工具注册 / 意图识别 / LLM 调用 / 错误分级重试）
│   ├── memory_writer.py        MemoryWriterMixin（记忆预热 / MCP / 蒸馏 / 清理）
│   ├── _context_vars.py        共享 ContextVar（per-request 隔离，避免循环导入）
│   ├── l1_cache.py             L1 精确缓存（SHA256 匹配，5min TTL，LRU 100 条上限）
│   ├── l2_cache.py             L2 语义缓存（ChromaDB 余弦相似度，24h TTL）
│   ├── progress_recovery.py    进度恢复协议（扫描 progress_state.md → 注入 [PROGRESS RECOVERY]）
│   └── system_prompt_builder.py SystemPromptBuilder（灵魂层 / 心证段 / 心跳段 / 角色 / 工具指令组装）
│
├── memory/
│   ├── manager.py              MemoryManager（路由 → 短/长期）
│   ├── base.py                 MemoryEntry 基础数据模型
│   ├── short_term.py           ShortTermMemory（进程内双端队列，TTL 24h，max 100）
│   ├── long_term.py            LongTermMemory（ChromaDB 向量库）
│   ├── distiller.py            MemoryDistiller（自动事实提炼 + 阶段摘要）
│   ├── context_extractor.py    ContextExtractor（项目 nugget 提取，每 8 轮）
│   ├── lightweight_embedding.py 无 sentence-transformers 时的降级向量化
│   └── semantic_cache.py       L2 语义响应缓存（余弦相似度 ≥ 0.92 命中）
│
├── scheduler/
│   ├── simple_scheduler.py     SimpleTaskScheduler（后台线程，2s 轮询）
│   ├── simple_manager.py       TaskManager（向 Agent 暴露的封装 API）
│   ├── simple_models.py        Task / Schedule 数据模型（Pydantic）
│   └── task_scheduler.py       初始化入口函数
│
├── tools/
│   ├── tool_manager.py         ToolManager（per-agent 独立实例）
│   ├── base_tool.py            BaseTool / AsyncBaseTool 抽象基类
│   ├── function_tool.py        FunctionTool / AsyncFunctionTool（签名自推导 schema）
│   ├── migrate_chromadb.py     ChromaDB schema 迁移脚本（--dry-run 支持）
│   └── builtin_tools/
│       ├── calculator.py       数学计算（eval 沙箱）
│       ├── time_tool.py        时间查询
│       ├── file_tool.py        文件读写
│       ├── web_search.py       DuckDuckGo 搜索
│       ├── shell_tool.py       Shell 命令（受目录白名单限制）
│       ├── image_tool.py       图片生成（SiliconFlow API / 本地 SD WebUI）
│       ├── heart_record.py     心证管理（append/list/delete，操作 soul/heart.md）
│       ├── database/           MySQL 查询工具
│       ├── feishu_calendar.py  查询飞书日历（支持 user_access_token / tenant fallback）
│       ├── feishu_task.py      查询飞书任务（支持 user_access_token / tenant fallback）
│       ├── feishu_calendar_create.py  创建日历事件（需 OAuth user_access_token）
│       └── feishu_task_write.py       创建/完成任务（需 OAuth user_access_token）
│
├── im/                          即时通讯集成
│   └── feishu_client.py        飞书 IM 客户端（WS 长连接 / 消息收发 / 推送前后 verify）
│
├── personas/                   角色系统 Python 模块
│   ├── role_manager.py         RoleManager（角色 CRUD + 激活状态持久化）
│   ├── role_models.py          Pydantic 角色数据模型（RoleCard / CoreIdentity 等）
│   └── prompt_builder.py       PromptBuilder 单例（根据激活角色构建 system prompt）
│
├── skills/
│   ├── manager.py              SkillManager（技能注册与查找）
│   ├── router.py               技能意图路由（触发词匹配）
│   ├── applicator.py           技能应用（注入工具约束到 LLM 调用）
│   └── templates.py            技能 Markdown 模板解析
│
├── services/
│   ├── base_provider.py        LLMProvider 抽象基类
│   ├── ollama_provider.py      Ollama 推理（原生 Function Calling + Text-tool 模式）
│   ├── ollama_service.py       Ollama HTTP 客户端封装
│   ├── openai_provider.py      OpenAI-compatible 云端接口（DashScope/DeepSeek 等）
│   ├── mcp_client.py           MCP 工具客户端（GitHub / FileSystem）
│   └── image/                  图片生成服务封装
│
├── prompts/
│   ├── prompt_manager.py       PromptManager（加载 YAML + 角色注入）
│   ├── system_default.yaml     默认 system prompt 模板
│   └── system_dolphin.yaml     dolphin 专用模板（含双语无限制声明）
│
├── soul/
│   └── loader.py               SoulLoader（读取 {project_root}/soul/*.md，构建灵魂层 system prompt）
│                               数据文件：SOUL.md / USER.md / MEMORY.md / IDENTITY.md / HEARTBEAT.md / heart.md / whisper.md
├── analytics/                  使用统计接口（满意度/响应时间/工具排名）
├── config/
│   └── settings.py             Pydantic-settings 全量配置（含 .env 读取）
├── data/                       运行时数据目录（runtime_config.json、user 偏好等）
└── tests/                      pytest 单元测试套件（~370 个，含角色、记忆、调度、消息撤回、飞书 OAuth、心证管理、分支检测、L1/L2 缓存、失职自查、进度恢复、跨 session 记忆增强等）
```

---

## 核心组件详解

### IntelligentAgent（core/ 五文件架构）

`IntelligentAgent` 继承三个 Mixin，职责分离后清晰：

```
IntelligentAgent (agent.py)
    ├── ConversationFlowMixin  (conversation_flow.py)
    │     ├── _build_messages_async()    构建消息列表（注入记忆/项目/任务/Spec）
    │     ├── chat()                     非流式聊天入口
    │     └── stream_chat()              流式聊天入口（SSE token 逐发）
    │
    ├── ToolDispatcherMixin    (tool_dispatcher.py)
    │     ├── _call_model_with_tools()   第一次 LLM 调用（决策：回答 or 工具）
    │     ├── _execute_tool_round()      单轮工具执行 + 结果追加（最多 5 轮）
    │     └── _stream_tokens_async()     流式输出最终回答
    │
    └── MemoryWriterMixin      (memory_writer.py)
          ├── _warmup_memory()           启动时预热长期记忆
          ├── _maybe_distill()           每 N 轮触发记忆提炼
          └── _cleanup_expired()         清理过期短期记忆
```

**ReAct 推理流程**：

```
_build_messages_async()
    注入：短期记忆 + 长期语义检索 + 项目上下文 + 任务列表 + Spec（每5轮）
    │
    ▼
_call_model_with_tools()     ← 第一次 LLM 调用
    │
    ├── 有工具调用 ──► _execute_tool_round() ──► 追加结果 ──► 重复（≤5次）
    │
    └── 无工具调用 ──► _stream_tokens_async()   SSE 逐 token 流式输出
```

**关键设计决策**：
- `_context_vars.py` 中的 `contextvars.ContextVar` 实现 per-asyncio-Task 隔离，同一进程内多用户 provider/persona 不串台
- `_TEXT_TOOL_CALLING_PATTERNS = ["dolphin", "phi2", "orca-mini", "orca2"]`：这些模型不支持 Ollama 原生 Function Calling（Ollama 内部模板覆盖 system prompt），自动切换文本解析模式
- 上下文压缩：超 `max_context_tokens` 时异步压缩最旧 60% 对话为摘要
- L1 精确缓存（OrderedDict LRU）+ L2 语义缓存（ChromaDB 余弦相似度 ≥ 0.92）
- **心证层**：SystemPromptBuilder 在 ③MEMORY 之后插入 ③.5 HEART 段（`soul/heart.md`），优先级高于自动蒸馏记忆；IM 渠道（飞书/企微）自动排除心证内容
- **分支失败检测**（5 信号）：`_detect_branch_failure()` 每轮工具执行后检查——同工具同错误×3 / 连续重复输出 >80% / 用户纠偏 / 空响应+RTE / 重试耗尽，命中即自动撤回 2 轮 + 注入 `[BRANCH_RESET]`
- **工具错误分级重试**：鉴权错（401/403）重试 1 次，系统错（5xx/超时）重试 3 次
- **进度恢复**：`progress_recovery.py` 在首次消息时扫描 `memory/work/`，检测未完成任务并注入 `[PROGRESS RECOVERY]` + `[TASK PROGRESS MEMORY]` 上下文
- **失职自查**：飞书推送前后 verify（content 非空 + message_id 有效）、scheduler 任务执行后 verify（输出文件存在）、heart_record 写入后读回确认

---

### 记忆系统（memory/）

```
用户消息 ──► ShortTermMemory（进程内双端队列，TTL 24h，max 100）
                │
                │ 每 5 轮 → MemoryDistiller → LLM 提取 JSON → LongTermMemory (facts/preferences)
                │ 每 10 轮 → SessionSummarizer → LongTermMemory (type=session_summary)
                │ 有 project_id → ContextExtractor（每 8 轮）→ ChromaDB project_{id}_context
                │
每次聊天：LongTermMemory 语义检索 top-K → 注入 system 消息 [MEMORY CONTEXT]
          project 上下文语义检索     → 注入 system 消息 [PROJECT CONTEXT]
```

**ChromaDB 防御性设计**：
- `count()` 和 `query()` 均包裹 `try/except TypeError`，防 schema mismatch 崩溃
- `semantic_cache` 初始化失败时自动 delete + recreate collection
- 迁移脚本：`python tools/migrate_chromadb.py [--dry-run]`（修复 seq_id 类型不匹配）
- Docker 具名卷：`intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm`

**消息撤回级联**（`conversations_router.py` 的 `retract` 端点）：
- 每条消息在生成时由 `chat_router.py` 赋一个跨层共享的 `message_id`，写入对话 JSON 和 `ShortTermMemory` 的 metadata
- 撤回时：对话 JSON 数组里对应条目直接移除（不留 tombstone）+ `ShortTermMemory.delete_by_ids()` 按 `message_id` 精确删除
- 蒸馏（`MemoryDistiller`）写入长期记忆时会记录来源短期记忆的 `source_message_ids`；撤回后异步（`asyncio.create_task` + `to_thread`，不阻塞响应）扫描长期记忆，命中的条目打 `excluded_from_retrieval` 标记——硬过滤、不物理删除（一条摘要可能混合多条消息内容，避免误伤）
- 已蒸馏的旧数据没有 `source_message_ids`，无法回溯清理，是已知边界（详见设计文档 `docs/superpowers/specs/2026-06-21-message-retraction-design.md`）

---

### 角色（Persona）系统（personas/）

**新角色体系**（commit b562259，2026-06-09 完成）：

- `role_models.py`：Pydantic 数据模型，包含 `RoleCard`（元信息）、`CoreIdentity`（身份定义）、`UserProfile`（用户画像）、`RoleData`（完整角色）
- `role_manager.py`：`RoleManager` 单例，负责角色增删改查；激活状态持久化至 `data/user_active_roles.json`
- `prompt_builder.py`：`PromptBuilder` 单例，根据当前激活角色将多字段组合成 system prompt；dolphin 模型使用双语无限制声明包裹，防止安全微调回退

**热加载**：Docker 模式下 `personas/` 目录挂载为卷，修改后仅需重启容器（无需重建镜像）。

---

### 工具系统（tools/）

注册两种工具类型：
- `BaseTool` / `AsyncBaseTool`：手写工具类，完整参数校验和错误处理
- `FunctionTool(callable)`：从函数签名自动推导参数 schema

`ToolManager` 是 per-agent 实例（非全局单例），多 agent 实例工具表不串台。

工具自动注册为调度器 action，定时任务可按名字调用任意工具。

---

### 任务调度系统（scheduler/）

后台线程每 2 秒轮询待执行任务。支持五种调度类型：

| 类型 | 说明 |
|------|------|
| `immediate` | 立即执行一次 |
| `delay` | N 秒后执行一次 |
| `interval` | 按固定间隔循环执行 |
| `datetime` | 指定 ISO 时间点执行一次 |
| `cron` | Cron 表达式（如 `0 8 * * *`） |

**并发控制**：调度器持有 asyncio Semaphore，绑定到正确的事件循环（启动时显式传入），避免将 Semaphore 绑定到临时 loop 导致 chat 永久阻塞（历史 bug 根因）。

---

### 多用户隔离

| 层 | 手段 |
|----|------|
| Provider（模型） | `_request_provider_ctx` ContextVar；持久化到 `data/user_model_prefs.json` |
| Persona（角色） | `_request_persona_ctx` ContextVar；持久化到 `data/user_active_roles.json` |
| ToolManager | per-IntelligentAgent 独立实例 |
| TaskManager | per-IntelligentAgent 独立实例 |
| 项目上下文 | turn counter 键为 `{user_id}:{project_id}` |

Java 后端通过 `X-User-Id` 头透传真实用户 ID，Python middleware 优先读取此头。

---

## 配置项（settings.py 完整列表）

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `ollama_base_url` | `http://localhost:11434` | Ollama 地址 |
| `ollama_model` | `qwen2.5:7b` | 全局默认模型 |
| `ollama_temperature` | `0.7` | 生成温度 |
| `ollama_max_tokens` | `2048` | 最大生成 token |
| `ollama_num_ctx` | `4096` | 上下文窗口 |
| `ollama_num_gpu` | `-1` | GPU 层数（-1=自动）|
| `chat_timeout` | `300` | 单次推理超时（秒） |
| `max_context_tokens` | `7000` | 上下文 token 预算 |
| `inference_concurrency` | `1`（docker-compose 默认）| 并发推理上限；CPU 跑大模型必须为 1 |
| `inference_queue_size` | `20` | 等待队列深度（超出返回 503）|
| `response_cache_max_size` | `500` | L1 缓存条数（LRU）|
| `response_cache_ttl_secs` | `3600` | L1 缓存 TTL |
| `semantic_cache_threshold` | `0.92` | L2 语义命中阈值 |
| `memory_distill_interval` | `5` | 每 N 轮触发记忆提炼 |
| `memory_summary_interval` | `10` | 每 N 轮生成阶段摘要 |
| `short_term_max_size` | `100` | 短期记忆最大条数 |
| `short_term_ttl_hours` | `24` | 短期记忆 TTL |
| `github_mcp_enabled` | `false` | MCP GitHub 工具（未安装 mcp 包时必须保持 false，否则启动卡 30s）|
| `jwt_secret` | **必填** | 与 Java 保持一致，≥32 字符 |
| `cloud_provider` | 空 | 云端 LLM（`openai`/`dashscope`/`deepseek`/`zhipu`/`moonshot`/`baidu`/`siliconflow`）|
| `cloud_api_key` | 空 | 云端 LLM API Key |
| `cloud_base_url` | 空（自动推断）| 云端 base URL，留空则按 provider 自动设置 |
| `scheduler_max_concurrent_tasks` | `5` | 调度器最大并发任务数 |
| `feishu_oauth_redirect_uri` | 空 | 飞书 OAuth 公网 callback URL（Cloudflare Tunnel 等）|
| `feishu_oauth_encryption_key` | 空 | Fernet 密钥，user_access_token 加密存储用（`Fernet.generate_key()`）|

---

## 启动与开发

```bash
cd agent

# 安装依赖
pip install -r requirements.txt        # 生产
pip install -e ".[dev]"               # 含 black/isort/pylint/mypy

# 启动（本地开发，使用 conda python310 环境）
conda activate python310
python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload

# 运行单元测试（~370 个，< 30s）
pytest tests/ -v

# 代码质量
black . && isort . && pylint . && mypy .
```

**Docker 热更新规则**：

| 修改内容 | 操作 |
|----------|------|
| `personas/` 下的 Python 文件 | `docker restart ia-agent`（卷挂载，无需重建）|
| `prompts/*.yaml` | `docker restart ia-agent` |
| `core/`、`memory/`、`services/` 等 Python 代码 | `docker compose build agent && docker compose up -d agent` |
| `requirements-docker.txt` 依赖变更 | 同上（会重新安装，耗时较长）|

---

## REST API 端点速查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| POST | `/api/chat` | 非流式聊天 |
| POST | `/api/chat/stream` | SSE 流式聊天（Java 消费）|
| GET | `/api/models` | 列出可用模型 |
| POST | `/api/model/switch` | 切换模型（per-user）|
| GET | `/api/roles` | 列出角色 |
| GET | `/api/roles/activate` | 查询当前激活角色 |
| POST | `/api/roles/activate` | 激活角色（per-user 持久化）|
| DELETE | `/api/roles/activate` | 取消激活角色 |
| GET | `/api/conversations` | 列出历史会话 |
| POST | `/api/conversations` | 新建会话记录 |
| POST | `/api/conversations/{session_id}/retract` | 按 message_id 撤回（永久删除）消息，级联清理短期记忆 + 异步标记长期记忆排除检索，单次最多 50 条 |
| GET | `/api/memory/list` | 列出记忆条目 |
| GET | `/api/memory/search?q=关键词` | 语义搜索 |
| GET | `/api/tasks/list` | 列出调度任务 |
| POST | `/api/tasks/create` | 创建调度任务 |
| GET | `/api/project/list` | 项目列表 |
| GET | `/api/project/spec` | 读取规格文档 |
| PUT | `/api/project/spec` | 写入规格文档 |
| POST | `/api/project/tasks/decompose` | AI 任务分解 |
| GET | `/api/tools/list` | 工具列表 |
| GET | `/metrics` | Prometheus 指标 |
| GET | `/api/notifications/poll` | 通知轮询（Java 每 5s 调用）|
| GET | `/api/cloud/providers` | 列出云端服务商配置 |
| POST | `/api/cloud/providers` | 新建服务商配置 |
| PUT | `/api/cloud/providers/{id}` | 编辑服务商配置（空 api_key = 保留原值）|
| DELETE | `/api/cloud/providers/{id}` | 删除服务商配置 |
| POST | `/api/cloud/providers/{id}/activate` | 激活服务商（立即切换全局 provider）|
| POST | `/api/cloud/deactivate` | 停用云端，切回 Ollama |
| GET | `/api/cloud/presets` | 列出已知服务商 URL 预设（7 家）|
| GET | `/api/feishu/oauth/authorize?open_id=xxx` | 获取飞书 OAuth 授权 URL（需 JWT）|
| GET | `/api/feishu/oauth/callback` | OAuth 回调，返回 HTML（无 JWT，由飞书服务器重定向）|
| GET | `/api/feishu/oauth/status?open_id=xxx` | 查询 OAuth 授权状态（需 JWT）|

---

## 已知问题与技术债

| 编号 | 问题 | 状态 |
|------|------|------|
| D-01 | Java 用 `java-service` 固定 token，Python 无法区分真实用户 | ✅ 已修复（2026-06-02）：X-User-Id 头透传 |
| D-02 | ChromaDB `seq_id` INTEGER/BLOB schema mismatch | ✅ 已防御（try/except）；`migrate_chromadb.py` 可彻底修复 |
| D-03 | `_TEXT_TOOL_CALLING_PATTERNS` 硬编码，新模型需改源码 | ✅ 已修复（2026-06-13）：迁移到 `TEXT_TOOL_CALLING_PATTERNS` 环境变量，cached_property 缓存 |
| D-04 | L1 缓存 key 未包含 persona 维度，不同角色可能命中同一缓存 | ✅ 已修复（之前版本）：key 含 persona_sig |
| D-05 | `asyncio.ensure_future` 在模块级别调用，依赖 uvicorn 复用事件循环 | 运行正常，不规范 |
