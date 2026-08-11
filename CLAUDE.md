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

### E2E tests (`tests/e2e/`, requires backend + frontend + Ollama running)

The E2E suite itself is still written in Python (pytest + httpx) but targets the Java backend
only — it no longer talks to any Python service.

```bash
cd tests/e2e && pytest -v
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
