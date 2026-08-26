# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Working agreement

Before implementing any feature, behavior change, or code modification, present a concise
plan first (scope, approach, key tradeoffs) and wait for the user's confirmation. Do not
write or edit code until the user approves the plan. Read-only work (inspection, diagnosis,
status reports, answering questions) is exempt, but any code change — even incidental —
still requires the plan-first gate. If a request has multiple reasonable directions,
propose the recommended option and ask the user before starting.

## Project overview

Local-first intelligent agent platform (Java-only since 2026-08-08; the Python FastAPI agent
and Python CLI were retired and their source removed). All AI logic now lives in the Java
Spring Boot backend; Ollama serves local inference; ChromaDB was replaced by a file-persisted
vector repository (real embeddings via Ollama `nomic-embed-text` when available, falling back
to n-gram hashing).

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
`AI_PROJECT_CONTEXT.md` first — it's written specifically for LLM context-loading.
`README.md` has user-facing setup/ops instructions in Chinese. `TODOS.md` tracks open work items.
Python-era migration artifacts under `docs/migration/` were removed on 2026-08-23
(recoverable from git history); the migration reconciliation fixture lives in
`backend/web/src/test/resources/migration/export`.

This repo also vendors an unrelated set of frontend design skills under `skills-src/`
(ui-design, accessibility, color-theory, etc.) — these are Claude Code skills, not part of
the agent application itself.

## Commands

### Java backend (`backend/web/`) — requires JDK 21 (`D:\software\jdk21\jdk-21.0.12+8`)

```bash
cd backend/web
./mvnw.cmd spring-boot:run     # Windows; JWT_SECRET / ADMIN_PASSWORD env required
./mvnw.cmd test                # full suite (~566 tests)
./mvnw.cmd package
```

`start_java_mode.bat` reads `JWT_SECRET`/`ADMIN_PASSWORD` from the root `.env` and starts the
Java backend (Java-only; the Python service and its rollback paths have been removed).

### Java CLI client (`client/`)

```bash
cd client
../backend/web/mvnw.cmd package -DskipTests
java -jar target/client-1.0-SNAPSHOT.jar login --username admin --password <pw>
java -jar target/client-1.0-SNAPSHOT.jar chat "你好"
java -jar target/client-1.0-SNAPSHOT.jar repl
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

### E2E tests (`tests/e2e-java/`, requires backend + Ollama running)

Java E2E suite (JUnit + JDK HttpClient, 2026-08-15 起替代退役的 Python pytest E2E) —
black-box REST tests against the Java backend only; backend unreachable → classes skip.

```bash
cd backend/web && ./mvnw.cmd -f ../../tests/e2e-java/pom.xml test
```

### Full stack startup order

Ollama → Java backend → Frontend. `start_all.bat` / `start_all.sh` automate this;
`docker compose up -d --build` for containerized startup (`--profile local` adds
Ollama+ComfyUI, `--profile https` adds TLS via nginx).

## Architecture

### Why a single Java tier (post-migration)

The Java backend is now self-contained: WebSocket session management, JWT auth (validated at
WS handshake via `JwtHandshakeInterceptor` and REST via `JwtAuthFilter`), ReAct orchestration,
memory, tools, persona/prompt/soul, scheduling, IM channels, and all domain APIs. Every AI
feature is implemented in `backend/web/src/main/java/com/intelligent/agent/web/`.

### Core agent (`ai/agent/`, `ai/llm/`)

`AgentOrchestrator` is the ReAct orchestrator:
- `AgentRequestContext` — per-request isolation (userId, message, model, persona, projectId,
  sessionId, tools/memory flags, channel, image base64, group-scene markers).
- `LlmProviderRouter` — routes by requested model: local `OllamaLlmProvider` by default,
  `OpenAiCompatibleLlmProvider` for configured cloud models.
- Tool loop: first non-streaming call → `ToolExecutor` executes parsed tool calls
  (native function calling or text-tool parsing for dolphin/phi2/orca-* prefixes) →
  loop (max 5 rounds) → final streaming answer as SSE/WS `ModelEvent`s.
- `BranchFailureDetector` — 6 failure signals + iron-rule violation scan terminate the loop.

### Memory system (`ai/memory/`)

- Short-term: in-process deque per user (TTL 24h, last 100 messages).
- Distillation: every 5 turns `MemoryDistillationService` extracts facts via LLM
  (`LlmExtractionService`), falling back to rule-based extraction; every 10 turns writes a
  session summary; with a `project_id`, every 8 turns LLM-extracts project nuggets into
  `project` memory records, injected back as `[PROJECT CONTEXT]`.
- Long-term retrieval: `VectorMemoryRepository` (in-memory) uses `EmbeddingService`
  (Ollama `/api/embed`, default `nomic-embed-text`, 768-dim) with n-gram hash fallback.
- `SemanticResponseCache` — persona/model-scoped exact + semantic cache (24h TTL).

### Project / task system (`domain/`)

Each project has a Markdown spec and a task tree. The LLM marks completion by emitting
`[TASK_DONE:<id>]` / `[TASK_BLOCKED:<id>]` in its reply; the stream turns these into
`task_update`/`task_blocked` events consumed by the frontend.

### Persona / prompt / soul system (`ai/prompt/`)

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
  Controllers under `controller/` are thin facades over local domain services; no Python
  proxying exists anymore.
- Frontend navigation has a single source of truth:
  `frontend/src/config/routes.config.js` (`NAV_ITEMS`/`CONFIG_ITEMS`/`SYSTEM_ITEMS`) — both
  the sidebar and mobile header read from it, so new pages only need one edit there.
- Destructive UI confirmations must use the custom `useConfirmDialogStore`, never
  `window.confirm`/`alert` — those are silently swallowed in PWA/WebView contexts in this app
  (caused a recurring bug previously).
- New AI/server logic goes in the Java backend; do not reintroduce a Python service without
  an explicit decision to restore the retired stack.
