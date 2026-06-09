# Intelligent Agent 项目完整分析文档

> 生成日期: 2026-05-19 | 最后更新: 2026-05-24
> 目的: 供另一个 Claude 实例在不接触源码的情况下进行后续设计与规划
> 覆盖范围: 三层的完整源码分析，包括所有模块的数据结构、接口、业务逻辑和当前状态

---

## 目录

1. [项目总览](#1-项目总览)
2. [架构与数据流](#2-架构与数据流)
3. [Python Agent 层（核心）](#3-python-agent-层核心)
   - 3.1 FastAPI 入口与 API 端点
   - 3.2 配置系统
   - 3.3 LLM Provider 层
   - 3.4 IntelligentAgent 核心
   - 3.5 工具系统
   - 3.6 记忆系统
   - 3.7 任务调度器
   - 3.8 Skills 技能系统
   - 3.9 MCP Client
   - 3.10 分析系统
4. [Java 后端层（网关）](#4-java-后端层网关)
5. [Vue 3 前端层](#5-vue-3-前端层)
6. [Docker 部署](#6-docker-部署)
7. [依赖清单](#7-依赖清单)
8. [当前架构特征与待完善项](#8-当前架构特征与待完善项)
9. [规划中能力项（路线图）](#9-规划中能力项路线图)

---

## 1. 项目总览

这是一个**三层架构的本地优先（+云端可选）智能 Agent 系统**，支持：
- 多模型对话（本地 Ollama + 云端 OpenAI-compatible API）
- 工具调用（ReAct 循环，Function Calling）
- 双模记忆（短期 + 长期向量化）
- 任务调度（延迟/间隔/定时/即时）
- 多步骤 Skill 编排
- MCP 协议扩展工具
- 对话质量反馈收集
- WebSocket 流式聊天

**开发者**: lin miao
**技术栈**: Python (FastAPI) / Java (Spring Boot 2.7) / Vue 3 + Vite

---

## 2. 架构与数据流

```
┌─────────────────────────────────────────────────┐
│  浏览器 (localhost:3000)                          │
│  Vue 3 + Pinia + Element Plus                    │
│  登录 → JWT Token → WebSocket 流式聊天             │
└──────────┬──────────────────────┬────────────────┘
           │ WebSocket            │ REST /api/*
           ▼                      ▼
┌─────────────────────────────────────────────────┐
│  Java Backend (Spring Boot, port 8080)           │
│  - WebSocketController: WS 消息路由               │
│  - AgentService: 转发到 Python + SSE 消费         │
│  - HealthController: 全量代理 Python REST API     │
│  - JwtAuthFilter: Bearer Token 鉴权               │
│  - AuthController: /api/auth/login               │
└──────────────────────┬──────────────────────────┘
                       │ REST / SSE (JWT 服务间 Token)
                       ▼
┌─────────────────────────────────────────────────┐
│  Python Agent (FastAPI, port 8000)               │
│  - IntelligentAgent: ReAct 循环 + 工具执行         │
│  - ToolManager: 工具注册/执行/分类                 │
│  - MemoryManager: 短期 + 长期记忆                  │
│  - SimpleTaskScheduler: 后台线程定时执行           │
│  - SkillManager: 关键词/LLM 意图匹配               │
│  - MCPClientManager: 外部 MCP Server 连接          │
│  - Provider: Ollama / OpenAI-compatible           │
└──────────────────────┬──────────────────────────┘
                       │ HTTP
                       ▼
┌─────────────────────────────────────────────────┐
│  Ollama (localhost:11434)                        │
│  模型: qwen2.5:7b (可切换)                        │
└─────────────────────────────────────────────────┘
```

**启动顺序**: Ollama → Python Agent → Java Backend → Frontend

---

## 3. Python Agent 层（核心）

### 3.1 FastAPI 入口与 API 端点

**文件**: `agent/api/fastapi_app.py`

#### 全局单例

```python
# Provider 选择逻辑：
# 1. 如果 .env 配了 cloud_provider + cloud_api_key + cloud_model → 用 OpenAIProvider（云端模式）
# 2. 否则尝试 OllamaProvider
# 3. 如果都不行 → provider = None，所有端点降级

agent: IntelligentAgent = None  # 在 lifespan 中初始化
```

#### 完整端点清单

| Method | Path | 功能 | 关键细节 |
|--------|------|------|----------|
| GET | `/` | 基础状态 | 返回 ollama_available, model, agent_ready |
| GET | `/health` | 健康检查 | 返回 cloud_mode, cloud_model, agent_model, timestamp |
| GET | `/api/models` | 模型列表 | 合并 Ollama 本地模型 + 云端模型 |
| POST | `/api/model/switch` | 切换模型 | 支持切换到本地 Ollama 或云端 OpenAI |
| POST | `/api/chat` | 非流式聊天 | agent 模式走 ReAct 循环; 降级走 Provider 直连 |
| POST | `/api/chat/stream` | SSE 流式聊天 | 事件类型: token, tool_call, tool_calls_done, done, error |
| GET | `/api/tools/list` | 工具列表 | agent 可用时从 ToolManager 获取 |
| **记忆** | | | |
| GET | `/api/memory` | 记忆统计 | short_term + long_term 统计 |
| GET | `/api/memory/list?memory_type=&limit=` | 记忆列表 | 支持 short_term / long_term 过滤 |
| DELETE | `/api/memory` | 清空所有记忆 | |
| DELETE | `/api/memory/{id}` | 删除单条记忆 | 仅 long_term |
| GET | `/api/memory/search?q=&limit=` | 语义搜索记忆 | 向量相似度搜索 |
| POST | `/api/memory/distill` | 手动触发记忆蒸馏 | 测试用 |
| **任务** | | | |
| GET | `/api/tasks/list` | 任务列表 | 按 status 过滤 |
| POST | `/api/tasks/create` | 创建任务 | schedule_type: immediate/delay/interval/datetime |
| DELETE | `/api/tasks/{id}` | 删除任务 | |
| POST | `/api/tasks/{id}/cancel` | 取消任务 | |
| POST | `/api/tasks/{id}/execute` | 立即执行 | |
| GET | `/api/tasks/stats` | 任务统计 | |
| GET | `/api/tasks/actions` | 可用动作列表 | |
| **Skills** | (通过 skills/router.py) | | |
| GET | `/api/skills` | 全部 Skill | tag / enabled_only 过滤 |
| POST | `/api/skills` | 创建 Skill | |
| PUT | `/api/skills/{id}` | 更新 Skill | |
| DELETE | `/api/skills/{id}` | 删除 Skill | |
| PATCH | `/api/skills/{id}/toggle` | 启用/禁用 | |
| GET | `/api/skills/templates/list` | 模板列表 | |
| POST | `/api/skills/templates/{id}/apply` | 从模板创建 | |
| **分析** | (通过 analytics/router.py) | | |
| POST | `/api/analytics/feedback` | 提交反馈 | like/dislike 评分 |
| GET | `/api/analytics/stats/{username}` | 统计汇总 | |
| GET | `/api/analytics/records/{username}` | 反馈记录 | |
| GET | `/api/analytics/skill-logs/{username}` | Skill 触发日志 | |
| GET | `/api/analytics/skill-stats/{username}` | Skill 统计 | |
| **系统** | | | |
| GET | `/api/system/resources` | 系统资源 | CPU, 内存, 磁盘, GPU, Ollama 模型, 进程信息 |

#### JWT 中间件

```python
# 白名单: /health, /, /docs, /openapi.json, /redoc
# 如果 settings.jwt_enabled = False → 全局跳过
# 取 Authorization: Bearer <token>
# 用 HS256 + settings.jwt_secret 验签
```

#### 流式聊天 SSE 事件格式

```json
{"type": "thinking", "data": "正在思考..."}
{"type": "token", "data": "你"}
{"type": "tool_call", "data": {"tool": "CalculatorTool", "args": {...}, "success": true, "result": "..."}}
{"type": "tool_calls_done", "data": [...]}
{"type": "done", "data": {"content": "完整回复"}}
{"type": "error", "data": "错误信息"}
```

---

### 3.2 配置系统

**文件**: `agent/config/settings.py`

使用 `pydantic-settings`，从 `.env` 读取，关键配置项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ollama_base_url` | `http://localhost:11434` | Ollama 服务地址 |
| `ollama_model` | `qwen2.5:7b` | 默认模型名 |
| `ollama_temperature` | 0.7 | 生成温度 |
| `ollama_max_tokens` | 2048 | 最大生成 token |
| `ollama_top_p` | 0.9 | Top-P 采样 |
| `ollama_top_k` | 40 | Top-K 采样 |
| `ollama_repeat_penalty` | 1.1 | 重复惩罚 |
| `ollama_num_ctx` | 8192 | 上下文窗口 |
| `api_host` | `127.0.0.1` | API 绑定地址 |
| `api_port` | 8000 | API 端口 |
| `short_term_max_size` | 100 | 短期记忆最大条数 |
| `short_term_ttl_hours` | 24 | 短期记忆 TTL |
| `embedding_model` | `all-MiniLM-L6-v2` | 嵌入模型 |
| `chroma_persist_dir` | `agent/chroma-data` | ChromaDB 持久化目录 |
| `tool_result_max_chars` | 3000 | 工具结果截断阈值 |
| `chat_timeout` | **300** | 聊天请求超时(秒)；CPU 推理大模型需 200-300s |
| `max_context_tokens` | **7000** | 上下文 token 预算上限（留 1000+ token 给输出） |
| `intent_use_embedding` | true | 是否启用嵌入意图过滤 |
| `intent_embedding_threshold` | 0.30 | 意图匹配相似度阈值 |
| `intent_embedding_top_k` | 3 | 意图匹配 Top-K |
| `ollama_num_gpu` | -1 | GPU 层数：-1=Ollama 自动；正整数=指定层数（显存受限时用） |
| `inference_concurrency` | 3 | 同时推理最大并发数（超出进等待队列） |
| `inference_queue_size` | 20 | 等待队列深度（超出返回 503） |
| `response_cache_max_size` | 500 | L1 精确响应缓存条数（LRU 淘汰） |
| `response_cache_ttl_secs` | 3600 | L1 缓存有效期（秒） |
| `semantic_cache_threshold` | 0.92 | L2 语义缓存余弦相似度命中阈值 |
| `semantic_cache_ttl_secs` | 86400 | L2 缓存有效期（秒，默认 24h） |
| `semantic_cache_max_entries` | 2000 | L2 缓存最大条目数 |
| `scheduler_max_concurrent_tasks` | 5 | 调度器同时执行最大任务数 |
| `github_token` | "" | GitHub MCP Token |
| `github_mcp_enabled` | false | 是否启用 GitHub MCP |
| `filesystem_mcp_enabled` | false | 是否启用文件系统 MCP |
| `filesystem_allowed_dirs` | `""` | MCP 允许目录（逗号分隔，空=不允许任何目录） |
| `jwt_secret` | "" | JWT 密钥（生产环境必须通过 JWT_SECRET 环境变量注入） |
| `jwt_enabled` | true | 是否启用 JWT |
| `cloud_provider` | "" | 云端提供商: openai/dashscope/deepseek/zhipu |
| `cloud_api_key` | "" | 云端 API Key |
| `cloud_base_url` | "" | 云端 API 地址 |
| `cloud_model` | "" | 云端模型名 |
| `db_type/host/port/user/password` | mysql | 数据库配置（供 DatabaseTool 使用） |

**文件**: `agent/config/constants.py` — 枚举常量 (TOOL_TYPES, TASK_STATUS, MEMORY_TYPES, MODEL_PRESETS)

---

### 3.3 LLM Provider 层

#### 抽象基类

**文件**: `agent/services/base_provider.py`

```python
@dataclass
class ChatMessage:
    role: str      # system / user / assistant
    content: str

@dataclass
class LLMConfig:
    temperature: float = 0.7
    max_tokens: int = 512
    top_p: float = 0.9
    top_k: int = 40
    repeat_penalty: float = 1.1
    num_ctx: int = 2048

@dataclass
class LLMResponse:
    content: str
    model: str
    success: bool
    error: str
```

抽象方法: `provider_name`, `current_model`, `available_models()`, `check_connection()`, `chat()`

#### OllamaProvider

**文件**: `agent/services/ollama_provider.py`

- 通过 `requests` 直接调 Ollama REST API (`/api/chat`, `/api/tags`)
- 支持: 同步 chat, 流式 `chat_stream_generator` (yield token), `chat_with_tools` (Function Calling)
- `build_tool_schemas_from(tools)` / `build_tool_schemas(tool_manager)` → 将 ToolParameter 转为 Ollama function schema
- 模型切换: `switch_model(model)` — 从缓存列表中匹配
- 初始化时自动 `_find_best_match` — 如果指定模型不存在则降级到已安装的第一个模型
- 流式生成器: `chat_stream_generator` 逐行读 SSE, yield token, 超时自动中断

#### OpenAIProvider

**文件**: `agent/services/openai_provider.py`

- 兼容 OpenAI API 格式 (`/chat/completions`)
- 支持流式 `chat_stream_generator` — 解析 SSE `data: ` 行
- `chat_with_tools` — 原生 Function Calling, tool_choice=auto
- `build_tool_schemas` — 递归转 ToolParameter 为 dict
- 支持任何 OpenAI-compatible API (DeepSeek, 智谱, DashScope 等)

---

### 3.4 IntelligentAgent 核心

**文件**: `agent/core/agent.py` (~1800 行，最核心文件)

#### 初始化流程

1. Provider 选择: 参数传入 > 云端配置 > 本地 Ollama
2. `_init_tools()` → 注册 6 个内置工具 + 函数工具 + 记忆工具
3. `MemoryManager` 初始化 (short_term + long_term)
4. `TaskManager` 初始化 (后台调度线程)
5. 注册任务工具到调度器
6. 异步启动 MCP 连接 + 记忆清理循环
7. 预热 embedding 模型 + 意图分类向量

#### Token 估算与上下文保护

- `_estimate_tokens(text)` — 中英文混合: `len(text) / 2.5`
- `_trim_context(messages, max_tokens)` — 保留 system + 最后一条 user，从中间最旧开始移除（降级兜底）
- `_compress_context(messages, max_tokens)` — 超过 `max_context_tokens` 时，将最旧 60% 的对话用 LLM 压缩为一条摘要 system 消息，LLM 压缩失败时自动降级到 `_trim_context`
- 由 `max_context_tokens=7000` 控制（留 1000+ token 余量给输出）
- `_build_messages_async()` 和 `chat()` / `chat_stream()` 均调用压缩逻辑

#### 意图分类 (Intent Filtering)

**目的**: 根据用户消息过滤相关工具分类，减少传给 LLM 的工具数量

**两层策略**:
1. **关键词硬匹配** (高精度，零成本): 9 组关键词映射到 8 个分类 (github, web, file, filesystem, math, utility, memory, scheduler, system)
2. **Embedding 兜底** (高召回): 用 sentence-transformers 将用户消息和分类描述编码为向量，计算余弦相似度，取 `top_k=3` 且 `threshold>=0.30` 的分类

**分类描述** (中英双语):
```python
_INTENT_CATEGORY_DESCRIPTIONS = {
    "github": "GitHub repository, code search, issues, pull requests...",
    "web": "Web search, query the internet, latest news...",
    "file": "Local file read/write, list directory...",
    "filesystem": "Sandboxed filesystem access via MCP...",
    "math": "Calculation, arithmetic, math expression...",
    "utility": "Current time, date, timezone...",
    "memory": "Recall previous information...",
    "scheduler": "Schedule tasks, reminders...",
    "system": "System information, CPU usage...",
}
```

#### 消息构建 `_build_messages`

```
[system] system_prompt (模型适配的)
[system] 最近的对话历史 (如果 use_memory)
[system] 相关背景知识 (如果 use_memory, 且已去重)
[user] 当前用户消息
```

最终经过 `_trim_context` 截断

#### System Prompt 策略（Prompt Manager）

**文件**: `agent/prompts/prompt_manager.py`，YAML 模板目录 `agent/prompts/`

- `agent.system_prompt` 属性 → `prompt_manager.get(model_name)` → 按 `model_pattern` 字段匹配对应 YAML
- 当前 YAML 文件:
  - `system_default.yaml` — 通用模型: 中文回复、规则约束 (GitHub/搜索必须用工具)
  - `system_dolphin.yaml` — dolphin 系列: **仅**包含无限制人格描述，不含工具规则（规则由运行时注入）
- **工具指令注入**: 文本工具模式模型（dolphin 等）的工具调用格式说明不在 YAML 中，而由 `_build_tools_prompt_for()` 动态生成并追加到 system 消息末尾，避免模型把规则文本混入回答中

#### 文本工具调用模式（Text-Tool Mode）

针对不支持 Ollama 原生 Function Calling 的模型（dolphin、phi2、orca-mini、orca2），使用独立调用路径：

```python
_TEXT_TOOL_CALLING_PATTERNS = ["dolphin", "phi2", "orca-mini", "orca2"]
```

- 原因: Ollama 收到 `tools` 参数时会用内置模板覆盖 system prompt，破坏 dolphin 的无审查人格
- 做法: 不传 `tools` 参数，将工具列表以文本格式注入 system 末尾，靠文本解析提取调用
- 优化: 首轮如无工具调用，**直接复用**非流式 LLM 的返回内容，跳过第二次流式 LLM 调用（节省 100-300s CPU 推理时间）

#### ReAct 循环 — `chat()` (非流式)

```
for i in range(max_iterations=5):
    1. _call_model_with_tools() → 调用 LLM (带 Function Calling)
    2. 如果没有 tool_calls → 直接返回
    3. 提取 tool_calls，去重
    4. 并发执行所有工具 (asyncio.gather)
    5. 将工具结果以 tool role 注入 messages
    6. 如果所有调用重复 → 强制 LLM 直接回答
    7. 循环结束 → 再调一次 LLM 生成最终回答
```

#### ReAct 循环 — `chat_stream()` (SSE 流式)

```
for i in range(max_iterations=5):
    1. _call_model_with_tools() 
    2. 执行工具，yield ('tool_call', entry)
    3. yield ('tool_calls_done', tool_call_log)
    4. 最终用 stream_tokens_async 流式输出回答
    5. yield ('token', token) ... yield ('done', {...})

# 文本工具模式优化（dolphin 等）:
if use_text_tools and 首轮无工具调用 and content 非空:
    清理残留 <tool_call> 标签 → 直接 yield ('token', cleaned) → return
    # 跳过第二次 LLM 调用，节省 100-300s
```

#### 工具调用解析 `_extract_tool_calls`

四种格式降级（优先级从高到低）:
1. `<tool_call>{...}</tool_call>` — 标准 XML 标签包裹 JSON
2. `<tool_call {"tool":...}>` — **dolphin 实际输出格式**（JSON 作为标签属性，无闭合标签）
3. 裸 JSON `{"tool": "...", "args": {...}}` — 用栈匹配提取顶层 JSON 对象
4. Gemma 自定义格式 `<|tool_call>call:tool_name{args}`

解析结果自动去重（基于 tool_name + args 的 hash key）。

#### 工具执行

- `_execute_tool_call` → `tool_manager.execute_tool_async(name, **args)`
- `_execute_tool_round` → 批量并发，自动去重（基于 tool_name + args 的 key），重复调用终止迭代
- `_format_tool_result` → 超过 `tool_result_max_chars=3000` 自动截断并附说明

#### 记忆蒸馏 `_cleanup_memories`

- 每天凌晨 2 点触发 (每 15 分钟检查)
- 清理过期短期记忆
- 长期记忆超过 500 条压缩到 400 条
- `_distill_short_term_memories()` → 用 LLM 从近期对话提取 preferences, personal_info, frequent_topics, behavior_patterns → 存入长期记忆
- `_generate_daily_summary()` → 用 LLM 生成今日对话摘要

---

### 3.5 工具系统

#### 基类定义

**文件**: `agent/tools/base_tool.py`

```python
class ToolParameter(BaseModel):
    name: str          # 参数名
    type: str          # str/int/float/bool/list/dict
    description: str
    required: bool = True
    default: Any = None

class ToolSchema(BaseModel):
    name: str
    description: str
    parameters: List[ToolParameter]

class ToolResult(BaseModel):
    success: bool
    data: Any
    error: Optional[str]
    execution_time: float

class BaseTool(ABC):
    # 通过 inspect.signature(execute) 自动提取参数定义
    # __call__ → _run() → execute(**kwargs) → ToolResult

class AsyncBaseTool(BaseTool):
    # async execute(**kwargs)
    # async _run() → ToolResult
```

#### ToolManager

**文件**: `agent/tools/tool_manager.py`

- `tools: Dict[str, BaseTool]` — 同步工具
- `async_tools: Dict[str, AsyncBaseTool]` — 异步工具
- `tool_categories: Dict[str, List[str]]` — 分类索引
- 全局单例: `tool_manager = ToolManager()`
- 启动时自动注册 `DatabaseTool`

核心方法:
- `register_tool(tool, category)` — 注册工具实例
- `register_function(func, name, category)` — 将普通函数包装为 FunctionTool
- `execute_tool(name, **kwargs)` / `execute_tool_async(name, **kwargs)` — 执行
- `get_all_tools()` — 返回 tools + async_tools 的合并 dict

#### FunctionTool / AsyncFunctionTool

**文件**: `agent/tools/function_tool.py`

- `FunctionTool(func)` — 自动从函数签名提取 `ToolParameter` 列表
- 如果传入的是 `async def` 函数 → `asyncio.run()` 包装
- `AsyncFunctionTool` 同理，同步函数用 `run_in_executor`

#### 内置工具列表

| 工具名 | 分类 | 文件 | 功能 |
|--------|------|------|------|
| CalculatorTool | math | `calculator.py` | 安全沙箱数学计算 (eval with restricted builtins), 支持三角函数/对数/幂 |
| AdvancedCalculatorTool | math | `calculator.py` | 继承 CalculatorTool, 增加单位转换 (km→miles, ℃→℉ 等) |
| TimeTool | utility | `time_tool.py` | 当前时间/日期/格式化/时间戳 |
| TimerTool | utility | `time_tool.py` | 计时器 (start/end 动作) |
| FileTool | file | `file_tool.py` | 文件操作: read/write/list/create/delete/copy/move/info/exists, 路径安全检查 |
| WebSearchTool | web | `web_search.py` | DuckDuckGo 搜索, 免费无需 API Key |
| DatabaseTool | database | `database/database_tool.py` | MySQL 查询 (通过 DatabaseTool) |

#### 函数式工具 (在 agent.py 中注册)

- `system_info` (system): 获取 CPU/内存/平台
- `store_memory` (memory): 存储记忆到系统
- `search_memories` (memory): 搜索记忆
- `create_reminder` (scheduler): 创建提醒任务
- `list_tasks` (scheduler): 列出任务

---

### 3.6 记忆系统

#### 架构

```
MemoryManager
  ├── ShortTermMemory (in-process deque)
  │   - max_size=100, ttl_hours=24
  │   - 简单文本匹配检索
  │   - 存储对话历史、任务信息
  │
  └── LongTermMemory (ChromaDB 向量数据库)
      - 持久化到 agent/chroma-data/
      - Embedding: sentence-transformers (all-MiniLM-L6-v2, dim=384)
      - 或 LightweightEmbeddingModel (仅用于测试, MD5 hash 模拟)
      - 或 ChromaDB 内置 embedding (Docker 环境无 torch 时自动降级)
      - 存储知识、事实、用户偏好
```

#### MemoryItem (base.py)

```python
class MemoryItem(BaseModel):
    id: str
    content: str
    metadata: Dict[str, Any]
    embedding: Optional[List[float]]
    created_at: datetime
    updated_at: datetime
    importance: float  # 0.0-1.0
    access_count: int
    last_accessed: datetime
```

#### MemorySearchResult

```python
class MemorySearchResult(BaseModel):
    memory: MemoryItem
    similarity: float
    score: float  # similarity*0.5 + importance*0.3 + recency*0.2
```

#### MemoryManager 路由规则

```
category → 存储位置:
  "conversation" → short_term
  "knowledge"    → long_term
  "fact"         → long_term
  "preference"   → long_term
  "task"         → short_term
```

#### build_context 去重策略

1. 短期对话: 取最近 N 轮 (排除当前轮 + 非 user/assistant 消息)
2. 长期语义搜索: 用 query 检索 (不再走 both 避免与 short_term 重复)
3. 内容级去重: 过滤与短期对话文本互相包含的长期记忆

#### 轻量级嵌入 (降级方案)

**文件**: `agent/memory/lightweight_embedding.py`

- 用 MD5 hash 做确定性随机向量
- 归一化到单位向量
- 不依赖任何外部模型
- 余弦相似度计算与标准模型一致

#### ChromaDB 防御性容错

历史版本 ChromaDB 将 `seq_id` 以 INTEGER 写入，当前版本期望 BLOB，`_decode_seq_id()` 抛出 `TypeError`，导致 `count()` 和 `query()` 均失败。

修复措施：
- `long_term.py:_search_in_chroma` — 单独包裹 `count()` 的 `try/except TypeError`，失败时 debug 级日志并返回空列表
- `semantic_cache.py:_init_collection` — `get_or_create_collection` 失败时自动 delete + recreate（重建损坏 collection）；`get()` 先检查 `count() == 0`，`collection.query()` 包裹 `except TypeError`

#### L2 语义响应缓存

**文件**: `agent/memory/semantic_cache.py`

使用 ChromaDB 独立 collection (`response_cache`) 缓存语义相似问题的回答：
- 命中条件: 余弦相似度 >= `semantic_cache_threshold=0.92` 且未过期（`semantic_cache_ttl_secs=86400`）
- 最大条目: `semantic_cache_max_entries=2000`
- 缓存未命中时正常调 LLM，命中后直接返回缓存内容（跳过 LLM 推理）

---

### 3.7 任务调度器

#### SimpleTaskScheduler

**文件**: `agent/scheduler/simple_scheduler.py`

- 后台线程每 2 秒检查一次
- 支持调度类型: immediate, delay, interval, datetime
- `actions: Dict[str, Callable]` — 已注册的动作注册表
- 内置动作: `log`, `system_info`, `test`
- 工具自动注册为可调度动作
- 失败重试: 最多 3 次，间隔 10 秒
- 清理: 已完成超过 1 小时的任务自动删除

#### SimpleTask 模型

**文件**: `agent/scheduler/simple_models.py`

```python
class SimpleTask(BaseModel):
    id: str                    # task_xxxxxxxx
    name: str
    action: str                # 动作名（函数名或工具名）
    args: Dict[str, Any]
    schedule_type: str         # immediate/interval/delay/datetime
    interval_seconds: Optional[int]
    delay_seconds: Optional[int]
    run_at: Optional[datetime]
    status: SimpleTaskStatus   # pending/running/completed/failed/cancelled
    run_count: int
    retry_count: int
    max_retries: int = 3
```

#### TaskManager

**文件**: `agent/scheduler/simple_manager.py`

- 全局单例: `task_manager = TaskManager()`
- 启动时自动开始调度线程
- 便捷方法: `create_reminder`, `create_periodic_task`, `create_daily_task`
- 把所有已注册工具注册为可调度动作

---

### 3.8 Skills 技能系统

#### 设计理念

Skill = 预定义的多步骤执行策略，注入到 LLM system prompt 中，引导 LLM 按预设步骤调用工具。

#### 核心类

**文件**: `agent/skills/manager.py`

```python
class SkillStep:
    step_id: str
    name: str
    description: str
    tool_hints: List[str]     # 建议使用的工具
    forced_tools: List[str]   # 强制加入可用工具列表
    strategy_prompt: str      # 注入 LLM 的具体执行要求

class Skill:
    id: str
    name: str
    trigger_keywords: List[str]  # 触发关键词
    overall_strategy: str        # 整体策略
    steps: List[SkillStep]       # 执行步骤列表
    enabled: bool
```

#### 意图匹配 (两层)

1. **关键词匹配** (`match_by_keywords`): 对用户消息做 `in` 包含匹配，按命中数排序
2. **LLM 裁决** (`match_by_llm`): 多个候选时，让 LLM 判断最匹配的技能 (返回 skill_id 或 "none")

#### SkillApplicator

**文件**: `agent/skills/applicator.py`

- `apply(message, messages, filtered_tools, call_model_func)`
- 匹配到 Skill 后:
  1. 将 Skill 的策略提示注入为 system 消息
  2. 强制将 forced_tools 加入可用工具列表
  3. 记录触发日志到 SkillLogStore

#### 内置 Skills (5 个)

| Skill | 触发词 | 步骤数 | 关键工具 |
|-------|--------|--------|----------|
| 数学计算 | 计算, 算一下, 等于, ², 平方, 开根 | 1 | CalculatorTool (forced) |
| 时间查询 | 几点, 日期, 当前时间 | 1 | TimeTool (forced) |
| 文件操作 | 读取, 读文件, 写入, 目录, 列出文件 | 2 | FileTool (forced) |
| GitHub 操作 | github, 仓库, pr, issue, commit | 3 | GitHub MCP tools (hinted) |
| 网络搜索 | 搜索, 查一下, 网上, 最新, 新闻 | 2 | WebSearchTool (forced) |

#### 内置模板 (templates.py, 6 个)

- `tpl_database`: 数据库查询助手 (含 DatabaseTool)
- `tpl_github`: GitHub 代码助手
- `tpl_file`: 文件处理助手
- `tpl_websearch`: 网络信息助手
- `tpl_task`: 时间任务助手 (含 create_reminder)
- `tpl_report`: 日报周报生成 (含 search_memories)

---

### 3.9 MCP Client

**文件**: `agent/services/mcp_client.py`

#### 架构

```
MCPClientManager (全局单例 mcp_manager)
  └── MCPServerConnection (每个 Server 一个独立后台 task)
       ├── 通过 stdio_client + ClientSession 保持长连接
       ├── 初始化时获取 MCP Server 的工具列表
       └── MCPToolWrapper:
            - 继承 BaseTool
            - execute() → 通过 asyncio.Queue 发送调用请求到后台 task
            - 后台 task 调用 session.call_tool() 并返回结果
```

#### 支持的外部 MCP Server

1. **GitHub**: `npx -y @modelcontextprotocol/server-github` (需 `github_token`)
2. **Filesystem**: `npx -y @modelcontextprotocol/server-filesystem <dirs>` (需 `filesystem_allowed_dirs`)

#### 连接生命周期

- Agent 启动时 `asyncio.ensure_future(_init_mcp_tools())` 异步初始化
- 每个 Server 在独立 asyncio.Task 中运行
- 工具调用通过 Queue 通信，60 秒超时
- Agent 关闭时 `mcp_manager.close_all()` 取消所有 task

---

### 3.10 分析系统

#### FeedbackStore

**文件**: `agent/analytics/feedback_store.py`

- JSON 文件持久化: `data/feedback/{username}/records.json`
- 每条记录: id, username, message, response, rating(like/dislike), response_time, tools_used, skill_triggered, created_at
- 统计: total, likes, dislikes, like_rate, avg_response_time, tool_usage Top10, skill_usage, daily_counts(14天), response_time_trend(最近20次)

#### SkillLogStore

**文件**: `agent/analytics/skill_log.py`

- JSON 文件持久化: `data/skill_logs/{username}/records.json`
- 每条记录: id, skill_name, message(截断100字), steps_count, tools, triggered_at
- 统计: total, by_skill (按技能名分组计数), recent(最近10条)

---

## 4. Java 后端层（网关）

**基础**: Spring Boot 2.7.18, Java 8

### 4.1 WebSocketController

**文件**: `backend/web/src/main/java/com/intelligent/agent/web/controller/WebSocketController.java`

- 继承 `TextWebSocketHandler`
- 端点: `/ws`
- Session 管理: `ConcurrentHashMap<String, WebSocketSession>`
- 连接建立: 发送 `connection_established` + 调用 `sendSystemInfo`
- 消息类型路由:
  - `chat_message` → `handleChatMessage` → 先发 thinking → 调 `agentService.streamChatAsync`
  - `ping` → `pong`
  - `get_system_info` → `sendSystemInfo`
- 断开: 从 sessions Map 移除

### 4.2 AgentService

**文件**: `backend/web/src/main/java/com/intelligent/agent/web/service/AgentService.java`

- 生成 JWT 服务间 Token (24h 有效期): `jwtUtil.generateToken("java-service")`
- 所有 Python 请求带 `Authorization: Bearer <serviceToken>` 头

**核心方法**:

```java
// 非流式 (ChatController / 飞书)
String chat(ChatRequest request)        // 调 POST /api/chat
Map<String, Object> chatFull(ChatRequest) // 完整结果

// 流式 (WebSocket)
void streamChatAsync(ChatRequest, WebSocketSession, requestId, startTime)
// → 线程池提交任务 → doStreamChat:
//   POST /api/chat/stream, 用 CloseableHttpClient
//   逐行读 SSE: data: {...}
//   事件映射:
//     "token"           → wsMsg type="chat_token"
//     "tool_calls_done" → wsMsg type="tool_calls_done"
//     "done"            → wsMsg type="chat_done" (含 response_time)
//     "error"           → wsMsg type="error"

// 系统信息
Map<String, Object> getRealSystemInfo()  // JVM 信息 + 代理 Python /health + /api/tools/list

// 模型
Map<String, Object> getModels()          // 代理 /api/models
Map<String, Object> switchModel(String)   // 代理 /api/model/switch
```

**线程池配置**: core=2, max=10, 队列=100, CallerRunsPolicy

### 4.3 HealthController (REST 代理层)

**文件**: `backend/web/src/main/java/com/intelligent/agent/web/controller/HealthController.java`

**这是最庞大的 Controller**，几乎代理了 Python 的全部 REST API:

| 端点 | 功能 |
|------|------|
| `GET /api/health` | Java 自身健康 |
| `GET /api/python/health` | 代理 Python /health |
| `GET /api/system/info` | 系统信息 (调 AgentService) |
| `GET /api/models` | 模型列表 |
| `POST /api/model/switch` | 切换模型 |
| `GET /api/tools/list` | 工具列表 |
| `GET /api/memory` | 记忆统计 |
| `GET /api/memory/list` | 记忆列表 |
| `DELETE /api/memory/{id}` | 删除记忆 |
| `DELETE /api/memory` | 清空所有记忆 |
| `GET /api/memory/search` | 搜索记忆 |
| `GET /api/tasks/list` | 任务列表 |
| `POST /api/tasks/create` | 创建任务 |
| `DELETE /api/tasks/{id}` | 删除任务 |
| `POST /api/tasks/{id}/cancel` | 取消任务 |
| `POST /api/tasks/{id}/execute` | 立即执行 |
| `GET /api/tasks/stats` | 任务统计 |
| `GET /api/tasks/actions` | 可用动作 |
| `GET /api/system/resources` | 系统资源 |
| `GET /api/skills` | Skill 列表 |
| `POST /api/skills` | 创建 Skill |
| `PUT /api/skills/{id}` | 更新 Skill |
| `DELETE /api/skills/{id}` | 删除 Skill |
| `PATCH /api/skills/{id}/toggle` | 切换 Skill |
| `POST /api/analytics/feedback` | 提交反馈 |
| `GET /api/analytics/stats/{user}` | 统计 |
| `GET /api/analytics/records/{user}` | 反馈记录 |

### 4.4 AuthController

**文件**: `backend/web/src/main/java/com/intelligent/agent/web/controller/AuthController.java`

- `POST /api/auth/login` → 验用户名密码 → 生成 JWT
- `POST /api/auth/logout` → 前端清 token 即可

### 4.5 JWT 认证体系

**AuthProperties** (`config/AuthProperties.java`):
```yaml
auth:
  jwt:
    secret: "ABCDEFGHIJKLMNOPQRSTUVWXYZlinmiaoshusheng123"
    expiry-hours: 24
  users:
    - username: admin
      password: admin123
```

**JwtUtil** (`util/JwtUtil.java`): HS256 签名, subject=username, 24h 过期

**JwtAuthFilter** (`filter/JwtAuthFilter.java`): OncePerRequestFilter
- 白名单: `/api/auth/login`, `/api/auth/logout`, `/ws` (WebSocket upgrade)
- WebSocket: token 从 URL query parameter 取
- REST: token 从 `Authorization: Bearer <token>` 取
- 滑动续期: 已过一半有效期时，response header 带 `X-New-Token`

### 4.6 其他配置

**WebSocketConfig**: 注册 `/ws` 端点，允许所有来源
**AsyncConfig**: 线程池 `streamExecutor` (core=2, max=10, CallerRunsPolicy)
**WebConfig**: CORS 全开, ObjectMapper (JavaTimeModule, NON_NULL), RestTemplate (timeout 600s)

---

## 5. Vue 3 前端层

### 5.1 技术栈

- Vue 3 (Composition API + `<script setup>`)
- Pinia 状态管理
- Vue Router 4
- Element Plus UI
- marked (Markdown 渲染)
- highlight.js (代码高亮)
- Vite 4 构建

### 5.2 路由

```javascript
/login   → LoginView (public)
/chat    → ChatView (懒加载, title: 聊天)
/tools   → ToolsView (懒加载, title: 工具管理)
/skills  → SkillView (懒加载, title: Skill 管理)
/tasks   → TasksView (懒加载, title: 任务管理)
/memory  → MemoryView (懒加载, title: 记忆管理)
/system  → SystemView (懒加载, title: 系统信息)
/stats   → StatsView (懒加载, title: 统计分析)
/*       → redirect /chat
```

**路由守卫**: 非 public 页面检查 `localStorage.agent_token`，无 token 跳转 `/login`

### 5.3 Store

#### useAuthStore

- `token` / `username`: localStorage 持久化
- `login(user, pass)`: POST `/api/auth/login` → 存 token
- `logout()`: 清空 localStorage
- `refreshToken(newToken)`: 更新 token (响应拦截器用)

#### useWebSocketStore (核心 store)

**状态**:
- `isConnected`, `messages[]`, `systemInfo`, `lastResponseTime`
- `isMockMode`, `isStreaming`, `streamingIndex`
- `currentModel`, `availableModels`

**连接**:
- `connect(url)`: new WebSocket(url + ?token=xxx)
- 断线自动重连 (5s 后，仅已登录用户)
- 支持 Mock 模式 (`VITE_USE_MOCK=true`)

**消息处理**:
```
connection_established → 标记已连接
system_info → 更新系统信息
thinking → ChatView 显示思考动画
chat_token → appendToken (流式追加)
tool_calls_done → 插入工具调用卡片
chat_done → finalizeStream, 记录响应时间
chat_response → 非流式兼容
error → 显示错误
```

**流式管理**:
- `startStreamMessage()`: 创建 isStreaming=true 的空助手消息
- `appendToken(token)`: 追加到当前流式消息
- `finalizeStream(responseTime)`: 标记流式完成

**模型切换**: `switchModel(modelName)` → REST API → 更新 store

**消息持久化**: 使用 `localStorage` 保存最近 50 条消息，页面刷新/重开后自动恢复

### 5.4 Services

#### api.js

所有 REST API 的封装函数，统一通过 `/api` 前缀 (Vite proxy → Java 后端):

- 自动带 `Authorization: Bearer <token>`
- 自动处理 `X-New-Token` 续期
- 401 自动跳转登录页

#### websocket-mock.js

Mock WebSocket 实现，用于离线开发，实现与真实 WebSocket 相同的接口。

### 5.5 页面视图

| 视图 | 功能描述 |
|------|----------|
| **LoginView** | 用户名/密码输入，渐变色背景，登录后跳转 /chat |
| **ChatView** | 聊天主界面: 消息列表 (Markdown 渲染), 工具调用卡片, 思考动画（等待 3s 后显示已等待秒数计时器）, 输入框, 导出按钮, 点赞/踩反馈; CTX_LIMIT 从 `/api/system/resources` 动态拉取 `ollama_num_ctx` |
| **ToolsView** | 工具卡片网格: 按分类过滤, 显示工具名/描述/状态/分类 |
| **SkillView** | Skill 列表: CRUD, 启用/禁用, 从模板创建 |
| **TasksView** | 任务管理: 创建/删除/取消/立即执行, 状态过滤 |
| **MemoryView** | 记忆管理: 短期/长期切换, 搜索, 删除, 清空 |
| **SystemView** | 系统监控: 服务状态卡片, CPU/内存/磁盘图表, GPU 信息, Ollama 模型, 进程列表, 10 秒自动刷新 |
| **StatsView** | 统计分析: 反馈统计, 响应时间趋势, 工具/Skill 使用统计 |

### 5.6 布局组件

| 组件 | 位置 | 功能 |
|------|------|------|
| **Sidebar** | 左侧 250px | Logo, 导航菜单 (7 项), 用户信息, 退出按钮 |
| **Header** | 顶部 60px | 页面标题, 模型切换下拉, 连接状态, 清空按钮, 移动端汉堡菜单 |
| **StatusBar** | 底部 40px | 当前模型名, 最后响应时间, 消息数量 |

---

## 6. Docker 部署

**文件**: `docker-compose.yml` (4 个服务)

```yaml
ollama:    image: ollama/ollama:latest, port 11434, GPU support
agent:     build: ./agent/Dockerfile, port 8000, depends_on ollama
backend:   build: ./backend/web/Dockerfile, port 8080, depends_on agent (healthy)
frontend:  build: ./frontend/Dockerfile, port 3000→80 (nginx), depends_on backend (healthy)
```

关键环境变量:
- `OLLAMA_BASE_URL=http://ollama:11434` (容器间通信用服务名)
- `PYTHON_SERVICE_BASE_URL=http://agent:8000`
- `SPRING_PROFILES_ACTIVE=docker`

Volume 挂载: chroma-data, chroma-data-longterm, data/, .cache

---

## 7. 依赖清单

### Python (`requirements.txt`)

```
python-dotenv, pydantic>=2.0, pydantic-settings>=2.0
loguru, fastapi, uvicorn
apscheduler (已安装但使用自定义 SimpleTaskScheduler)
ollama, chromadb, duckduckgo-search, sh
可选: sentence-transformers, numpy, psutil, pynvml, pytz
```

### Java (`pom.xml`)

```
Spring Boot 2.7.18 (web, websocket, validation)
Jackson (databind, jsr310)
Apache HttpClient 4.5.13
commons-lang3, commons-io
Lombok, SpringDoc OpenAPI 1.7
jjwt 0.11.5 (api, impl, jackson)
```

### 前端 (`package.json`)

```
vue 3.3, pinia 3.0, vue-router 4.6
element-plus 2.13, marked 18.0, highlight.js 11.11
axios 1.15, sass 1.99
vite 4.4, @vitejs/plugin-vue 4.3
```

---

## 8. 当前架构特征与待完善项

### 8.1 架构优势

1. **三层清晰分离**: AI 逻辑全在 Python，Java 只做网关代理，前端纯展示
2. **Provider 可替换**: 抽象基类 + 云端/本地双 Provider，运行时切换
3. **ReAct + Function Calling 双轨**: FC 成功时用原生解析，失败时降级到文本解析
4. **意图过滤**: 减少工具传递数量，提升 LLM 工具选择准确率
5. **Skills 编排**: 通过注入 system prompt 实现多步骤任务编排，不需要修改 Agent 核心逻辑
6. **MCP 集成**: 通过标准协议接入外部工具，支持 GitHub/Filesystem
7. **双模记忆**: 短期对话 (deque) + 长期语义 (ChromaDB)，自动蒸馏和每日摘要
8. **流式体验**: WebSocket + SSE 实现 token 级实时输出和服务信息实时更新
9. **反馈闭环**: like/dislike 收集 + 统计分析面板
10. **Docker 化**: 一命令启动全部服务

### 8.2 待完善/可扩展点

1. **aPScheduler 冗余**: `requirements.txt` 有 apscheduler，但实际用的是自定义 `SimpleTaskScheduler`，两者共存
2. **Task 隔离**: 任务调度器无持久化，重启丢失所有任务
3. **Memory Embedding 依赖**: 需要 `sentence-transformers`（~1GB 模型），Docker 环境降级用 ChromaDB 内置 embedding
4. **JWT 密钥**: 硬编码在前端和 Java 配置中，生产环境需改为环境变量注入
5. **HTTPS 缺失**: 全 HTTP 明文传输
6. **WebSocket 重连**: 前端有 5s 自动重连，但 token 过期后无自动刷新机制
7. **多用户隔离（部分）**: 记忆按 `user_id` 隔离（短期/长期均已实现过滤），但 `ToolManager`、`MCP` 连接、**模型选择**是全局共享的 — 用户 A 切换模型后用户 B 同步受影响
8. **数据库工具**: `settings.py` 有完整 MySQL 配置项，`DatabaseTool` 已使用，但未暴露动态连接切换接口
9. **ToolManager 中的冗余代码**: 有 `load_tools_from_module` 方法但未使用
10. **日志**: Python 层用 `loguru`，`ollama_provider.py` 中混用了标准 `logging`
11. **TimerTool**: 注册了但从未被 Agent 实际使用，且代码有复制粘贴问题
12. **Task 无持久化**: 调度器重启后所有任务丢失（仅内存存储）

### 8.3 关键数值配置速查

| 参数 | 值 |
|------|-----|
| ReAct 最大迭代次数 | 5 |
| 短期记忆最大容量 | 100 条 |
| 短期记忆 TTL | 24 小时 |
| 长期记忆压缩阈值 | 500 条 → 400 条 |
| 工具结果截断 | 3000 字符 |
| 聊天超时 | **300 秒**（CPU 推理大模型适配） |
| 上下文 token 预算 | **7000**（留 ~1200 token 给输出） |
| 记忆清理检查间隔 | 15 分钟 |
| 任务调度检查间隔 | 2 秒 |
| 任务失败重试 | 3 次，间隔 10 秒 |
| 已完成任务清理 | 1 小时后自动删除 |
| Embedding 维度 | 384 |
| ChromaDB 相似度距离 | cosine |
| 意图过滤阈值 | 0.30 (余弦相似度) |
| 意图过滤 Top-K | 3 |
| JWT 有效期 | 24 小时 |
| 滑动续期触发 | 剩余时间小于一半 |

---

> 本文档覆盖了项目全部源码文件 (包括 `__init__.py`、`__pycache__` 以外的所有功能性文件)。
> 总计分析源文件: ~50 个 Python 文件, ~15 个 Java 文件, ~20 个前端文件。
> 后续规划时可直接引用本文档中的模块名、类名、方法签名和数据结构。

---

## 9. 规划中能力项（路线图）

> 本节记录经过需求分析确认的能力方向，尚未实现。按优先级排序。

### 9.1 模型选择多用户隔离（阻断串台）

**现状缺口**: `provider` 和 `agent.model` 是全局单例，任一用户切换模型后所有用户受影响。

**目标**: 每个 `user_id` 维护独立的模型偏好，请求时按用户 JWT sub 动态路由到对应 provider 实例。

**设计要点**:
- `settings.py` 或数据库存储 `user_model_prefs: Dict[user_id, model_name]`
- `fastapi_app.py` 在 `/api/model/switch` 时写入用户偏好，`/api/chat/stream` 时读取对应模型
- ToolManager / MCP 连接可继续共享（工具无状态），仅 provider + model 隔离
- 预计工作量: **1 天**

### 9.2 自定义角色层（统一人格 + MD 管理）

**现状缺口**: 人格定义硬编码在 YAML 模板中，仅支持 per-model 配置，无法跨模型共用同一角色，也无 UI 管理。

**目标**: 引入独立的角色（Persona）概念，与模型解耦；角色用 Markdown 定义，支持多角色切换；dolphin 等模型叠加专属覆盖层。

**设计要点**:

```
agent/personas/
  ├── default.md        ← 默认角色（专业助理）
  ├── creative.md       ← 创意角色（头脑风暴风格）
  └── technical.md      ← 技术角色（代码/架构专家）

system prompt 组装顺序:
  1. persona.md 正文（通用人格基础）
  2. model overlay（model-specific YAML，如 dolphin 覆盖无限制行为）
  3. _build_tools_prompt_for()（运行时工具列表注入，仅 text-tool 模式）
```

- `settings.py` 新增 `active_persona: str = "default"`，支持 `.env` / API 切换
- `prompt_manager.py` 扩展：先读 `personas/{active_persona}.md`，再叠加 model overlay YAML
- Python 新增 `/api/personas` CRUD 端点，Java HealthController 代理
- 前端 Header 下拉新增角色切换（与模型切换并列）
- 预计工作量: **1.5 天**

### 9.3 自动记忆提炼（越用越智能）

**现状缺口**: 长期记忆蒸馏（`_distill_short_term_memories`）仅在每天凌晨 2 点触发，且需要用户手动点"提炼知识"按钮激活；普通对话中的偏好/事实不会自动沉淀。

**目标**: 每 N 轮对话（如每 20 轮）异步触发一次增量蒸馏，将本轮对话中的结论性内容、偏好、事实自动写入该用户的长期记忆。

**设计要点**:
- `agent.py` 在 `chat()` / `chat_stream()` 末尾维护 per-user 计数器 `_distill_counter: Dict[user_id, int]`
- 计数达阈值时 `asyncio.ensure_future(_distill_for_user(user_id))`（不阻塞当前请求）
- 蒸馏提示词提取五类内容: preferences（偏好）、personal_info（个人信息）、frequent_topics（常见话题）、behavior_patterns（行为模式）、factual_knowledge（陈述性知识）
- 写入 `long_term` 时 `metadata.user_id = user_id` 保持隔离
- 预计工作量: **1 天**

### 9.4 历史话题与阶段性摘要导出

**现状缺口**: 无对话历史按时间段/主题聚合能力，无 Markdown 摘要生成与导出接口。

**目标**: 支持生成某用户某时间段的对话阶段性总结（Markdown 格式），可下载或推送到客户端本地。

**设计要点**:
- Python 新增 `/api/memory/summary` 端点：接收 `user_id` + `start_date` + `end_date`
- 从 `short_term`（最近）和 `long_term`（按时间过滤）聚合对话 → 发给 LLM 生成摘要
- 摘要格式参考: 主题概述 / 关键结论 / 决策事项 / 待跟进问题
- 同时支持导出为文件（`data/summaries/{user_id}/{date}.md`）
- 前端 MemoryView 增加"生成阶段性总结"按钮，支持日期范围选择 + Markdown 预览下载
- 预计工作量: **0.5 天**

### 9.5 可迁移个人客户端（PWA + 本地同步）

**现状缺口**: 前端为纯 Web，仅 localStorage 缓存 50 条消息。服务端迁移机器后，客户端侧无法保留完整的角色定义、对话记忆和摘要 MD 文件。

**目标**: 前端升级为 PWA（渐进式 Web 应用），客户端本地持久化私人状态；服务端可独立迁移机器，重启后客户端通过同步接口恢复上下文。

**客户端本地存储（IndexedDB）**:
- 角色定义 MD（可编辑，优先级高于服务端配置）
- 最近 500 条对话历史（增量 append）
- 阶段性摘要 MD 文件列表
- 用户偏好（模型选择、主题、字体等）

**同步协议**:
```
客户端启动 → POST /api/sync/push  上传本地新增记忆/偏好
           ← GET  /api/sync/pull  拉取服务端新增长期记忆
服务端迁移后 → 重新登录 → 自动 push/pull 完成上下文恢复
```

**实现路径**:
- `vite-plugin-pwa` 注册 Service Worker，支持离线访问
- `idb`（IndexedDB 封装库）替换 localStorage 存储对话历史
- Python 新增 `/api/sync/push` + `/api/sync/pull` 端点（本质是批量写入/读取 long_term memory）
- 前端新增角色编辑器（Markdown 编辑器组件，本地保存 + 上传服务端）
- 预计工作量: **3-4 天**

---

### 能力路线图总览

| 编号 | 能力 | 优先级 | 预计工作量 | 依赖 |
|------|------|--------|------------|------|
| 9.1 | 模型选择多用户隔离 | P0（阻断串台） | 1 天 | 无 |
| 9.2 | 自定义角色层 + MD 管理 | P1（核心体验） | 1.5 天 | 无 |
| 9.3 | 自动记忆提炼 | P2（越用越智能） | 1 天 | 9.1 |
| 9.4 | 历史话题 + 阶段性摘要导出 | P2 | 0.5 天 | 无 |
| 9.5 | PWA 客户端 + 本地同步 | P3（迁移能力） | 3-4 天 | 9.2、9.4 |

> 本文档覆盖了项目全部源码文件 (包括 `__init__.py`、`__pycache__` 以外的所有功能性文件)。
> 总计分析源文件: ~50 个 Python 文件, ~15 个 Java 文件, ~20 个前端文件。
> 后续规划时可直接引用本文档中的模块名、类名、方法签名和数据结构。
