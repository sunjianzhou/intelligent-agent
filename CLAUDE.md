# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a three-tier intelligent agent system:
- **Python Agent** (`agent/`): FastAPI service on port 8000 — handles all AI logic (Ollama LLM, tool execution, memory, task scheduling)
- **Java Backend** (`backend/web/`): Spring Boot service on port 8080 — WebSocket gateway and HTTP proxy to the Python service
- **Vue 3 Frontend** (`frontend/`): Vite app — chat UI with panels for tools, memory, tasks, and system info

The frontend connects to Java via WebSocket for streaming chat. Java proxies REST calls and SSE streams from the Python service. All AI-side logic lives exclusively in Python.

## Commands

### Python Agent (`agent/`)
```bash
# Install dependencies
pip install -r requirements.txt
# Install with dev tools
pip install -e ".[dev]"

# Start the service (run from agent/ directory)
python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload

# Run tests
pytest tests/
# Run a single test file
pytest tests/test_foo.py -v

# Lint / format
black .
isort .
pylint .
mypy .
```

### Java Backend (`backend/web/`)
```bash
# 使用项目内置的 mvnw wrapper（指向 E:\workspace\llm\mock_webflux\maven-dist）
./mvnw spring-boot:run
./mvnw package
./mvnw test
./mvnw compile   # 仅编译，不运行
```

### Frontend (`frontend/`)
```bash
npm install
npm run dev      # dev server (Vite)
npm run build    # production build
npm run preview  # preview build
```

## Architecture

### Python Agent internals

`api/fastapi_app.py` is the entry point. On startup it initialises a singleton `OllamaProvider` (wraps local Ollama), then constructs `IntelligentAgent` (`core/agent.py`). If Ollama is unreachable, the app degrades gracefully to direct provider calls.

Key REST + SSE endpoints exposed by the Python service:
- `POST /api/chat` — non-streaming chat (used by REST clients / Feishu)
- `POST /api/chat/stream` — SSE streaming chat (consumed by Java backend)
- `GET/DELETE /api/memory/*` — memory CRUD
- `GET/POST/DELETE /api/tasks/*` — task scheduler CRUD
- `GET /api/tools/list`, `/api/models`, `/api/model/switch`

**Tools** (`tools/`): `ToolManager` maintains two dicts (`tools`, `async_tools`) keyed by name. Register via `register_tool(BaseTool, category)` or `register_function(callable)`. `FunctionTool`/`AsyncFunctionTool` auto-derive parameter schemas from function signatures. Tools are also registered as scheduler actions so tasks can call any tool by name.

**Memory** (`memory/`): `MemoryManager` routes writes by category:
- `conversation`/`task` → `ShortTermMemory` (in-process deque, TTL 24 h, max 100 entries)
- `knowledge`/`fact`/`preference` → `LongTermMemory` (ChromaDB vector DB, persisted to `./chroma_data`, embedding model `all-MiniLM-L6-v2`)

`get_context_for_query()` combines recent conversations + semantic search results into a single context string injected into the LLM prompt.

**Scheduler** (`scheduler/`): `SimpleTaskScheduler` runs a background thread checking every 2 s. Supports four schedule types: `immediate`, `delay` (seconds), `interval` (recurring), `datetime` (one-shot at ISO timestamp). `TaskManager` wraps it and pre-registers all tools as callable actions.

### Java Backend internals

`WebSocketController` (extends `TextWebSocketHandler`) accepts `chat_message`, `ping`, and `get_system_info` message types. For chat it calls `AgentService.streamChatAsync()`, which submits a thread-pool task that reads the Python SSE stream line-by-line and re-emits typed WebSocket messages (`thinking`, `chat_token`, `tool_calls_done`, `chat_done`, `error`) to the frontend session.

`AgentService` is the only bridge to Python. Configure `PYTHON_SERVICE_BASE_URL` (default `http://localhost:8000`) and `LOG_LEVEL` via environment variables or `application.yml`.

### Frontend internals

Vue 3 + Pinia + Vue Router + Element Plus. State is split into Pinia stores under `src/stores/`. The WebSocket connection is managed in `src/services/websocket.js` (a mock variant exists at `websocket-mock.js` for offline development). REST calls go through `src/services/api.js`, all prefixed with `/api` (proxied by Vite in dev, served by Java in production).

Views: `ChatView`, `ToolsView`, `MemoryView`, `TasksView`, `SystemView`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `PYTHON_SERVICE_BASE_URL` | `http://localhost:8000` | Java → Python service URL |
| `LOG_LEVEL` | `INFO` | Java log level |

Python settings are in `agent/config/settings.py` (backed by pydantic-settings; read from `.env`). Key settings include `ollama_temperature`, `ollama_max_tokens`, `api_host`, `api_port`.

## Startup Order

1. Start Ollama (`ollama serve`) with at least one model pulled (e.g., `ollama pull dolphin`)
2. Start Python agent (`uvicorn` in `agent/`)
3. Start Java backend (`mvn spring-boot:run` in `backend/web/`)
4. Start frontend dev server (`npm run dev` in `frontend/`)

Optional dependencies (not in core `requirements.txt`): `feishu` extra adds Feishu/Lark integration; `wechat` extra adds WeChat support.
