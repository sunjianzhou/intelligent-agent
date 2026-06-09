# Python Agent 模块

> FastAPI 服务，port 8000。所有 AI 逻辑的唯一载体：LLM 推理、工具执行、记忆管理、任务调度、角色系统、项目上下文。

---

## 目录结构

```
agent/
├── api/
│   ├── fastapi_app.py          入口：全部 REST/SSE 端点 + 中间件
│   ├── personas_router.py      /api/personas/* 角色 CRUD
│   └── metrics.py              Prometheus 指标 (/metrics)
├── core/
│   └── agent.py                IntelligentAgent 核心（ReAct 循环）
├── memory/
│   ├── manager.py              MemoryManager（路由 → 短/长期）
│   ├── short_term.py           ShortTermMemory（进程内双端队列）
│   ├── long_term.py            LongTermMemory（ChromaDB 向量）
│   ├── distiller.py            MemoryDistiller（自动事实提炼）
│   ├── context_extractor.py    ContextExtractor（项目 nugget 提取）
│   └── semantic_cache.py       L2 语义响应缓存
├── scheduler/
│   ├── simple_scheduler.py     SimpleTaskScheduler（后台线程，2s 轮询）
│   └── simple_manager.py       TaskManager（对 Agent 暴露的封装）
├── tools/
│   ├── tool_manager.py         ToolManager（per-agent 独立实例）
│   ├── function_tool.py        FunctionTool / AsyncFunctionTool（签名自推导）
│   └── builtin_tools/
│       ├── calculator.py       数学计算（eval 沙箱）
│       ├── time_tool.py        时间查询
│       ├── file_tool.py        文件读写
│       ├── web_search.py       DuckDuckGo 搜索
│       ├── shell_tool.py       Shell 命令（受目录白名单限制）
│       ├── database/           MySQL 查询工具
│       └── reminder_tool.py    定时提醒
├── services/
│   ├── base_provider.py        LLMProvider 抽象基类
│   ├── ollama_provider.py      Ollama 推理（原生 Function Calling + text-tool 模式）
│   ├── openai_provider.py      OpenAI-compatible 云端接口
│   └── mcp_client.py           MCP 工具客户端（GitHub / FileSystem）
├── skills/                     技能路由（意图 → 最优工具集）
├── analytics/                  使用统计路由
├── prompts/
│   ├── system_default.yaml     默认 system prompt 模板
│   └── system_dolphin.yaml     dolphin 专用模板（含 persona_template）
├── personas/                   *.md 角色文件（可热加载）
├── config/
│   └── settings.py             Pydantic-settings 全量配置（含 .env 读取）
└── tests/                      pytest 测试套件
```

---

## 核心组件详解

### IntelligentAgent（core/agent.py）

ReAct 循环实现，每轮对话流程：

```
_build_messages_async()          构建消息列表（注入记忆 + 项目上下文 + 任务列表）
    │
    ▼
_call_model_with_tools()         第一次 LLM 调用（决策：直接回答 or 调用工具）
    │
    ├── 有工具调用 ──► _execute_tool_round() ──► 追加工具结果
    │                      │
    │                      └── 重复至 max_iterations=5
    │
    └── 无工具调用 ──► _stream_tokens_async()   流式输出最终回答
```

**关键设计决策**：
- `contextvars.ContextVar` 隔离 provider 和 persona，per-asyncio-Task 不串台
- `_TEXT_TOOL_CALLING_PATTERNS = ["dolphin", "phi2", "orca-mini", "orca2"]`：这些模型不支持 Ollama 原生 Function Calling（Ollama 会用自身模板覆盖 system prompt），改用文本解析模式
- 上下文压缩：超 `max_context_tokens` 时异步压缩最旧 60% 对话为摘要
- L1 精确缓存（OrderedDict LRU）+ L2 语义缓存（ChromaDB 余弦相似度 ≥ 0.92）

### 记忆系统

```
用户消息 ──► ShortTermMemory（进程内双端队列，TTL 24h，max 100）
                  │ 每 5 轮 → MemoryDistiller → LLM JSON 提取 → LongTermMemory
                  │ 每 10 轮 → SessionSummarizer → LongTermMemory
                  │ 有 project_id → ContextExtractor（每 8 轮）
每次聊天：LongTermMemory 语义检索 top-K → 注入 system 消息
```

**ChromaDB 防御**：
- `count()` 和 `query()` 均包裹 `try/except TypeError`，防 schema mismatch 崩溃
- semantic_cache 初始化失败时自动 delete + recreate collection
- 迁移脚本：`python tools/migrate_chromadb.py [--dry-run]`（修复 seq_id 类型不匹配）

### 工具系统

注册两种工具：
- `BaseTool` / `AsyncBaseTool`：手写工具类，有完整参数校验
- `FunctionTool(callable)`：自动从函数签名推导参数 schema

`ToolManager` 是 per-agent 实例（非全局单例），避免多 agent 工具注册表串台。

工具同时注册为调度器 action，定时任务可按名字调用任意工具。

### 角色（Persona）系统

- `agent/personas/*.md` → 角色文件，第一行 `# 标题` 作为展示名
- `agent/prompts/system_dolphin.yaml` 中的 `persona_template`：dolphin 使用时包裹中英双语无限制声明，防止中文角色内容触发安全微调回退
- per-user 角色持久化到 `data/user_persona_prefs.json`，重启后恢复
- Docker 下 `personas/` 和 `prompts/` 均为卷挂载，修改后无需重建镜像，**但需重启 ia-agent 让 PromptManager 重新加载**

### 多用户隔离

| 层 | 手段 |
|----|------|
| Provider（模型） | `_request_provider_ctx` ContextVar；持久化到 `data/user_model_prefs.json` |
| Persona（角色） | `_request_persona_ctx` ContextVar；持久化到 `data/user_persona_prefs.json` |
| ToolManager | per-IntelligentAgent 独立实例 |
| TaskManager | per-IntelligentAgent 独立实例 |
| 项目上下文 | turn counter 键为 `{user_id}:{project_id}` |

**P0 已完成**：Java 后端通过 `X-User-Id` 头透传真实用户 ID，Python middleware 优先读取此头，多用户模型/角色偏好完全隔离。

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
| `inference_concurrency` | `1`（docker-compose 设置）| 并发推理上限；CPU 跑 dolphin 必须为 1 |
| `inference_queue_size` | `20` | 等待队列深度（超出返回 503）|
| `response_cache_max_size` | `500` | L1 缓存条数（LRU）|
| `response_cache_ttl_secs` | `3600` | L1 缓存 TTL |
| `semantic_cache_threshold` | `0.92` | L2 语义命中阈值 |
| `memory_distill_interval` | `5` | 每 N 轮触发记忆提炼 |
| `memory_summary_interval` | `10` | 每 N 轮生成阶段摘要 |
| `short_term_max_size` | `100` | 短期记忆最大条数 |
| `short_term_ttl_hours` | `24` | 短期记忆 TTL |
| `github_mcp_enabled` | `false` | MCP GitHub 工具（需安装 mcp 包，未安装保持 false 否则启动卡 30s）|
| `jwt_secret` | **必填** | 与 Java 保持一致 |
| `cloud_provider` / `cloud_model` | 空 | 云端 LLM 配置（已知 provider：openai/dashscope/deepseek/zhipu/moonshot/baidu/siliconflow，`cloud_base_url` 留空时自动解析）|
| `scheduler_max_concurrent_tasks` | `5` | 调度器最大并发任务数 |

---

## 启动与开发

```bash
cd agent

# 安装
pip install -r requirements.txt        # 生产
pip install -e ".[dev]"               # 开发工具

# 启动（本地开发，使用 conda python310 环境）
conda activate python310
python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload

# 测试
pytest tests/ -v

# 代码质量
black . && isort . && pylint . && mypy .
```

**Docker 热更新规则**：
- 修改 `prompts/*.yaml` 或 `personas/*.md` → `docker restart ia-agent`（卷挂载，无需重建）
- 修改 Python 代码（`core/`、`memory/`、`services/` 等）→ `docker compose build agent && docker compose up -d agent`
- 修改 `requirements-docker.txt` → 同上，但会重新安装依赖（耗时）

---

## 已知问题与技术债

| 编号 | 问题 | 状态 |
|------|------|------|
| D-01 | Java 用 `java-service` 固定 token，Python 无法区分真实用户 | ✅ 已完成（2026-06-02）：Java 所有 Controller 加 X-User-Id 头，Python middleware 优先读取 |
| D-02 | ChromaDB `seq_id` INTEGER/BLOB schema mismatch | 已防御（try/except），根本修复需迁移数据 |
| D-03 | `_TEXT_TOOL_CALLING_PATTERNS` 硬编码，新模型需改源码 | 低优先级 |
| D-04 | L1 缓存 key 未包含 persona 维度，不同角色可能命中同一缓存 | 低优先级 |
| D-05 | `asyncio.ensure_future` 在模块级别调用（lifespan 前），依赖 uvicorn 复用事件循环 | 运行正常，但不规范 |

## 近期修复（2026-06-01）

| 项 | 文件 | 说明 |
|----|------|------|
| memory category fallback | `api/fastapi_app.py` | 短期记忆条目的 category 字段优先读 `metadata["category"]`，fallback 到 `metadata["type"]`，避免显示"unknown" |
| cleanup_expired 方法名 | `core/agent.py` | 记忆提炼（知识蒸馏）调用 `_cleanup_expired()` 而非不存在的 `cleanup_expired()`，修复点击"知识提炼"按钮报 AttributeError |
