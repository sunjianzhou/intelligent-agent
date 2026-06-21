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

- **长期记忆不做"删除"，只做"召回排除"**：蒸馏后的内容是多条短期记忆的摘要，不是原文，无法物理删除某条具体消息对应的片段（详见第 4.5 节的折中方案：来源追溯 + 硬性排除过滤，而不是简单删除或调权重）。
- **不做存储层"软删除/占位"**：被撤回的消息从对话 JSON 的 `messages[]` 数组里完全移除，不留 tombstone。这意味着：
  - 当前实时会话窗口里，撤回后看到的"已撤回"灰色占位条只存在于前端内存（`messages` 数组）里
  - 如果之后用户刷新页面 / 重新从历史会话列表加载该会话，后端返回的 JSON 已经没有这条记录，占位条不会再出现——消息前后自然衔接，如同它从未存在过
- **client（CLI）不支持撤回升级前产生的旧消息**：旧版本存的消息没有 `id` 字段，无法对应到后端做精确删除，提示用户该消息无法撤回。
- **CLI 终端输出不可逆**：撤回不会改写已经打印到屏幕上的历史行，只影响下一次 `!history` 的展示和本地/服务端存储。

### 2.1 撤回失效场景一览（用户可感知的边界）

| 场景 | 行为 | 原因 |
|---|---|---|
| 撤回发生在蒸馏间隔触发**之前** | 短期记忆 + 蒸馏来源都会被清理，效果完整 | message_id 全链路追踪到位 |
| 撤回发生在蒸馏间隔触发**之后**，但本功能上线之后产生的蒸馏记录 | 长期记忆里对应的摘要条目会被标记排除检索，但**内容本身不会被删除**（只是不再被召回） | ChromaDB 摘要是多条消息的合成内容，物理删除可能误伤其他未撤回的消息 |
| 撤回的消息在**本功能上线之前**就已经被蒸馏过 | 完全不处理，长期记忆里可能仍残留相关摘要 | 旧蒸馏记录没有 `source_message_ids` 字段，无法追溯 |
| SSE 流式响应中途断网/刷新页面，且 Java 兜底空 `chat_done` 没带 id | 前端自动触发一次会话重新拉取来回填 id（见第 5 节）；如果连服务端都没收完整条回复（彻底没持久化），则该消息本来就不存在，无需撤回 | 网络中断导致 id 投递失败，需要二次同步 |
| client（CLI）旧版本产生的消息（无 `id`） | `!retract` 提示该消息无法撤回 | 历史数据没有 id 字段 |
| Java 后端重启后，撤回一条此前通过飞书发出的消息 | 内部存储正常删除；飞书客户端界面上的消息**不会**被联动撤回 | message_id → 飞书 message_id 的映射是纯内存态，重启丢失 |
| 飞书官方撤回接口的时间窗口已过期 | 内部存储正常删除；飞书侧调用会失败但不影响内部删除结果 | 飞书官方限制撤回时限，超出后接口本身会拒绝 |
| 单次勾选 > 50 条 | 前端禁止继续勾选，需分批操作 | 防止单次请求体过大 / 批量误删风险 |

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
  → 返回 {success, requested, deleted, deleted_ids, memory_purged}
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
_MAX_RETRACT_BATCH = 50

@router.post("/api/conversations/{session_id}/retract")
async def retract_messages(session_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    body = await request.json()
    requested_ids = list(dict.fromkeys(body.get("message_ids") or []))  # 去重保序
    if not requested_ids:
        return {"success": True, "requested": 0, "deleted": 0, "deleted_ids": [], "memory_purged": 0}
    if len(requested_ids) > _MAX_RETRACT_BATCH:
        return JSONResponse(status_code=400, content={
            "success": False,
            "message": f"单次最多撤回 {_MAX_RETRACT_BATCH} 条，请分批操作",
        })

    session = _load_session(user_id, session_id)
    if session is None:
        return {"success": True, "requested": len(requested_ids), "deleted": 0, "deleted_ids": [], "memory_purged": 0}

    target_ids = set(requested_ids)
    kept, removed = [], []
    for m in session["messages"]:
        (removed if m.get("id") in target_ids else kept).append(m)
    session["messages"] = kept
    session["updated_at"] = datetime.now().isoformat()
    _save_session(user_id, session)

    removed_ids = [m["id"] for m in removed if m.get("id")]
    purged = 0
    if removed_ids and _state.agent:  # 通过模块级 _state 访问全局 agent 实例
        purged = _state.agent.memory.short_term.delete_by_ids(removed_ids)  # 同步、O(短期记忆容量)，很快
        asyncio.create_task(_suppress_distilled_memories_bg(_state.agent, removed_ids))  # 见 4.5 节，异步不阻塞响应

    return {
        "success": True,
        "requested": len(requested_ids),
        "deleted": len(removed_ids),
        "deleted_ids": removed_ids,
        "memory_purged": purged,  # 仅短期记忆计数；长期记忆排除标记是后台异步的，响应时还没跑完，不在此计数
    }
```

> 需要从 `api.state` 模块导入 `_state`（参考 `config_router.py` 里 `_state.agent` 的现有用法）。找不到 session、id 不在数组里、或 id 已被删过，都不报错——`deleted < requested` 是正常的"部分已不存在"结果，由前端展示提示，不是错误状态。

### 4.4 Java 代理

`backend/web/.../ConversationsProxyController.java` 新增：

```java
@Autowired
private FeishuRecallBridge feishuRecallBridge;  // 见 4.6 节

@PostMapping("/api/conversations/{sessionId}/retract")
public ResponseEntity<Map<String, Object>> retractMessages(
        @PathVariable String sessionId, @RequestBody Map<String, Object> body,
        HttpServletRequest req) {
    ResponseEntity<Map<String, Object>> resp = proxyPost("/api/conversations/" + sessionId + "/retract", body, req);
    feishuRecallBridge.onMessagesRetracted(resp.getBody());  // 失败不影响本次响应
    return resp;
}
```

### 4.5 蒸馏来源追溯 + 长期记忆硬性排除过滤

**不采用"降权"，改用"硬过滤"**——降权（调低 `importance`）只是让该条目在 `calculate_memory_score()` 的综合分数公式里更不容易胜出，但这个公式本身完全可能在未来被调整（比如权重比例变化、加新的因子），届时一个写死的 `importance=0.1` 还能不能压住召回是不确定的，相当于把"是否生效"这件事隐式绑定到了一个未来可能变化的计算公式上。改成显式的排除标记 + 检索时硬过滤，不管打分公式怎么改，被标记的条目永远不会进入候选集，是更稳的做法。

**异步执行，不阻塞 retract 响应**——`agent.memory.long_term.memories` 在长期使用后可能积累到几千条，遍历 + 逐条 `update()`（可能涉及 ChromaDB 的同步 I/O）如果放在 retract 请求的主流程里同步跑，会拖慢撤回操作的响应时间。改成 `asyncio.create_task` 发起的后台任务，内部用 `asyncio.to_thread` 把同步遍历丢进线程池，不占用事件循环：

```python
async def _suppress_distilled_memories_bg(agent, retracted_message_ids: list[str]) -> None:
    """后台异步执行：扫描长期记忆，命中来源 id 的条目标记排除检索。
    不放在 retract 请求主流程里同步跑，避免长期记忆条目较多时拖慢撤回响应。
    """
    try:
        count = await asyncio.to_thread(_suppress_distilled_memories, agent, retracted_message_ids)
        if count:
            logger.info(f"撤回级联：{count} 条长期记忆已标记排除检索")
    except Exception as e:
        logger.warning(f"长期记忆排除标记失败（不影响已完成的内部删除）: {e}")
```

代价：`memory_purged` 字段只反映短期记忆的同步清理结果；长期记忆的排除标记在响应返回时可能还没跑完（通常几百毫秒内完成），前端不需要等待也不需要感知这个结果——它只影响"未来检索是否还会召回"，不影响"这次撤回操作本身是否成功"。

**改动 1：蒸馏时记录来源**

`agent/memory/distiller.py:116-124`，`long_term_memory.store()` 调用补充 `source_message_ids`：

```python
long_term_memory.store(
    content=fact,
    metadata={
        "type": "fact",
        "source": "distillation",
        "user_id": user_id,
        "source_message_ids": [
            m.metadata.get("message_id") for m in batch_items
            if m.metadata.get("message_id")
        ],
    },
    importance=0.75,
)
```

**改动 2：检索时硬过滤**

`agent/memory/long_term.py` 的 `retrieve()`（L493 附近）和 `search()` 统一在拿到候选 `memory` 后加一道闸：

```python
if memory and not memory.metadata.get("excluded_from_retrieval"):
    ...  # 原有打分/收集逻辑
```

**改动 3：撤回时打排除标记（不删除内容）**

`agent/api/conversations_router.py` 新增辅助函数：

```python
def _suppress_distilled_memories(agent, retracted_message_ids: list[str]) -> int:
    """撤回后，把来源命中这些 message_id 的长期记忆标记为排除检索，不物理删除。"""
    retracted = set(retracted_message_ids)
    count = 0
    for memory_id, item in list(agent.memory.long_term.memories.items()):
        source_ids = set(item.metadata.get("source_message_ids") or [])
        if source_ids & retracted:
            agent.memory.long_term.update(memory_id, metadata={"excluded_from_retrieval": True})
            count += 1
    return count
```

> 只对**本功能上线之后产生的新蒸馏记录**生效（旧记录没有 `source_message_ids`，见 2.1 节边界表）。被排除的条目仍然占用存储空间，不做物理清理——这是刻意的取舍：一条摘要可能混合了多条消息的内容，物理删除有误伤其他未撤回消息的风险，硬过滤足以达到"不再污染上下文"的目标。

### 4.6 飞书撤回联动

飞书消息发送本身已经复用 Python `/api/chat` 端点（`AgentService.chatFull()` 注释明确写"非流式（ChatController / 飞书接入用）"），所以对话 JSON 和短期记忆的清理已经自动覆盖飞书场景，**不需要额外处理**。唯一缺的是"让消息在飞书客户端界面上也视觉消失"，这需要主动调用飞书官方撤回 API。

- `FeishuMessageSender.java` 的 `doSend()`（目前返回 `void`，丢弃响应体）改为解析并返回飞书响应里的 `data.message_id`
- 新增 `FeishuRecallBridge`（Java，单例 bean）：
  - 维护一个有上限的内存映射 `ConcurrentHashMap<String, String>`（`our_assistant_message_id → feishu_message_id`），插入时若超过 500 条按最早插入顺序淘汰（LRU 风格，不落盘——和短期记忆一样是易失态，重启即丢）
  - `FeishuEventController` 发送回复成功后，调用 `agentService.chatFull(req)`（而非只取 `.response` 字符串的 `chat()`）拿到 `assistant_message_id`，连同 `doSend()` 返回的飞书 `message_id` 一起存入映射
  - `onMessagesRetracted(Map<String,Object> retractResponse)`：读取 `deleted_ids`，逐个查映射表，命中则调用飞书官方 `POST /open-apis/im/v1/messages/{message_id}/recall`；调用失败（超出飞书撤回时限等）只记日志，不影响已经完成的内部存储删除

## 5. 前端改动明细（frontend/src/views/ChatView.vue 等）

- **入口**：消息列表工具条新增「撤回」图标按钮，点击切换 `retractMode = true`
- **撤回模式渲染**：`role === 'user' || role === 'assistant'` 的气泡左侧渲染勾选框（绑定到一个 `Set<id>` `selectedRetractIds`）；`tool_calls`、`system`/`notif` 消息不可选
- **批量上限**：`selectedRetractIds.size >= 50` 时，其余未勾选气泡的 checkbox 置灰禁用，浮层文案变为「已选 50 条（已达单次上限），请先确认或取消部分选择」
- **底部浮层**：撤回模式激活时显示「已选 N 条　取消　确认撤回」工具条
- **确认**（复用 `useConfirmDialogStore`，禁止 `window.confirm`）：
  - N=1：`确认撤回这条消息？此操作将从存储中永久删除，无法恢复。`
  - N>1：额外追加 `⚠️ 同时撤回多条消息可能造成对话上下文不连贯，请确认这些消息之间没有被后续内容依赖引用。`
- **执行**：调用新增 API `retractMessages(sessionId, ids)` → `POST /api/conversations/{sessionId}/retract`；响应体 `{requested, deleted, deleted_ids, memory_purged}`：
  - 对 `deleted_ids` 命中的消息对象：保留在 `messages` 数组中，但 `content` 清空、`isRetracted = true`；不可再点赞/复制/分支；渲染为灰色斜体占位条「该消息已被撤回」
  - 若 `deleted < requested`：用 `ElMessage.warning` 提示「部分消息已不存在或删除失败（${requested} 条中成功 ${deleted} 条）」，未命中的勾选项保持原样（不清空），让用户知道哪些没生效
  - 退出撤回模式
- **id 回填**：
  - `sendMessage()` 发出后，`finalizeStream()`（流式）/ 非流式响应处理逻辑里读取 `data.user_message_id` / `data.assistant_message_id`（经 Java `chat_done` 透传），回填到刚 push 的 user 消息对象和当前 streaming 的 assistant 消息对象的 `id` 字段，覆盖本地 `genId()` 临时值
  - `websocket.js` 的 `chat_done` case：在 `finalizeStream(responseTime)` 调用时多传 `data.user_message_id, data.assistant_message_id`
  - **断流 fallback（内容前缀匹配优先，位置兜底）**：`finalizeStream()` 执行后，若本轮始终没收到任一 id（即 `chat_done` payload 里两个字段都是 `undefined`——典型场景是 SSE 中途断开，Java `finally` 兜底补发了一个空 `chat_done`），延迟 1.5s 后自动调用一次现有的 `getConversation(sessionId)`：
    - 单纯"按最后两条位置对齐"在并发多 tab / 用户连续快发的场景下会错位（最后两条不一定是本轮的），改成双重定位：
      1. **内容前缀匹配**：取本地待回填消息（`messages.value` 中 `role` 为 user/assistant 且尚未 `_backendIdConfirmed` 的最近 6 条，限定窗口避免误匹配扩散到整段历史）逐条与后端返回的 `messages` 倒序比较，按 `role` 相同 + `content` 前 200 字符相同（而非完全相等——前端超长消息会被截断并加 `…（响应过长已截断）` 后缀，与后端存的完整内容不一致）找一条**未被占用**的后端条目
      2. **位置兜底**：前缀匹配未命中时，退化为"同 `role` 里最近一条未被占用"的后端条目
      3. 匹配成功后把该后端条目的 `id` 回填到本地消息对象，并置 `_backendIdConfirmed = true`、标记该后端条目为已占用（避免被下一条本地消息重复匹配）
    - 如果该会话在后端压根没有这些消息（请求中途彻底失败、什么都没 persist），匹配不到任何候选，跳过即可，不报错不提示
  - 正常路径（通过 `chat_done` 拿到 id）回填时，同样要置 `_backendIdConfirmed = true`，避免断流 fallback 逻辑重复处理已经确认过 id 的消息
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
- 单元测试：`long_term.retrieve()`/`search()` 对 `excluded_from_retrieval=True` 的条目正确过滤
- 单元测试：`conversations_router.retract_messages` 端点：JSON 正确过滤、agent 不可用时不抛异常、`requested>50` 返回 400、`deleted < requested` 时字段正确
- 单元测试：`_suppress_distilled_memories()` 按 `source_message_ids` 交集命中并打标记
- 单元测试：`_suppress_distilled_memories_bg()` 异常被吞掉不向上抛（避免影响已经返回的 retract 响应）
- 集成测试：完整一轮 chat → 拿到 id → 调 retract → 确认 JSON 文件和 short_term 都已清除（同步部分）；轮询/等待后确认 long_term 排除标记也已生效（异步部分，给够时间窗口）
- 集成测试：蒸馏触发后再撤回 → 对应长期记忆条目被标记排除、但内容仍存在（验证"硬过滤不删除"）
- 前端手测：撤回模式勾选/取消/确认/N>1 警告文案/占位条渲染/不可操作态/勾选满50禁用/deleted<requested 提示
- 前端手测：模拟断流（开发工具里中途断网）后验证 id 通过前缀匹配正确回填；连续快发多轮后再断流，验证不会错位匹配到旧轮次的消息
- CLI 手测：`!retract` 单选/多选/旧消息报错路径
- 飞书手测：飞书内发出的消息撤回后，飞书客户端界面同步消失；Java 重启后撤回旧消息时飞书侧静默失败但内部存储仍正常删除

## 8. 文件改动清单

| 文件 | 改动类型 |
|---|---|
| `agent/api/chat_router.py` | 生成并传递 message_id，响应体/SSE 附带 id |
| `agent/core/conversation_flow.py` | `chat()`/`chat_stream()`/`_build_messages_async()` 新增 message_id 形参与传递 |
| `agent/memory/short_term.py` | 新增 `delete_by_ids()` |
| `agent/memory/distiller.py` | `store()` 调用补充 `source_message_ids` metadata |
| `agent/memory/long_term.py` | `retrieve()`/`search()` 新增 `excluded_from_retrieval` 硬过滤 |
| `agent/api/conversations_router.py` | `append_messages()` 自动赋 id；新增 `POST .../retract` 端点（含批量上限、requested/deleted 回执）；新增 `_suppress_distilled_memories()` |
| `backend/web/.../ConversationsProxyController.java` | 新增 `retract` 代理方法，调用 `FeishuRecallBridge` |
| `backend/web/.../FeishuMessageSender.java` | `doSend()` 返回飞书 `message_id` |
| `backend/web/.../FeishuRecallBridge.java`（新文件） | id 映射表维护 + 飞书撤回 API 调用 |
| `backend/web/.../FeishuEventController.java` | 发送成功后记录 id 映射 |
| `frontend/src/services/api.js` | 新增 `retractMessages()` |
| `frontend/src/stores/websocket.js` | `chat_done` 处理读取并回填 message id |
| `frontend/src/views/ChatView.vue` | 撤回模式 UI、勾选（含 50 条上限）、确认、占位条渲染、deleted<requested 提示、断流 fallback 重新同步 id、历史加载用真实 id |
| `client/session.py` | 消息 id 字段、`retract()` |
| `client/api.py` | 新增 `retract_messages()`（`chat()`/`chat_stream()` 本身无需改动） |
| `client/repl.py` | `!history` 加序号，`stream_response()` 读出 id，新增 `!retract` 命令、`!help` 文案 |
