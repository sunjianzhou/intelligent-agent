# CLAUDE.md

> 最后更新：2026-07-09（W1-W12 全部落地：主人永久铁律 + Channel Adapter 抽象层 + 双通道并行广播）

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A local-first, three-tier intelligent agent platform: Ollama local inference → Python FastAPI agent (all AI logic) → Java Spring Boot gateway (WebSocket + HTTP proxy, zero AI logic) → Vue 3 SPA. A Python CLI client can talk to the agent directly, bypassing Java.

```
Browser / CLI / 飞书 / 企微 / Telegram
    │  WebSocket (streaming) + REST + IM WebSocket 长连接
    ▼
Java backend (Spring Boot :8080)   ← pure gateway: JWT auth, WS management, proxying
    │                                  ChannelAdapterManager: FeishuChannelAdapter + broadcast
    │  HTTP + SSE
    ▼
Python Agent (FastAPI :8000)       ← all AI logic lives here
    │
    ├── Channel Adapter 层          ← 4 channel 统一接口（飞书/企微/Web/Telegram）
    │   ├── ChannelRouter          ← 单通道/广播/去重/fallback/指标聚合
    │   ├── ChannelAdapterFactory  ← 自动发现 4 adapter
    │   ├── ChannelMessageTool     ← LLM 统一 IM 工具（替代 FeishuIMTool）
    │   └── ChannelNotifier        ← 整合通知系统（notify_user / notify_user_sync）
    │
    ├── Ollama (:11434)            ← local LLM inference
    └── ChromaDB (embedded)        ← vector long-term memory
```

For a deep dive (full module breakdown, API reference, known tech debt by ID), read `AI_PROJECT_CONTEXT.md` first — it's written specifically for LLM context-loading and is kept up to date. `README.md` has user-facing setup/ops instructions in Chinese. `TODOS.md` tracks open work items.

This repo also vendors an unrelated set of frontend design skills under `skills-src/` (ui-design, accessibility, color-theory, etc.) — these are Claude Code skills, not part of the agent application itself.

## Commands

### Python Agent (`agent/`) — conda env `python310`

```bash
conda activate python310
cd agent
pip install -e ".[dev]"
python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload

pytest tests/ -v                          # full suite (~370 tests)
pytest tests/test_some_file.py::test_name -v   # single test
black . && isort .                        # formatting (line-length 88, black profile)
```

### Java backend (`backend/web/`)

```bash
cd backend/web
./mvnw spring-boot:run     # mvnw.cmd on Windows
./mvnw test
./mvnw package
```

### Vue frontend (`frontend/`)

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
npm run build
npm run test        # vitest run
npm run test:watch
```

### E2E tests (`tests/e2e/`, requires all services running)

```bash
cd tests/e2e && pytest -v
```

### Full stack startup order

Ollama → Agent → Backend → Frontend (Backend waits on Agent's health check). `start_all.sh` / `start_all.bat` automate this; `docker compose up -d --build` for containerized startup (`--profile local` adds Ollama+ComfyUI, `--profile https` adds TLS via nginx).

## Architecture

### Why three tiers

The Java backend is intentionally a thin, swappable gateway: WebSocket session management, JWT auth (validated at WS handshake via `JwtHandshakeInterceptor`), and HTTP proxying to Python. It contains **no AI logic** — every proxy controller (`RoleController`, `MemoryProxyController`, `TaskProxyController`, etc.) extends `AbstractProxyController` and forwards to Python with the real user ID attached via `X-User-Id`. All intelligence lives in the Python agent.

### Channel Adapter 抽象层（`agent/im/` + `backend/web/im/`）

统一的 IM Channel 抽象层，4 channel（飞书/企微/Web/Telegram）走统一接口，支持多通道并行广播：

**Python 侧**：
- `ChannelAdapter`（ABC） — 统一接口：`send_text()` / `send_card()` / `send_file()` / `send_image()`，内置限流（`TokenBucket`） + 指数退避重试（`RetryConfig`） + 指标（`ChannelMetric`） + HTTP Session 连接池复用
- `FeishuAdapter` / `WeComAdapter` / `WebAdapter` / `TelegramAdapter` — 4 个 adapter 实现，各自独立限流（飞书 text 50/s card 1.67/s，企微 1.67/s，Telegram 30/s，Web 不限流）
- `ChannelRouter` — 单通道发送（`send_to`）、多通道并行广播（`broadcast_text`，asyncio.gather + 失败隔离）、去重（dedup_key）、fallback 降级到 Web、全局单例
- `ChannelAdapterFactory` — 按 `ChannelType` 自动发现并创建 adapter
- `ChannelMessageTool` — LLM 统一 IM 工具，替代旧 `FeishuIMTool`，通过 ChannelRouter 路由
- `ChannelNotifier` — 整合 ChannelRouter 到通知系统（`notify_user` / `notify_user_sync`）
- `GET /health/channels` — 各 channel 状态端点（健康/指标/限流拒绝次数）

**Java 侧**：
- `ChannelAdapter`（interface） + `FeishuChannelAdapter`（委托 `FeishuMessageSender`）
- `ChannelAdapterManager` — Spring Bean，管理 adapter 注册 + `broadcast()` 并行广播
- 数据模型：`ChannelType` / `ChannelMessage` / `SendResult` / `UserInfo` / `RetryConfig` / `TokenBucket` / `ChannelMetric`（与 Python 侧一一对应）

### Python agent core (`agent/core/`)

`IntelligentAgent` (`core/agent.py`) is a thin facade composed of three mixins to avoid a God Class:
- `ConversationFlowMixin` (`conversation_flow.py`) — message building, `chat()`/`chat_stream()`
- `ToolDispatcherMixin` (`tool_dispatcher.py`) — tool registration, intent routing, LLM calls
- `MemoryWriterMixin` (`memory_writer.py`) — warmup, MCP, distillation, cleanup

Per-request isolation (multi-user) is done via `ContextVar`s in `core/_context_vars.py` (model provider, persona, request image base64) rather than instance state, since `ToolManager` and the agent facade are shared.

### ReAct loop

```
_build_messages_async()  → injects short-term memory + long-term semantic recall
                             + project context + task list + spec (every 10 turns)
                             + [HEART 心证铁卷] (user-explicit permanent memories)
                             + [RULES 主人铁律] (non-violable rules with privacy tiers)
_call_model_with_tools()  → first LLM call
  ├── tool calls present → _execute_tool_round() → append results
  │                         ├── per-tool error-graded retry (auth×1 / system×3)
  │                         └── _detect_branch_failure() 6-signal check
  │                              └── triggered → auto-retract 2 rounds + [BRANCH_RESET]
  └── no tool calls       → _stream_tokens_async()  (SSE)
```

Models without native function calling (dolphin, phi2, orca-*) fall back to text-tool parsing, supporting JSON / `<tool_call>` tags / markdown code blocks / plain text.

### Branch failure detection (6 signals)

After each tool round, `_detect_branch_failure()` checks a 5-round window:
1. Same tool + same error ≥3 times
2. LLM output ≥2 consecutive rounds Jaccard similarity >80%
3. User explicit correction ("不对/重来/换个思路")
4. RuntimeError + empty response in same window → immediate
5. Tool retry exhaustion (`_retry_exhausted` flag)
6. **Rule violation** — LLM output matches hardcoded danger patterns (rm -rf, os.system, eval, DROP TABLE, etc.) or rules.md forbidden keywords

Triggered → auto-retract last 2 rounds + inject `[BRANCH_RESET]` system message + loop continues. Max 1 reset per conversation.

### Two-tier memory + heart + cache

Short-term memory is an in-process deque (TTL 24h, last 100 messages). Every 5 turns, `MemoryDistiller` extracts facts into long-term memory (ChromaDB, `all-MiniLM-L6-v2` embeddings); every 10 turns `SessionSummarizer` writes a stage summary. When a `project_id` is present, `ContextExtractor` additionally pulls project-specific nuggets into a per-project ChromaDB collection every 8 turns, injected back as `[PROJECT CONTEXT]`.

**Heart layer** (`soul/heart.md`): User-explicit permanent memory, separate from auto-distilled LTM. `heart_record` builtin tool (append/list/delete) lets the LLM read/write on user command. System prompt injects【心证铁卷】at position ③.5 (after MEMORY, before HEARTBEAT), excluded from external IM channels (feishu_im/wecom).

**Rules layer** (`soul/rules.md`): User-defined non-violable permanent rules (21-rule system). `heart_record` tool extended with `rule_add`/`rule_list`/`rule_delete` actions. System prompt injects【主人铁律】at position ③.6 (after HEART, before HEARTBEAT) with privacy tiers: `public` (all channels), `private` (web/CLI only), `secret` (never injected). Token budget <4096 degrades to critical-only rules. `_check_rule_violation()` serves as a 6th branch failure signal, scanning LLM output for dangerous patterns (rm -rf, os.system, eval, DROP TABLE, curl|sh, etc.).

**Response cache**: L1 exact-match cache (`core/l1_cache.py`, 5min TTL, LRU 100 entries) + L2 semantic cache (`memory/semantic_cache.py`, ChromaDB cosine similarity, 24h TTL). Only active when `use_tools=False` (pure knowledge queries).

### Project system

Each project has a Markdown spec (re-injected as `[SPEC]` every 5 turns) and a task tree. The LLM marks completion by emitting `[TASK_DONE:<id>]` / `[TASK_BLOCKED:<id>]` in its reply text; the SSE stream turns this into `task_update`/`task_blocked` events that the frontend uses to update the task tree.

### Persona system

`.md`/JSON-backed roles in `agent/personas/` (data in `agent/data/roles.json`), hot-reloaded — no restart needed to add or edit a persona. `PromptBuilder` (singleton) composes: persona description → model-specific override layer (e.g. an unrestricted overlay for dolphin) → tool-use instructions (when in text-tool mode). Active persona per user persists to `data/user_persona_prefs.json`.

### Request flow boundaries to know before touching code

- All REST/SSE endpoints are mounted in `agent/api/fastapi_app.py`; feature-specific routers live alongside it (`chat_router.py`, `roles_router.py`, `projects_router.py`, `knowledge_router.py`, `cloud_router.py`, `conversations_router.py`).
- Java never computes anything AI-related — if a feature needs new server logic, it goes in `agent/`, with Java only adding a thin proxy controller plus a WebSocket event type if streaming is involved.
- Frontend navigation has a single source of truth: `frontend/src/config/routes.config.js` (`NAV_ITEMS`/`CONFIG_ITEMS`/`SYSTEM_ITEMS`) — both the sidebar and mobile header read from it, so new pages only need one edit there.
- Destructive UI confirmations must use the custom `useConfirmDialogStore`, never `window.confirm`/`alert` — those are silently swallowed in PWA/WebView contexts in this app (caused a recurring bug previously).
