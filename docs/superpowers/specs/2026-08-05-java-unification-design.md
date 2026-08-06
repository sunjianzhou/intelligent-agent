# Java-Only Platform Unification Design

## Goal

Replace the Python Agent service and Python CLI with Java implementations while
keeping the Vue frontend, public HTTP APIs, WebSocket messages, and SSE events
compatible. The former gateway and Agent become one Spring Boot backend; the
CLI remains a separate Java module.

## Scope

In scope:

- Move all Agent behavior from `agent/` into `backend/web/`.
- Replace `client/` Python code with a standalone Java CLI.
- Migrate persistent business data and ChromaDB logical records.
- Remove the Python Agent container and its application dependencies after
  compatibility, data, and rollback checks pass.

Out of scope:

- Rewriting the Vue frontend.
- Replacing Ollama, cloud model providers, or the external ComfyUI application.
  ComfyUI may remain a Python-based external integration; no project-owned
  Python application will remain after migration.
- Changing public API paths or streaming event contracts during migration.

## Target Repository Structure

```text
frontend/                         Vue 3 SPA; preserved
backend/web/                      one Spring Boot backend module
client/                           standalone Java CLI module
agent/                            compatibility source only; deleted at retirement
```

The backend remains one deployable Spring Boot application. It does not make
internal HTTP requests to an Agent service.

## Backend Package Structure

```text
com.intelligent.agent
├─ api/
│  ├─ auth/
│  ├─ chat/
│  ├─ conversation/
│  ├─ role/
│  ├─ project/
│  ├─ task/
│  ├─ knowledge/
│  ├─ model/
│  ├─ system/
│  └─ websocket/
├─ ai/
│  ├─ agent/
│  ├─ llm/
│  ├─ tool/
│  └─ memory/
├─ domain/
│  ├─ role/
│  ├─ conversation/
│  ├─ project/
│  ├─ task/
│  ├─ skill/
│  ├─ knowledge/
│  ├─ analytics/
│  └─ teaching/
├─ integration/
│  ├─ comfyui/
│  ├─ mcp/
│  ├─ feishu/
│  ├─ wechat/
│  └─ telegram/
└─ infrastructure/
   ├─ configuration/
   ├─ persistence/
   ├─ vectorstore/
   ├─ filesystem/
   ├─ scheduler/
   ├─ observability/
   ├─ security/
   └─ migration/
```

`ai` owns all inference behavior. `domain` owns business state and must not
depend on an LLM. Integration clients own protocol-specific behavior and are
only reached through their explicit package interfaces.

## Dependency Rules

```text
api → ai, domain
ai.agent → ai.llm, ai.tool, ai.memory
ai.tool → domain, integration
ai.memory → infrastructure.vectorstore, infrastructure.persistence
domain → infrastructure.persistence, infrastructure.filesystem
integration → infrastructure.configuration, infrastructure.observability
```

No package may use `api` types as its service contract. Controllers translate
HTTP/WS/SSE data into application commands and responses.

## Runtime Behavior

`ai.agent` implements the present ReAct loop: request-scoped model/persona
selection, context construction, a maximum of five tool rounds, branch-failure
recovery, token streaming, and task markers. `ai.llm` provides Ollama and cloud
providers. Native function-calling models use structured tool calls; the four
legacy text-tool formats remain supported until the configured model matrix no
longer needs them.

`ai.memory` implements short-term conversation state, long-term retrieval,
distillation, summaries, project context, and the semantic response cache.
The Java vector-store adapter is the only path through which these capabilities
access vector data.

## Persistent Data and Migration

The current ChromaDB directories are not a Java storage contract. Migration
exports logical records (id, document, metadata, scope, timestamps, importance
and source collection) to versioned JSONL files, validates counts and hashes,
then re-embeds and imports them to the chosen Java vector store. Original
ChromaDB volumes remain read-only until the acceptance period ends.

Existing JSON-backed state—roles, active roles, model preferences, runtime
configuration, tasks, skills, conversations, knowledge manifests, OAuth tokens
and teaching data—gets a versioned importer and post-import reconciliation
report. Sensitive OAuth and credential values are encrypted or externalized
before their Java persistence implementation becomes the source of truth.

## API and Client Compatibility

All existing `/api/*`, `/ws`, and SSE event names/payloads stay stable during
migration. The Java backend replaces `PythonProxyService` and `AgentService`'s
remote Agent calls with local services in phases. The frontend requires no
endpoint-address change.

The new `client/` is a Java CLI using Picocli and Java HTTP Client. It retains
the REPL, one-shot prompt, streaming output, model/persona switching, history,
session persistence, and message retraction. It authenticates through a server
login or scoped CLI token; it never contains the JWT signing secret.

## Delivery and Rollback Strategy

Migration uses contract tests and shadow mode. Java receives mirrored,
non-mutating requests and compares protocol shape, tool trace, and retrieval
results against Python. Side-effecting tool calls and channel delivery are not
double-executed. Feature flags route internal users and then production cohorts
to Java. Every cutover keeps a Python fallback until acceptance criteria pass.

Python removal happens only after data restore rehearsal, full end-to-end
verification, a stable acceptance period, and a documented rollback decision.

## Acceptance Criteria

- The Vue frontend works without API or event-contract changes.
- The Java CLI supports every currently documented Python CLI workflow.
- Chat, streaming, tool calls, roles, conversations, projects, tasks, knowledge,
  image coordination, schedules, and IM channels operate through Java.
- User isolation, JWT enforcement, destructive-tool policy, auditing, retries,
  and channel rate limiting are no weaker than the current implementation.
- Logical data import/reconciliation has no unexplained record loss.
- Docker Compose starts without an `agent` service or a project-owned Python
  runtime after retirement.

## Implementation Decomposition

The implementation must be delivered as three independently usable plans:

1. Java foundation plus AI chat/ReAct/tool compatibility.
2. Memory/RAG, business-domain APIs, scheduling, and channel integrations.
3. Java CLI, shadow verification, cutover, data migration, and Python retirement.
