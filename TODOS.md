# TODOS

这份文件记录已明确但暂未纳入当前 PR 的工作项。每一条都包含足够上下文，任何人拿起来都能知道从哪里开始。

---

## TODO-1: HTTPS/TLS — 生产环境全程 HTTP 明文

**什么**: 当前整个栈（前端 → Java → Python）全程 HTTP。即使完成 JWT 密钥迁移（D2），在网络传输级别，Token 和聊天内容仍为明文。

**为什么**: Token + 聊天内容在明文传输就是 OWASP A02:2021 加密失效风险。如果部署到内网之外或多设备访问，这是必须解决的问题。

**如何实现**: 在 docker-compose 前加一层 Nginx 反向代理，配置 Let's Encrypt 自动证书。后端 Java/Python 无需改动，Nginx 终结 TLS。

**依赖**: D2（JWT 迁到环境变量）完成后再处理。

**当前状态**: 项目属于本地优先开发阶段，暂不影响开发体验。

---

## TODO-12: 性能优化清单（不缩减现有资源配置的前提下）

**背景**: 2026-06-08 与用户讨论"在当前机器条件、不削减给定资源的前提下，代码/架构/机制流程层面是否还有优化空间"，梳理出以下条目，用户已确认"这些建议都可以做"，留作后续逐项落地。按"性价比"大致排序：

### ✅ 已完成 (2026-06-08，已构建部署 ia-agent 并验证)

1. **【优先】Ollama `keep_alive` 显式设置** — 新增 `settings.ollama_keep_alive`（默认 `"30m"`，可通过 `OLLAMA_KEEP_ALIVE` 环境变量覆盖），在 `OllamaProvider` 的 `chat`/`chat_stream_generator`/`chat_with_tools` 三处请求 payload 中都加上 `"keep_alive": settings.ollama_keep_alive`。已通过容器内直接调用验证：请求被 Ollama 正常接受（200，非"unknown field"错误）。
2. **首请求预热** — 新增 `IntelligentAgent._warmup_llm()`（`agent/core/agent.py`），仅当 `self.provider` 是 `OllamaProvider` 时才执行（云端模式下自动跳过），在 lifespan 启动时与 `_warmup_embeddings` 一并调度，发送极短 prompt 让模型预先加载到显存，与 #1 配合发挥最大效果。
3. **`_trim_context` token 计数缓存** — 新增 `_msg_token_count(m)` 辅助方法，把估算结果缓存到消息 dict 的 `_token_count` 字段；`_estimate_messages_tokens`/`_trim_context` 均改用该方法，避免同一轮内 `_build_messages_async → _compress_context → _trim_context` 链路对同一批消息重复计算 `len(content)/2.5`。
7. **长期记忆写入异步化** — 新增 `_store_knowledge_async()`，把 `_distill_short_term_memories`/`_generate_daily_summary`/`_generate_stage_summary` 中原本同步阻塞事件循环的 `self.memory.store_knowledge`/`long_term.store` 调用全部改为 `loop.run_in_executor` 异步执行，解耦"记忆写入"（embedding 编码 + ChromaDB I/O）与对话响应路径。
9. **语义缓存命中率埋点** — 复用 `agent/api/metrics.py` 中已定义但从未调用的 `cache_hits_total`/`cache_misses_total`（label=`level: L1|L2`），在 `agent.py` 聊天主流程的 L1/L2 缓存检查点上分别打点；并在 `SemanticCache.get()` 中为 `similarity` 与 `threshold` 差距 <0.05 的"近似未命中"补充 INFO 级诊断日志，便于后续评估调低阈值是否可行。已通过直接调用验证计数器可正确递增并出现在 `/metrics` 输出中（`cache_hits_total{level="L1"} 1.0` / `cache_misses_total{level="L2"} 1.0`），已构建部署，`/metrics` 已暴露 `cache_hits_total`/`cache_misses_total` 的 HELP/TYPE（具体 label 数据将在真实对话流量经过缓存路径后出现，属 Prometheus Counter 的惰性创建行为，非异常）。

全量测试 78 通过（12 个失败：11 个为 pre-existing pytest-asyncio 插件缺失问题，1 个 `test_cron_scheduler.py::test_every_minute_not_yet` 为按当前秒数判定的计时类 flaky 测试，单独重跑可通过；均与本次改动无关）。

### ⏸️ 暂缓（清单本身标注"需先有证据/统计再决定"，或涉及数据迁移需用户确认）

4. **L1 响应缓存锁优化** — `agent/core/agent.py:108-109`；清单原文"当前并发量下无影响"，需 `inference_concurrency` 调高后出现热点再做。
5. **Java 侧 token 批量转发** — 清单原文"需先有抖动证据再做"。
8. **Scheduler 轮询改事件驱动** — 清单原文"任务量小时无所谓，未来任务变多可以改"；且调度器历史上多次出现并发/绑定 bug（见 [[session_2026-06-06_semaphore_fix]]），改动有风险，待任务量真正增长再做。
6. **✅ ChromaDB 存储改用 Docker 具名卷** — 2026-06-09 完成迁移并验证。
   - 具名卷：`intelligent_agent_agent_chroma_data`（13 文件）、`intelligent_agent_agent_chroma_data_longterm`（5 文件）
   - bind-mount 备份保留于：`agent/chroma-data.bak_20260609_215213`、`agent/chroma-data-longterm.bak_20260609_215213`
   - 注意：中间创建的无前缀卷 `agent_chroma_data`/`agent_chroma_data_longterm` 可按需用 `docker volume rm` 清理（数据已在带前缀的正式卷中）

**如何继续**: #6 已进入方案讨论阶段（见上方草案），下次直接细化每步并执行。#4/#5/#8 待对应触发条件出现再处理。#9 已补埋点，待积累一段时间真实命中率数据后再决定是否调阈值。

---

## ~~TODO-2: ToolManager 全局单例 — 多用户工具状态隔离~~ ✅ 已完成 (2026-05-25)

**方案**: 每个 `IntelligentAgent` 实例调用 `ToolManager()` 创建独享实例（`self.tool_manager = ToolManager()`）；`TaskManager` / `SimpleTaskScheduler` 接受 `tool_manager` 参数注入，调度器工具回退链也走 per-agent 实例。全局 `tool_manager = ToolManager()` 保留供 CLI / 测试单用户场景兼容使用，不被 Agent 引用。

---

## ~~TODO-3: pyproject.toml 中的 feishu/wechat 可选依赖 — 无实现代码~~ ✅ 已确认不存在

当前 `pyproject.toml` 的 `[project.optional-dependencies]` 只有 `dev`，无 feishu/wechat 条目。本 TODO 描述的问题已不存在，无需处理。

---

## ~~TODO-4 ~ TODO-11~~: 三大智能能力实现 ✅ 已完成 (2026-05-24)

_由 /plan-eng-review 于 2026-05-24 生成，2026-05-24 全部实现。_
_完整任务列表见 `~/.gstack/projects/intelligent_agent/tasks-eng-review-20260524-three-features.jsonl`。_

### Phase 0（前置 Spike，解锁所有 end-to-end 测试）

**TODO-4: Java 透传 project_id**
- `ChatRequest.java` 加 `private String projectId`（`@JsonProperty("project_id")`）
- `WebSocketController.handleChatMessage()` 读 `request.get("project_id")` 写入 ChatRequest
- `AgentService.doStreamChat()/chatFull()` body 加 `project_id`
- 文件：`backend/.../dto/request/ChatRequest.java`，`WebSocketController.java`，`AgentService.java`

**TODO-5: Python 接收 project_id**
- `fastapi_app.py` 的 `ChatRequest` Pydantic model 加 `project_id: Optional[str] = None`
- `/api/chat` 和 `/api/chat/stream` 将 `project_id` 传给 `agent.chat()/agent.chat_stream()`
- `agent.py` 的 `chat()`、`chat_stream()`、`_build_messages_async()` 签名加 `project_id` 参数
- 文件：`agent/api/fastapi_app.py`，`agent/core/agent.py`

### Phase 1（上下文持久化 MVP）

**TODO-6: IDB 升级 + useProjectStore**
- `localDB.js` DB_VERSION 1→2，`onupgradeneeded` 创建 `projects` store，新增 CRUD 函数
- `frontend/src/stores/project.js`：新建 `useProjectStore`，CRUD + 离线重试队列
- Project 结构：`{id, title, session_ids, context_version, context_summary, spec, task_tree, synced}`
- 文件：`frontend/src/services/localDB.js`，`frontend/src/stores/project.js`

**TODO-7: ContextExtractor + /api/project/context 端点**
- `agent/memory/context_extractor.py`：类比 `MemoryDistiller`，键为 `{user_id}:{project_id}`，写入 ChromaDB 集合 `project_{id}_context`
- `fastapi_app.py`：`POST /api/project/context/extract`、`GET /api/project/context?project_id=X&query=MSG`
- `agent.py`：`_get_project_context(project_id, message)` 私有方法；`_build_messages_async()` 中注入 `[PROJECT CONTEXT]` 区块
- `sendChatMessage()` 在 `websocket.js` 中接受 `projectId` 参数，ChatView 从 store 读取后传入
- 文件：`agent/memory/context_extractor.py`，`agent/api/fastapi_app.py`，`agent/core/agent.py`，`frontend/src/services/websocket.js`

### Phase 2（规格驱动开发）

**TODO-8: SpecEditor.vue + /api/project/spec 端点**
- `SpecEditor.vue`：textarea + 预览切换，保存时写 IDB + 调 `PUT /api/project/spec`，失败标 `synced:false`
- `fastapi_app.py`：`PUT /api/project/spec`（ChromaDB delete + re-insert）、`GET /api/project/spec`
- `agent.py`：`_spec_turn_counts` 字典，`inject_spec_if_due()` 每 10 轮注入 `[SPEC]` 区块
- 文件：`frontend/src/components/SpecEditor.vue`，`agent/api/fastapi_app.py`，`agent/core/agent.py`

### Phase 3（自主任务分解）

**TODO-9: TaskTree.vue + /api/project/tasks/decompose + [TASK_DONE] 机制**
- `TaskTree.vue`：树形展示，状态 badge，AI 分解按钮，手动添加任务
- `fastapi_app.py`：`POST /api/project/tasks/decompose`（LLM 分解）、`GET /api/project/tasks`
- `agent.py`：`chat_stream()` 末尾扫描 `full_response` 中的 `[TASK_DONE]`（D1=B 方案），yield `task_update`/`task_blocked`，清除 sentinel 字符串
- Java `AgentService`：switch 中处理 `task_update`/`task_blocked` 事件，转发给 WS
- `websocket.js`：处理 `task_update`/`task_blocked`，更新 IDB task_tree
- 文件：`frontend/src/components/TaskTree.vue`，`agent/api/fastapi_app.py`，`agent/core/agent.py`，`backend/.../service/AgentService.java`，`frontend/src/services/websocket.js`

**TODO-10: ProjectView.vue 项目仪表板**
- `/project/:id` 路由：三栏（项目列表 / SpecEditor / TaskTree）
- Sidebar.vue 加入'项目'入口
- 文件：`frontend/src/views/ProjectView.vue`，`frontend/src/router/index.js`，`frontend/src/components/layout/Sidebar.vue`

### 配套测试

**TODO-11: 新功能测试**
- `agent/tests/test_context_extractor.py`：类比 `test_distiller.py`，用 stub ChromaCollection，无 ChromaDB 依赖
- `agent/tests/test_project_endpoints.py`：类比 `test_memory_endpoints.py`，用 `httpx.ASGITransport`，mock LLM 和 ChromaDB

**架构评审关键约束（实现时必须遵守）**:
- ChromaDB 每项目独立集合 `project_{id}_context`，避免全扫描（`long_term` 集合不加 metadata filter）
- spec 更新 = delete(`spec_{project_id}`) + add(`spec_{project_id}_v{version}`)（ChromaDB 无原生更新）
- `_context_extractor.record_turn()` 键必须是 `f"{user_id}:{project_id}"`（按项目隔离，非全局 per-user）
- `_spec_turn_counts` 同上，否则切换项目时注入周期紊乱
- `[TASK_DONE]` 检测前必须从 `full_response` 中清除 sentinel，避免显示给用户
- IDB 版本升级需正确处理 `oldVersion < 2`，不能用 `e.target.result.deleteObjectStore()` 删旧 store
