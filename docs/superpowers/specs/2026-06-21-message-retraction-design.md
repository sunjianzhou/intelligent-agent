# 消息撤回功能设计

> 状态：已通过分段确认，待用户审阅本文档
> 范围：Python agent / Java backend / Vue frontend / Python CLI client 四端

## 1. 背景与目标

当前 agent 没有任何机制能在事后清理已经发出、但被判断为"答错了/识别错了意图"的消息。这类消息一旦产生：

1. 永久留在前端聊天窗口和对话历史 JSON 里，污染上下文展示
2. 已经写入 `agent/memory/short_term.py` 的内存 deque（关键：`build_context()` 从这里取 `recent_conversations` 注入下一轮 prompt），意味着这条"错误回复"会被当作历史上下文继续喂给 LLM，造成噪音累积，在做摘要/压缩（蒸馏）时可能被放大

目标：用户可以手动选中本次对话窗口内的任意历史消息（user 或 assistant），将其**真正从存储中删除**（对话 JSON + 短期记忆），同时在聊天窗口里留下清晰的视觉痕迹（撤回操作本身不应被悄悄抹去）。

**触发方式**：纯用户手动触发，不做 Agent 自主判断撤回（避免每轮额外增加意图自检的推理负担，影响正常响应效率）。

## 2. 不做的事 / 已知边界

- **不回溯清理长期记忆（ChromaDB 蒸馏摘要）**：蒸馏后的内容是多条短期记忆的摘要，不是原文，技术上无法可靠地把一条长期记忆条目对应回某条具体消息。如果撤回发生在蒸馏间隔触发之后，原始噪音可能已经被摘要进长期记忆里，这次不处理。
- **不做存储层"软删除/占位"**：被撤回的消息从对话 JSON 的 `messages[]` 数组里完全移除，不留 tombstone。这意味着：
  - 当前实时会话窗口里，撤回后看到的"已撤回"灰色占位条只存在于前端内存（`messages` 数组）里
  - 如果之后用户刷新页面 / 重新从历史会话列表加载该会话，后端返回的 JSON 已经没有这条记录，占位条不会再出现——消息前后自然衔接，如同它从未存在过
- **client（CLI）不支持撤回升级前产生的旧消息**：旧版本存的消息没有 `id` 字段，无法对应到后端做精确删除，提示用户该消息无法撤回。
- **CLI 终端输出不可逆**：撤回不会改写已经打印到屏幕上的历史行，只影响下一次 `!history` 的展示和本地/服务端存储。

## 3. 跨层架构总览

```
chat_router.py (生成 user_message_id / assistant_message_id)
  ├─ agent.chat() / agent.chat_stream(message_id=, assistant_message_id=)
  │     └─ conversation_flow.py: store_conversation(..., metadata={"message_id": ...})
  │           └─ short_term.py: MemoryItem.metadata["message_id"]
  └─ _append_messages(... messages-with-id ...) → conversations/{user}/{session}.json

撤回请求：
frontend / client → POST /api/conversations/{session_id}/retract {message_ids:[...]}
  (Java ConversationsProxyController.retract → proxyPost 透传)
  → conversations_router.py:
       1) JSON messages[] 按 id 过滤掉对应条目，保存
       2) agent.memory.short_term.delete_by_ids(message_ids) 级联清理内存记忆
  → 返回 {success, deleted_ids, memory_purged}
```

## 4. 后端改动明细

### 4.1 消息 ID 生成与传递

- `agent/api/chat_router.py`：
  - `/api/chat`：在调用 `_state.agent.chat(...)` 前生成 `_user_msg_id = str(uuid.uuid4())`、`_assistant_msg_id = str(uuid.uuid4())`；作为 `message_id=`、`assistant_message_id=` 传入 `agent.chat()`；`_append_messages` 写入的 `_user_msg`/assistant 消息体都带上 `"id"`；响应 JSON 增加 `user_message_id`/`assistant_message_id` 字段
  - `/api/chat/stream`：同样生成两个 id 并传入 `agent.chat_stream(...)`；在收到 `event_type == "done"` 时，把这两个 id 合并进 `data` dict 后再序列化成 SSE（`data = {**data, "user_message_id": ..., "assistant_message_id": ...}`）；`_append_messages` 调用的消息体同样带 `"id"`

- `agent/core/conversation_flow.py`：
  - `chat()` / `chat_stream()` 签名各新增 `message_id: Optional[str] = None`、`assistant_message_id: Optional[str] = None`
  - `_build_messages_async()` 新增 `message_id` 形参，调用 `self.memory.store_conversation("user", message, user_id=user_id, metadata={"message_id": message_id} if message_id else None)`
  - 两处调用 `_build_messages_async` 的位置（`chat()` 内、`chat_stream()` 内）都传入 `message_id=message_id`
  - 5 处 `store_conversation("assistant", full_response/cleaned, user_id=user_id)` 调用全部追加 `metadata={"message_id": assistant_message_id} if assistant_message_id else None`

- `agent/memory/manager.py`：`store_conversation()` 已支持 `metadata` 形参合并，无需改动。

### 4.2 短期记忆按 id 精确删除

`agent/memory/short_term.py` 新增：

```python
def delete_by_ids(self, message_ids: list[str]) -> int:
    """按 metadata['message_id'] 精确匹配删除，返回实际删除条数。"""
    ids_set = set(message_ids)
    targets = [
        mid for mid, m in self.memories.items()
        if m.metadata.get("message_id") in ids_set
    ]
    for mid in targets:
        self._remove_memory(mid)
    return len(targets)
```

### 4.3 撤回端点

`agent/api/conversations_router.py` 新增：

```python
@router.post("/api/conversations/{session_id}/retract")
async def retract_messages(session_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    body = await request.json()
    target_ids = set(body.get("message_ids") or [])
    if not target_ids:
        return {"success": True, "deleted_ids": [], "memory_purged": 0}

    session = _load_session(user_id, session_id)
    if session is None:
        return {"success": True, "deleted_ids": [], "memory_purged": 0}

    kept, removed = [], []
    for m in session["messages"]:
        (removed if m.get("id") in target_ids else kept).append(m)
    session["messages"] = kept
    session["updated_at"] = datetime.now().isoformat()
    _save_session(user_id, session)

    purged = 0
    if removed and _state.agent:  # 通过模块级 _state 访问全局 agent 实例
        removed_ids = [m["id"] for m in removed if m.get("id")]
        purged = _state.agent.memory.short_term.delete_by_ids(removed_ids)

    return {
        "success": True,
        "deleted_ids": [m.get("id") for m in removed],
        "memory_purged": purged,
    }
```

> 需要从 `api.state` 模块导入 `_state`（参考 `config_router.py` 里 `_state.agent` 的现有用法）。找不到 session 或 id 不在数组里时不报错，视为"已经不存在等同已撤回"。

### 4.4 Java 代理

`backend/web/.../ConversationsProxyController.java` 新增：

```java
@PostMapping("/api/conversations/{sessionId}/retract")
public ResponseEntity<Map<String, Object>> retractMessages(
        @PathVariable String sessionId, @RequestBody Map<String, Object> body,
        HttpServletRequest req) {
    return proxyPost("/api/conversations/" + sessionId + "/retract", body, req);
}
```

## 5. 前端改动明细（frontend/src/views/ChatView.vue 等）

- **入口**：消息列表工具条新增「撤回」图标按钮，点击切换 `retractMode = true`
- **撤回模式渲染**：`role === 'user' || role === 'assistant'` 的气泡左侧渲染勾选框（绑定到一个 `Set<id>` `selectedRetractIds`）；`tool_calls`、`system`/`notif` 消息不可选
- **底部浮层**：撤回模式激活时显示「已选 N 条　取消　确认撤回」工具条
- **确认**（复用 `useConfirmDialogStore`，禁止 `window.confirm`）：
  - N=1：`确认撤回这条消息？此操作将从存储中永久删除，无法恢复。`
  - N>1：额外追加 `⚠️ 同时撤回多条消息可能造成对话上下文不连贯，请确认这些消息之间没有被后续内容依赖引用。`
- **执行**：调用新增 API `retractMessages(sessionId, ids)` → `POST /api/conversations/{sessionId}/retract`；成功后，对应消息对象保留在 `messages` 数组中，但 `content` 清空、`isRetracted = true`；不可再点赞/复制/分支；渲染为灰色斜体占位条「该消息已被撤回」；退出撤回模式
- **id 回填**：
  - `sendMessage()` 发出后，`finalizeStream()`（流式）/ 非流式响应处理逻辑里读取 `data.user_message_id` / `data.assistant_message_id`（经 Java `chat_done` 透传），回填到刚 push 的 user 消息对象和当前 streaming 的 assistant 消息对象的 `id` 字段，覆盖本地 `genId()` 临时值
  - `websocket.js` 的 `chat_done` case：在 `finalizeStream(responseTime)` 调用时多传 `data.user_message_id, data.assistant_message_id`
- **历史加载路径**（`loadSession()`、`openSessionSignal` watcher）：改用后端返回的 `m.id`（已被持久化的真实 id）而不是 `genId()` 现生成

### 5.1 API 封装

`frontend/src/services/api.js` 新增：

```js
export const retractMessages = (sessionId, messageIds) =>
  request(`/api/conversations/${sessionId}/retract`, {
    method: 'POST',
    body: JSON.stringify({ message_ids: messageIds }),
  })
```

## 6. Client（CLI）改动明细

- `client/session.py`：
  - `add_user(content, msg_id=None)` / `add_assistant(content, tool_calls=None, msg_id=None)`：写入字典时若 `msg_id` 非空则带上 `"id"` 字段
  - 新增 `retract(message_ids: list[str]) -> int`：从 `self.messages` 过滤移除匹配 id 的条目，调用 `_persist()`，返回删除条数
- `client/api.py`：
  - `chat()` / `chat_stream()` **不需要改动**——两者都是对后端 JSON/SSE payload 的透明转发（`chat()` 直接 `return r.json()`；`chat_stream()` 直接 `yield json.loads(payload)`），后端新增的 `user_message_id`/`assistant_message_id` 字段会自动随之传递到调用方，无需额外解析逻辑
  - 新增 `retract_messages(session_id: str, message_ids: list[str]) -> dict`：`POST {base_url}/api/conversations/{session_id}/retract`
- `client/repl.py`：
  - `!history` 输出加序号前缀 `[N]`
  - `stream_response()`：遍历 `client.chat_stream(...)` 时，对 `event["type"] == "done"` 的事件读出 `event["data"].get("user_message_id")` / `event["data"].get("assistant_message_id")`，连同已有的 `(text, tool_calls)` 一并返回给调用方（非流式路径直接从 `client.chat(...)` 的返回 dict 里取同名字段）
  - 主循环里调用 `session.add_user(line, msg_id=user_msg_id)` / `session.add_assistant(text, tool_calls, msg_id=assistant_msg_id)`，把拿到的 id 落到本地 session
  - 新增命令 `!retract <编号,编号,...>`：
    1. 解析编号，映射到 `session.recent(10)` 对应条目
    2. 过滤掉没有 `id` 的旧消息，并提示"该消息无法撤回（旧版本数据，缺少 id）"
    3. 打印待撤回内容预览，`input()` 二次确认（N>1 时追加上下文连贯性警告文案）
    4. 调用 `client.retract_messages(session_id, ids)` + `session.retract(ids)`
    5. 打印「已撤回 N 条消息」
  - `!help` 文案补充一行：撤回不会改写已打印的历史行，仅影响后续 `!history` 展示和存储内容

> CLI 当前默认不传 `session_id` 给后端（`chat()`/`chat_stream()` 里没有这个字段），后端按 `request.session_id or user_id` 兜底，相当于以 `user_id` 作为隐式 session id。本次不改这个行为；`retract_messages` 调用时直接传 `self._user_id` 作为 `session_id`，与现有隐式行为保持一致。

## 7. 测试要点

- 单元测试：`ShortTermMemory.delete_by_ids()` 精确删除、不存在的 id 不报错
- 单元测试：`conversations_router.retract_messages` 端点：JSON 正确过滤、agent 不可用时不抛异常
- 集成测试：完整一轮 chat → 拿到 id → 调 retract → 确认 JSON 文件和 short_term 都已清除
- 前端手测：撤回模式勾选/取消/确认/N>1 警告文案/占位条渲染/不可操作态
- CLI 手测：`!retract` 单选/多选/旧消息报错路径

## 8. 文件改动清单

| 文件 | 改动类型 |
|---|---|
| `agent/api/chat_router.py` | 生成并传递 message_id，响应体/SSE 附带 id |
| `agent/core/conversation_flow.py` | `chat()`/`chat_stream()`/`_build_messages_async()` 新增 message_id 形参与传递 |
| `agent/memory/short_term.py` | 新增 `delete_by_ids()` |
| `agent/api/conversations_router.py` | `append_messages()` 自动赋 id；新增 `POST .../retract` 端点 |
| `backend/web/.../ConversationsProxyController.java` | 新增 `retract` 代理方法 |
| `frontend/src/services/api.js` | 新增 `retractMessages()` |
| `frontend/src/stores/websocket.js` | `chat_done` 处理读取并回填 message id |
| `frontend/src/views/ChatView.vue` | 撤回模式 UI、勾选、确认、占位条渲染、历史加载用真实 id |
| `client/session.py` | 消息 id 字段、`retract()` |
| `client/api.py` | 新增 `retract_messages()`（`chat()`/`chat_stream()` 本身无需改动） |
| `client/repl.py` | `!history` 加序号，`stream_response()` 读出 id，新增 `!retract` 命令、`!help` 文案 |
