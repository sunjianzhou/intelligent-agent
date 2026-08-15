# CLAUDE.md

Project guidance for Claude Code agents working in this repository.

## Project overview

Local-first intelligent agent platform (Java-only since 2026-08-08; the Python FastAPI agent
and Python CLI were retired and their source removed). All AI logic now lives in the Java
Spring Boot backend; Ollama serves local inference; vector memory is file-persisted with real
embeddings via Ollama `nomic-embed-text` (n-gram hash fallback).

```
Browser (Vue 3 SPA) / Java CLI
    │  WebSocket (streaming) + REST + SSE
    ▼
Java backend (Spring Boot :8080)        ← the only server: JWT auth, WS management,
    │                                      ReAct orchestration, memory, tools, domain APIs
    ├── Ollama (:11434)                 ← local LLM inference + embeddings
    └── ComfyUI (:8188, optional)       ← image generation (external service)
```

For a deep dive (full module breakdown, API reference, known tech debt by ID), read
`AI_PROJECT_CONTEXT.md` first. `README.md` has user-facing setup/ops instructions in Chinese.
`TODOS.md` tracks open work items. Historical Python-era design notes live under
`docs/migration/` and are kept only as reference.

This repo also vendors an unrelated set of frontend design skills under `skills-src/`
(ui-design, accessibility, color-theory, etc.) — these are Claude Code skills, not part of
the agent application itself.

## Commands

### Java backend (`backend/web/`) — requires JDK 21

```bash
cd backend/web
./mvnw.cmd spring-boot:run     # Windows; JWT_SECRET / ADMIN_PASSWORD env required
./mvnw.cmd test                # full suite (~270 tests)
./mvnw.cmd package
```

`start_java_mode.bat` reads `JWT_SECRET`/`ADMIN_PASSWORD` from the root `.env` and starts the
Java backend (Java-only; Python rollback paths have been removed).

### Java CLI client (`client/`)

```bash
cd client
../backend/web/mvnw.cmd package -DskipTests
java -jar target/client-1.0-SNAPSHOT.jar login --username admin --password <pw>
java -jar target/client-1.0-SNAPSHOT.jar repl
```

### Vue frontend (`frontend/`)

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
npm run build
npm run test
```

### E2E tests (`tests/e2e-java/`, requires backend + Ollama running)

Java E2E suite（JUnit + JDK HttpClient，2026-08-15 替代退役的 Python pytest E2E）：
黑盒 REST 测试，仅测 Java 后端；后端不可达时整类跳过。

```bash
cd backend/web && ./mvnw.cmd -f ../../tests/e2e-java/pom.xml test
```

## Architecture

### Single Java tier (post-migration)

The Java backend is self-contained: WebSocket session management, JWT auth (REST via
`JwtAuthFilter`, WS handshake via `JwtHandshakeInterceptor`), ReAct orchestration, memory,
tools, persona/prompt/soul, scheduling, IM channels, and all domain APIs. Every AI feature is
implemented under `backend/web/src/main/java/com/intelligent/agent/web/`.

### Core agent (`ai/agent/`, `ai/llm/`)

- `AgentOrchestrator` — ReAct loop: build context → `LlmProviderRouter.complete()` →
  `ToolExecutor` executes parsed tool calls (native function calling or text-tool parsing for
  dolphin/phi2/orca-* prefixes) → loop (max 5 rounds) → final streaming answer as
  `ModelEvent` stream (SSE/WS).
- `AgentRequestContext` — per-request isolation (userId, message, model, persona, projectId,
  sessionId, tools/memory flags, channel, image base64, group-scene markers).
- `BranchFailureDetector` — 6 failure signals + iron-rule violation scan terminate the loop.

### Memory system (`ai/memory/`)

- Short-term: in-process deque per user (TTL 24h, last 100 messages).
- Distillation: every 5 turns LLM extracts facts (`LlmExtractionService`, rule-based fallback);
  every 10 turns a session summary; with a `project_id`, every 8 turns LLM extracts project
  nuggets into `project` records, injected back as `[PROJECT CONTEXT]`.
- Long-term retrieval: `VectorMemoryRepository` (in-memory) via `EmbeddingService`
  (Ollama `/api/embed`, default `nomic-embed-text`, 768-dim; n-gram hash fallback).
- `SemanticResponseCache` — persona/model-scoped exact + semantic cache (24h TTL).

### Domain services (`domain/`)

Role / conversation / project / task / knowledge / skill / analytics / teaching services,
each with thin controllers over local domain services (no Python proxying).

### Persona / prompt / soul (`ai/prompt/`)

Soul layer loaded from `soul/` (`SOUL_DIR`), rules/heart via `heart_record` tool;
`SystemPromptBuilder` composes SOUL → tool_overlay → rules (privacy-tiered per channel) →
persona sections; `PromptService` assembles the full system prompt with model-specific
overrides (e.g. unrestricted overlay for dolphin).

### Tools (`ai/tool/`)

Builtin tools: calculator, time, file (whitelisted read-only), shell (command whitelist),
web_search, database (read-only), feishu_calendar, feishu_task, heart_record. MCP tools via
`McpToolRegistry`. All registered in `AgentConfig`.

### Request flow boundaries to know before touching code

- All REST/SSE/WS endpoints live in `backend/web/src/main/java/com/intelligent/agent/web/`.
- Frontend navigation has a single source of truth:
  `frontend/src/config/routes.config.js` (`NAV_ITEMS`/`CONFIG_ITEMS`/`SYSTEM_ITEMS`) — both
  the sidebar and mobile header read from it.
- Destructive UI confirmations must use the custom `useConfirmDialogStore`, never
  `window.confirm`/`alert` — those are silently swallowed in PWA/WebView contexts.
- New AI/server logic goes in the Java backend; do not reintroduce a Python service without
  an explicit decision to restore the retired stack.

## 环境问题：Codex 协作子代理收不到任务正文（2026-08-13 确认）

**症状**：用 `spawn_agent` / `followup_task` 派发的子代理从不执行任务，
只回 "I'm ready to help... What would you like to work on?" 之类的问候。

**根因**：codex-cli 0.146.0 把协作消息正文放进 `encrypted_content` 字段投递，
但接收端子代理的模型上下文没有解密渲染它，只看到空的
`Message Type: NEW_TASK ... Payload:` 包装。中英文、spawn/followup 均复现。
证据在 `~/.codex/sessions/2026/08/13/rollout-2026-08-13T19-55-27-*.jsonl`：
消息对象含 `encrypted_content`，而子代理推理日志为 "The user hasn't sent a
message yet"。

**临时变通**：`fork_turns=all` 会让子代理继承父上下文并自行推断任务，
但不可靠（可能抢父线程正在做的任务，见 diag_b 实验）。不要依赖消息正文
给子代理传任务；关键任务优先本地完成或自审。

**升级进度（2026-08-13）**：`codex update` 已成功升级到 0.147.0
（`~/.codex/packages/standalone/current` 已指向 0.147.0 发布目录）。
注意：安装器曾反复失败，根因是 PATH 里 Git Bash 的 GNU tar 把 Windows 盘符
路径 `C:\...` 当成远程主机（报 "Cannot connect to C: resolve failed"）。
解决：运行升级前把 `%SystemRoot%\System32` 前置到 PATH（System32 自带 bsdtar），
并设 `CODEX_INSTALLER_USE_RELEASES_OPENAI_COM=false` 直连 GitHub（主源 403）。
0.147.0 仅对新会话生效：当前运行中的会话仍是 0.146.0 内存版本，需重启 Codex
后重新 spawn 测试代理验证任务正文是否可达；确认修复后删除本节。
