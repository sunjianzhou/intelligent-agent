# 消息撤回功能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户能在 frontend（Vue 聊天窗口）和 client（CLI）两端手动撤回历史消息（user/assistant），从对话 JSON、短期记忆中真正删除，长期记忆里来源相关的蒸馏摘要标记排除检索，飞书侧联动调用官方撤回 API。

**Architecture:** 在请求发起处（`chat_router.py`）生成跨层共享的 `message_id`/`assistant_message_id`，下传给 `agent.chat()`/`chat_stream()` 写入短期记忆 metadata，并随对话 JSON 一并持久化；前端/CLI 拿到这两个 id 后即可在后续调用统一的 `POST /api/conversations/{session_id}/retract` 端点时精确引用要删除的消息。Java 仅做透明代理 + 额外的飞书撤回联动，不感知撤回的业务逻辑。

**Tech Stack:** Python (FastAPI, pytest, loguru), Java (Spring Boot, JUnit5 + Mockito + AssertJ + MockWebServer), Vue 3 (Pinia, vitest), Python CLI (requests, pytest).

**设计依据：** `docs/superpowers/specs/2026-06-21-message-retraction-design.md`（已用户审阅批准，含 3 轮修订）

## Global Constraints

- 批量撤回单次请求最多 50 条 message_id（`_MAX_RETRACT_BATCH = 50`），超出返回 HTTP 400
- 长期记忆排除标记（`excluded_from_retrieval`）必须异步执行（`asyncio.create_task` + `asyncio.to_thread`），不得阻塞 retract 请求的同步响应路径
- 前端任何确认弹窗必须用 `useConfirmDialogStore`，禁止 `window.confirm`/`window.alert`（项目既定规则）
- 撤回的消息从对话 JSON 的 `messages[]` 数组**完全移除**，不留 tombstone/软删除标记
- Java 端 map 字面量禁止使用 `Map.of()`（项目运行在 Java 1.8，不支持），统一用 `new HashMap<>()` + `put(...)`
- 所有新增 Python 测试遵循现有 pytest 约定：直接调用函数/协程断言，不强行套 FastAPI TestClient（参照 `agent/tests/test_distiller.py`、`agent/tests/test_agent_core.py` 风格）

---

## Task 1: ShortTermMemory.delete_by_ids()

**Files:**
- Modify: `agent/memory/short_term.py`
- Test: `agent/tests/test_short_term.py` (new)

**Interfaces:**
- Produces: `ShortTermMemory.delete_by_ids(message_ids: list[str]) -> int` — 按 `MemoryItem.metadata["message_id"]` 精确匹配删除，返回实际删除条数。供 Task 5（`conversations_router.py` 的 retract 端点）调用。

- [ ] **Step 1: 写失败测试**

创建 `agent/tests/test_short_term.py`：

```python
"""Unit tests for ShortTermMemory.delete_by_ids() (message-retraction feature)."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from memory.short_term import ShortTermMemory


def test_delete_by_ids_removes_matching_entries():
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    m1 = stm.store("用户消息A", metadata={"role": "user", "message_id": "mid-1"})
    m2 = stm.store("助手消息A", metadata={"role": "assistant", "message_id": "mid-2"})
    m3 = stm.store("不相关消息", metadata={"role": "user", "message_id": "mid-3"})

    removed = stm.delete_by_ids(["mid-1", "mid-2"])

    assert removed == 2
    assert stm.get(m1.id) is None
    assert stm.get(m2.id) is None
    assert stm.get(m3.id) is not None


def test_delete_by_ids_ignores_unknown_ids():
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    stm.store("消息", metadata={"role": "user", "message_id": "mid-1"})

    removed = stm.delete_by_ids(["mid-does-not-exist"])

    assert removed == 0
    assert stm.count() == 1


def test_delete_by_ids_empty_list_returns_zero():
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    stm.store("消息", metadata={"role": "user", "message_id": "mid-1"})

    assert stm.delete_by_ids([]) == 0
    assert stm.count() == 1


def test_delete_by_ids_ignores_entries_without_message_id():
    """旧数据没有 message_id 字段，metadata.get() 返回 None，不应被意外匹配。"""
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    stm.store("旧版本消息（无 message_id）", metadata={"role": "user"})

    removed = stm.delete_by_ids([None])  # 防御性场景：调用方传入了 None

    assert removed == 0
    assert stm.count() == 1
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd agent && python -m pytest tests/test_short_term.py -v`
Expected: FAIL with `AttributeError: 'ShortTermMemory' object has no attribute 'delete_by_ids'`

- [ ] **Step 3: 实现 `delete_by_ids()`**

在 `agent/memory/short_term.py` 的 `delete()` 方法（约第 168-174 行）后面新增：

```python
    def delete_by_ids(self, message_ids: list) -> int:
        """按 metadata['message_id'] 精确匹配删除，返回实际删除条数。

        用于消息撤回功能：撤回时按对话 JSON 里记录的 message_id 级联清理
        对应的短期记忆条目，避免该内容继续作为上下文喂给下一轮 LLM。
        """
        if not message_ids:
            return 0
        ids_set = set(message_ids)
        targets = [
            mid for mid, m in self.memories.items()
            if m.metadata.get("message_id") in ids_set
        ]
        for mid in targets:
            self._remove_memory(mid)
        return len(targets)
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd agent && python -m pytest tests/test_short_term.py -v`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
git add agent/memory/short_term.py agent/tests/test_short_term.py
git commit -m "feat(memory): ShortTermMemory.delete_by_ids() 按 message_id 精确删除"
```

---

## Task 2: LongTermMemory 硬性排除过滤（excluded_from_retrieval）

**Files:**
- Modify: `agent/memory/long_term.py:493`（`retrieve()` 方法内的候选收集逻辑）、`L600-630` 区间的 `_search_in_chroma`/`search()` 路径
- Test: `agent/tests/test_long_term.py` (new)

**Interfaces:**
- Consumes: 无（独立改动）
- Produces: `LongTermMemory.retrieve()`/`search()` 对 `metadata.get("excluded_from_retrieval") is True` 的条目永远不进入候选集/结果列表。供 Task 5 的 `_suppress_distilled_memories()` 配合使用（写标记的是 Task 5，这里只负责"写了标记之后检索时认它"）。

**关于测试隔离：** 用 `LongTermMemory(vector_db_type="memory", use_lightweight=True)` 构造，避免依赖网络下载 sentence-transformers 模型或真实 ChromaDB。

- [ ] **Step 1: 写失败测试**

创建 `agent/tests/test_long_term.py`：

```python
"""Unit tests for LongTermMemory excluded_from_retrieval hard filter
(message-retraction feature, design doc section 4.5)."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from memory.long_term import LongTermMemory


def _make_ltm():
    # use_lightweight=True + vector_db_type="memory" 避免依赖网络下载嵌入模型或真实 ChromaDB
    return LongTermMemory(vector_db_type="memory", use_lightweight=True)


def test_retrieve_excludes_flagged_memory():
    ltm = _make_ltm()
    keep = ltm.store("用户喜欢喝咖啡", metadata={"user_id": "u1"})
    excluded = ltm.store("用户喜欢喝咖啡和茶", metadata={"user_id": "u1"})

    ltm.update(excluded.id, metadata={"excluded_from_retrieval": True})

    results = ltm.retrieve("咖啡", limit=10)
    result_ids = [r.memory.id for r in results]

    assert keep.id in result_ids
    assert excluded.id not in result_ids


def test_search_excludes_flagged_memory():
    ltm = _make_ltm()
    keep = ltm.store("天气晴朗", metadata={"user_id": "u1"})
    excluded = ltm.store("天气晴朗适合出门", metadata={"user_id": "u1"})

    ltm.update(excluded.id, metadata={"excluded_from_retrieval": True})

    results = ltm.search("天气", limit=10, threshold=0.0)
    result_ids = [r.memory.id for r in results]

    assert keep.id in result_ids
    assert excluded.id not in result_ids


def test_excluded_memory_content_still_present_not_deleted():
    """硬过滤不删除内容——验证条目仍在 self.memories 里，只是检索时被滤掉。"""
    ltm = _make_ltm()
    item = ltm.store("被撤回来源的摘要", metadata={"user_id": "u1"})
    ltm.update(item.id, metadata={"excluded_from_retrieval": True})

    assert ltm.get(item.id) is not None
    assert ltm.get(item.id).content == "被撤回来源的摘要"
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd agent && python -m pytest tests/test_long_term.py -v`
Expected: FAIL（`test_retrieve_excludes_flagged_memory` 和 `test_search_excludes_flagged_memory` 失败，因为目前没有过滤逻辑，`excluded` 条目也会出现在结果里）

- [ ] **Step 3: 实现硬过滤**

`agent/memory/long_term.py` 的 `retrieve()` 方法里，找到这一段（约第 493 行）：

```python
            if memory:
                memory.update_access()            # 只更新内存对象，暂不写 DB
                updated_memories.append(memory)

                score = self.calculate_memory_score(memory, similarity)
                search_results.append(MemorySearchResult(
                    memory=memory,
                    similarity=similarity,
                    score=score
                ))
```

改为：

```python
            if memory and not memory.metadata.get("excluded_from_retrieval"):
                memory.update_access()            # 只更新内存对象，暂不写 DB
                updated_memories.append(memory)

                score = self.calculate_memory_score(memory, similarity)
                search_results.append(MemorySearchResult(
                    memory=memory,
                    similarity=similarity,
                    score=score
                ))
```

然后找到 `search()` 方法（约第 631 行），确认它的实现方式——读取该方法完整内容，若它是直接复用 `retrieve()`（如 `return self.retrieve(query, limit)`），则无需单独改动，过滤已经在 `retrieve()` 里生效；若它有独立的候选收集逻辑，按同样的模式加 `and not memory.metadata.get("excluded_from_retrieval")` 条件。

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd agent && python -m pytest tests/test_long_term.py -v`
Expected: 3 passed

- [ ] **Step 5: Commit**

```bash
git add agent/memory/long_term.py agent/tests/test_long_term.py
git commit -m "feat(memory): LongTermMemory 检索硬过滤 excluded_from_retrieval 标记条目"
```

---

## Task 3: distiller.py 记录蒸馏来源 message_id

**Files:**
- Modify: `agent/memory/distiller.py:112-125`
- Test: `agent/tests/test_distiller.py`（扩展现有文件）

**Interfaces:**
- Consumes: 无（`window` 变量已存在于 `distill()` 方法内部，本任务只是把它的 `message_id` 提取出来写入 metadata）
- Produces: `long_term_memory.store(..., metadata={..., "source_message_ids": [...]})` —— 供 Task 5 的 `_suppress_distilled_memories()` 按交集匹配。

- [ ] **Step 1: 写失败测试**

在 `agent/tests/test_distiller.py` 末尾追加（复用文件已有的 `FakeLongTermMemory`、`FakeMemoryItem`、`_llm_good` fixtures，注意 `FakeMemoryItem` 当前不支持传入 `message_id`，需要先扩展它）：

把文件顶部的 `FakeMemoryItem` 类：

```python
class FakeMemoryItem:
    def __init__(self, role: str, content: str, user_id: str = "u1"):
        self.content = content
        self.metadata = {"role": role, "user_id": user_id}
```

改为：

```python
class FakeMemoryItem:
    def __init__(self, role: str, content: str, user_id: str = "u1", message_id: str = None):
        self.content = content
        self.metadata = {"role": role, "user_id": user_id}
        if message_id:
            self.metadata["message_id"] = message_id
```

然后在文件末尾追加新测试：

```python
@pytest.mark.asyncio
async def test_distill_records_source_message_ids():
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [
        FakeMemoryItem("user", "我叫张三", message_id="mid-u1"),
        FakeMemoryItem("assistant", "你好张三", message_id="mid-a1"),
    ]
    stored = await d.distill("u1", items, _llm_good, ltm)
    assert stored == 2
    for entry in ltm.stored:
        assert set(entry["metadata"]["source_message_ids"]) == {"mid-u1", "mid-a1"}


@pytest.mark.asyncio
async def test_distill_source_message_ids_skips_items_without_id():
    """部分短期记忆条目没有 message_id（旧数据），不应写入 None。"""
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [
        FakeMemoryItem("user", "我叫张三"),  # 无 message_id
        FakeMemoryItem("assistant", "你好张三", message_id="mid-a1"),
    ]
    stored = await d.distill("u1", items, _llm_good, ltm)
    assert stored == 2
    for entry in ltm.stored:
        assert entry["metadata"]["source_message_ids"] == ["mid-a1"]
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd agent && python -m pytest tests/test_distiller.py -v -k source_message_ids`
Expected: FAIL with `KeyError: 'source_message_ids'`

- [ ] **Step 3: 实现**

`agent/memory/distiller.py` 第 110-125 行，把：

```python
        # Store with dedup check
        stored = 0
        for fact in facts:
            if self._is_duplicate(fact, long_term_memory, user_id):
                logger.debug(f"跳过重复事实: {fact[:60]}")
                continue
            long_term_memory.store(
                content=fact,
                metadata={
                    "type": "fact",
                    "source": "distillation",
                    "user_id": user_id,
                },
                importance=0.75,
            )
            stored += 1
```

改为：

```python
        # Store with dedup check
        source_message_ids = [
            m.metadata.get("message_id") for m in window
            if m.metadata.get("message_id")
        ]
        stored = 0
        for fact in facts:
            if self._is_duplicate(fact, long_term_memory, user_id):
                logger.debug(f"跳过重复事实: {fact[:60]}")
                continue
            long_term_memory.store(
                content=fact,
                metadata={
                    "type": "fact",
                    "source": "distillation",
                    "user_id": user_id,
                    "source_message_ids": source_message_ids,
                },
                importance=0.75,
            )
            stored += 1
```

- [ ] **Step 4: 运行测试，确认全部通过**

Run: `cd agent && python -m pytest tests/test_distiller.py -v`
Expected: 全部 passed（包含原有用例 + 2 个新用例）

- [ ] **Step 5: Commit**

```bash
git add agent/memory/distiller.py agent/tests/test_distiller.py
git commit -m "feat(memory): 蒸馏时记录来源 short_term message_id，供撤回级联排除使用"
```

---

## Task 4: conversation_flow.py 串联 message_id 到 store_conversation()

**Files:**
- Modify: `agent/core/conversation_flow.py`（`chat()` 约 L259-394，`chat_stream()` 约 L477-659，`_build_messages_async()` 约 L60-104）
- Test: `agent/tests/test_agent_core.py`（扩展现有文件）

**Interfaces:**
- Consumes: 无新依赖
- Produces:
  - `agent.chat(message, ..., message_id: str = None, assistant_message_id: str = None) -> dict`
  - `agent.chat_stream(message, ..., message_id: str = None, assistant_message_id: str = None)` （async generator，签名不变只加这两个可选参数）
  - 二者内部把 `message_id` 传给 `self.memory.store_conversation("user", ..., metadata={"message_id": message_id})`，`assistant_message_id` 传给所有 `store_conversation("assistant", ...)` 调用点
  - 供 Task 6（`chat_router.py`）调用：`agent.chat(message=..., message_id=_user_msg_id, assistant_message_id=_assistant_msg_id, ...)`

- [ ] **Step 1: 写失败测试**

在 `agent/tests/test_agent_core.py` 的"Memory write"小节（约第 155-171 行）后追加：

```python
def test_chat_passes_message_id_to_store_conversation(agent):
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("Sure!"))
    agent.memory.store_conversation = MagicMock()
    _run(agent.chat(
        "Remember this", use_tools=False, use_memory=True,
        message_id="mid-user-1", assistant_message_id="mid-assistant-1",
    ))
    calls = agent.memory.store_conversation.call_args_list
    user_call = next(c for c in calls if c.args[0] == "user")
    assistant_call = next(c for c in calls if c.args[0] == "assistant")
    assert user_call.kwargs.get("metadata") == {"message_id": "mid-user-1"}
    assert assistant_call.kwargs.get("metadata") == {"message_id": "mid-assistant-1"}


def test_chat_without_message_id_omits_metadata(agent):
    """不传 message_id 时（如旧调用方/测试代码），metadata 不应被强行塞 None 值。"""
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("ok"))
    agent.memory.store_conversation = MagicMock()
    _run(agent.chat("hello", use_tools=False, use_memory=True))
    calls = agent.memory.store_conversation.call_args_list
    user_call = next(c for c in calls if c.args[0] == "user")
    assert user_call.kwargs.get("metadata") is None


@pytest.mark.asyncio
async def test_chat_stream_passes_message_id_to_store_conversation(agent):
    agent.memory.store_conversation = MagicMock()

    async def _fake_stream(*args, **kwargs):
        for etype, chunk in [("token", "Hi"), ("done", {"content": "Hi"})]:
            yield etype, chunk

    with patch.object(agent, "_stream_with_cot", side_effect=lambda *a, **k: _fake_stream()):
        events = []
        async for etype, data in agent.chat_stream(
            "hello", use_tools=False, use_memory=True,
            message_id="mid-user-2", assistant_message_id="mid-assistant-2",
        ):
            events.append((etype, data))

    calls = agent.memory.store_conversation.call_args_list
    user_call = next(c for c in calls if c.args[0] == "user")
    assistant_call = next(c for c in calls if c.args[0] == "assistant")
    assert user_call.kwargs.get("metadata") == {"message_id": "mid-user-2"}
    assert assistant_call.kwargs.get("metadata") == {"message_id": "mid-assistant-2"}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd agent && python -m pytest tests/test_agent_core.py -v -k message_id`
Expected: FAIL with `TypeError: chat() got an unexpected keyword argument 'message_id'`

- [ ] **Step 3: 实现 — `_build_messages_async()` 新增形参**

`agent/core/conversation_flow.py` 第 60-67 行，把：

```python
    async def _build_messages_async(self, message: str, use_memory: bool,
                                    user_id: str = "default",
                                    project_id: Optional[str] = None,
                                    pending_tasks: Optional[List[Dict[str, Any]]] = None,
                                    image_base64: Optional[str] = None) -> List[Dict[str, str]]:
        """异步版 _build_messages：超预算时先尝试 LLM 摘要压缩，再兜底截断。"""
        if use_memory:
            self.memory.store_conversation("user", message, user_id=user_id)
            self._encode_message_for_intent(message)
```

改为：

```python
    async def _build_messages_async(self, message: str, use_memory: bool,
                                    user_id: str = "default",
                                    project_id: Optional[str] = None,
                                    pending_tasks: Optional[List[Dict[str, Any]]] = None,
                                    image_base64: Optional[str] = None,
                                    message_id: Optional[str] = None) -> List[Dict[str, str]]:
        """异步版 _build_messages：超预算时先尝试 LLM 摘要压缩，再兜底截断。"""
        if use_memory:
            self.memory.store_conversation(
                "user", message, user_id=user_id,
                metadata={"message_id": message_id} if message_id else None,
            )
            self._encode_message_for_intent(message)
```

- [ ] **Step 4: 实现 — `chat()` 新增形参并传递**

第 259-264 行的签名，把：

```python
                   persona_override: Optional[str] = None,
                   project_id: Optional[str] = None,
                   pending_tasks: Optional[List[Dict[str, Any]]] = None,
                   skip_cache: bool = False,
                   image_base64: Optional[str] = None) -> dict:
```

改为：

```python
                   persona_override: Optional[str] = None,
                   project_id: Optional[str] = None,
                   pending_tasks: Optional[List[Dict[str, Any]]] = None,
                   skip_cache: bool = False,
                   image_base64: Optional[str] = None,
                   message_id: Optional[str] = None,
                   assistant_message_id: Optional[str] = None) -> dict:
```

第 304-307 行的 `_build_messages_async` 调用，把：

```python
        messages = await self._build_messages_async(message, use_memory, user_id=user_id,
                                                     project_id=project_id,
                                                     pending_tasks=pending_tasks,
                                                     image_base64=image_base64)
```

改为：

```python
        messages = await self._build_messages_async(message, use_memory, user_id=user_id,
                                                     project_id=project_id,
                                                     pending_tasks=pending_tasks,
                                                     image_base64=image_base64,
                                                     message_id=message_id)
```

第 315 行（`if not use_tools` 分支内）：

```python
                self.memory.store_conversation("assistant", full_response, user_id=user_id)
```

改为：

```python
                self.memory.store_conversation(
                    "assistant", full_response, user_id=user_id,
                    metadata={"message_id": assistant_message_id} if assistant_message_id else None,
                )
```

第 389 行（ReAct 工具循环结束后）同样的一行，做同样的替换：

```python
            self.memory.store_conversation("assistant", full_response, user_id=user_id)
```

改为：

```python
            self.memory.store_conversation(
                "assistant", full_response, user_id=user_id,
                metadata={"message_id": assistant_message_id} if assistant_message_id else None,
            )
```

- [ ] **Step 5: 实现 — `chat_stream()` 新增形参并传递**

第 477-487 行的签名，把：

```python
    async def chat_stream(self, message: str,
                          use_tools: bool = True,
                          use_memory: bool = True,
                          max_iterations: int = 5,
                          cancel_event: Optional[asyncio.Event] = None,
                          user_id: str = "default",
                          provider_override=None,
                          persona_override: Optional[str] = None,
                          project_id: Optional[str] = None,
                          pending_tasks: Optional[List[Dict[str, Any]]] = None,
                          image_base64: Optional[str] = None):
```

改为：

```python
    async def chat_stream(self, message: str,
                          use_tools: bool = True,
                          use_memory: bool = True,
                          max_iterations: int = 5,
                          cancel_event: Optional[asyncio.Event] = None,
                          user_id: str = "default",
                          provider_override=None,
                          persona_override: Optional[str] = None,
                          project_id: Optional[str] = None,
                          pending_tasks: Optional[List[Dict[str, Any]]] = None,
                          image_base64: Optional[str] = None,
                          message_id: Optional[str] = None,
                          assistant_message_id: Optional[str] = None):
```

第 501-504 行的 `_build_messages_async` 调用，同 Step 4 一样补 `message_id=message_id`：

```python
        messages = await self._build_messages_async(message, use_memory, user_id=user_id,
                                                     project_id=project_id,
                                                     pending_tasks=pending_tasks,
                                                     image_base64=image_base64,
                                                     message_id=message_id)
```

第 529 行（`if not use_tools` 流式分支）：

```python
                self.memory.store_conversation("assistant", full_response, user_id=user_id)
```

改为：

```python
                self.memory.store_conversation(
                    "assistant", full_response, user_id=user_id,
                    metadata={"message_id": assistant_message_id} if assistant_message_id else None,
                )
```

第 606 行（文本工具优化分支）：

```python
                    self.memory.store_conversation("assistant", cleaned, user_id=user_id)
```

改为：

```python
                    self.memory.store_conversation(
                        "assistant", cleaned, user_id=user_id,
                        metadata={"message_id": assistant_message_id} if assistant_message_id else None,
                    )
```

第 649 行（最终流式回答结束后）：

```python
            self.memory.store_conversation("assistant", full_response, user_id=user_id)
```

改为：

```python
            self.memory.store_conversation(
                "assistant", full_response, user_id=user_id,
                metadata={"message_id": assistant_message_id} if assistant_message_id else None,
            )
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd agent && python -m pytest tests/test_agent_core.py -v`
Expected: 全部 passed（包含原有用例 + 3 个新用例）

- [ ] **Step 7: Commit**

```bash
git add agent/core/conversation_flow.py agent/tests/test_agent_core.py
git commit -m "feat(agent): chat()/chat_stream() 支持传入 message_id 写入短期记忆 metadata"
```

---

## Task 5: conversations_router.py — append_messages 自动赋 id + retract 端点

**Files:**
- Modify: `agent/api/conversations_router.py`
- Test: `agent/tests/test_conversations_router.py` (new)

**Interfaces:**
- Consumes: `ShortTermMemory.delete_by_ids()`（Task 1）、`LongTermMemory.update()`（已存在，Task 2 让排除标记生效）
- Produces:
  - `append_messages(user_id, session_id, messages, project_id=None) -> List[Dict]`（**签名变化**：原来返回 `None`，现在返回写入后、带 id 的消息列表，供 Task 6 的 `chat_router.py` 读取 id）
  - `POST /api/conversations/{session_id}/retract`，body `{"message_ids": [...]}`，返回 `{"success", "requested", "deleted", "deleted_ids", "memory_purged"}`

**测试隔离说明：** 直接 `import api.conversations_router as cr` 后调用 `cr.retract_messages(session_id, fake_request)`（绕过 FastAPI 路由层，参照 `agent/tests/test_distiller.py` 直接调用协程的风格）。用 `monkeypatch` 把 `cr._CONV_BASE` 指向 `tmp_path`，避免污染真实的 `agent/data/conversations/`；用 `monkeypatch` 把 `cr._state` 替换成一个简单的 stub 对象。

- [ ] **Step 1: 写失败测试**

创建 `agent/tests/test_conversations_router.py`：

```python
"""Tests for conversations_router.py: id auto-assignment + retract endpoint."""
import sys
import os
import json
import asyncio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

import api.conversations_router as cr


class _FakeRequest:
    """Minimal stand-in for fastapi.Request — only what retract_messages() touches."""
    def __init__(self, user_id: str, body: dict):
        self.state = type("S", (), {"user_id": user_id})()
        self._body = body

    async def json(self):
        return self._body


class _FakeShortTerm:
    def __init__(self):
        self.deleted_ids = []

    def delete_by_ids(self, message_ids):
        self.deleted_ids.extend(message_ids)
        return len(message_ids)


class _FakeLongTerm:
    def __init__(self):
        self.memories = {}

    def update(self, memory_id, metadata=None):
        if memory_id in self.memories:
            self.memories[memory_id].setdefault("metadata", {}).update(metadata or {})


class _FakeMemory:
    def __init__(self):
        self.short_term = _FakeShortTerm()
        self.long_term = _FakeLongTerm()


class _FakeAgent:
    def __init__(self):
        self.memory = _FakeMemory()


@pytest.fixture
def isolated_conv_base(tmp_path, monkeypatch):
    monkeypatch.setattr(cr, "_CONV_BASE", str(tmp_path))
    return tmp_path


@pytest.fixture
def fake_agent(monkeypatch):
    agent = _FakeAgent()
    monkeypatch.setattr(cr._state, "agent", agent, raising=False)
    return agent


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


# ── append_messages auto-assigns id ────────────────────────────────────────────

def test_append_messages_assigns_id_when_missing(isolated_conv_base):
    written = cr.append_messages("u1", "sess1", [
        {"role": "user", "content": "hi", "timestamp": "t1"},
        {"role": "assistant", "content": "hello", "timestamp": "t1"},
    ])
    assert len(written) == 2
    assert written[0]["id"] and written[1]["id"]
    assert written[0]["id"] != written[1]["id"]


def test_append_messages_preserves_existing_id(isolated_conv_base):
    written = cr.append_messages("u1", "sess1", [
        {"role": "user", "content": "hi", "timestamp": "t1", "id": "preset-id"},
    ])
    assert written[0]["id"] == "preset-id"


# ── retract endpoint ────────────────────────────────────────────────────────────

def test_retract_removes_messages_and_purges_short_term(isolated_conv_base, fake_agent):
    cr.append_messages("u1", "sess1", [
        {"role": "user", "content": "hi", "timestamp": "t1", "id": "mid-1"},
        {"role": "assistant", "content": "hello", "timestamp": "t1", "id": "mid-2"},
        {"role": "user", "content": "keep me", "timestamp": "t2", "id": "mid-3"},
    ])

    req = _FakeRequest("u1", {"message_ids": ["mid-1", "mid-2"]})
    result = _run(cr.retract_messages("sess1", req))

    assert result["success"] is True
    assert result["requested"] == 2
    assert result["deleted"] == 2
    assert set(result["deleted_ids"]) == {"mid-1", "mid-2"}
    assert result["memory_purged"] == 2

    remaining = cr._load_session("u1", "sess1")
    remaining_ids = [m["id"] for m in remaining["messages"]]
    assert remaining_ids == ["mid-3"]


def test_retract_partial_match_returns_smaller_deleted_count(isolated_conv_base, fake_agent):
    cr.append_messages("u1", "sess1", [
        {"role": "user", "content": "hi", "timestamp": "t1", "id": "mid-1"},
    ])

    req = _FakeRequest("u1", {"message_ids": ["mid-1", "mid-does-not-exist"]})
    result = _run(cr.retract_messages("sess1", req))

    assert result["requested"] == 2
    assert result["deleted"] == 1
    assert result["deleted_ids"] == ["mid-1"]


def test_retract_missing_session_returns_zero_deleted(isolated_conv_base, fake_agent):
    req = _FakeRequest("u1", {"message_ids": ["mid-1"]})
    result = _run(cr.retract_messages("no-such-session", req))

    assert result["success"] is True
    assert result["requested"] == 1
    assert result["deleted"] == 0


def test_retract_empty_message_ids_is_noop(isolated_conv_base, fake_agent):
    req = _FakeRequest("u1", {"message_ids": []})
    result = _run(cr.retract_messages("sess1", req))

    assert result == {"success": True, "requested": 0, "deleted": 0, "deleted_ids": [], "memory_purged": 0}


def test_retract_over_batch_limit_returns_400(isolated_conv_base, fake_agent):
    from fastapi.responses import JSONResponse
    req = _FakeRequest("u1", {"message_ids": [f"mid-{i}" for i in range(51)]})
    result = _run(cr.retract_messages("sess1", req))

    assert isinstance(result, JSONResponse)
    assert result.status_code == 400


def test_retract_without_agent_skips_memory_purge_but_still_deletes(isolated_conv_base, monkeypatch):
    monkeypatch.setattr(cr._state, "agent", None, raising=False)
    cr.append_messages("u1", "sess1", [
        {"role": "user", "content": "hi", "timestamp": "t1", "id": "mid-1"},
    ])

    req = _FakeRequest("u1", {"message_ids": ["mid-1"]})
    result = _run(cr.retract_messages("sess1", req))

    assert result["deleted"] == 1
    assert result["memory_purged"] == 0


def test_suppress_distilled_memories_marks_matching_long_term_entries():
    class _FakeMemoryItem:
        def __init__(self, metadata):
            self.metadata = metadata

    agent = _FakeAgent()
    agent.memory.long_term.memories = {
        "lt-1": _FakeMemoryItem({"source_message_ids": ["mid-1", "mid-9"]}),
        "lt-2": _FakeMemoryItem({"source_message_ids": ["mid-unrelated"]}),
    }
    count = cr._suppress_distilled_memories(agent, ["mid-1"])
    assert count == 1
    assert agent.memory.long_term.memories["lt-1"].metadata.get("excluded_from_retrieval") is True
    assert "excluded_from_retrieval" not in agent.memory.long_term.memories["lt-2"].metadata
```

`_suppress_distilled_memories()` 通过 `item.metadata`（属性访问，不是 dict key）读写，所以 `_FakeLongTerm.update()` 的实现要相应改成对 `.metadata` 属性做 `.update()`：

```python
class _FakeLongTerm:
    def __init__(self):
        self.memories = {}

    def update(self, memory_id, metadata=None):
        if memory_id in self.memories:
            self.memories[memory_id].metadata.update(metadata or {})
```

把文件顶部已经写的 `_FakeLongTerm` 类替换成上面这版（`update()` 方法体从 `self.memories[memory_id].setdefault("metadata", {}).update(metadata or {})` 改为 `self.memories[memory_id].metadata.update(metadata or {})`）。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd agent && python -m pytest tests/test_conversations_router.py -v`
Expected: 多处 FAIL —— `append_messages()` 当前不返回值（`assert len(written) == 2` 会因为 `written is None` 报 `TypeError: object of type 'NoneType' has no len()`），`retract_messages`/`_suppress_distilled_memories` 不存在（`AttributeError`）

- [ ] **Step 3: 实现**

`agent/api/conversations_router.py` 顶部 import 区（第 14-26 行）新增 `asyncio` 和 `_state`：

```python
from __future__ import annotations

import asyncio
import json
import os
import uuid
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from loguru import logger

import api.state as _state
from config.settings import settings
```

`append_messages()`（第 100-121 行）改为对每条缺 `id` 的消息生成 uuid，并返回写入后的消息列表：

```python
def append_messages(
    user_id: str,
    session_id: str,
    messages: List[Dict[str, Any]],
    project_id: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """Called internally after each chat turn to persist messages.

    Returns the persisted message dicts (each guaranteed to have an "id"),
    so callers (chat_router.py) can read back the ids assigned this turn.
    """
    session = _load_session(user_id, session_id) or {
        "session_id": session_id,
        "user_id":    user_id,
        "created_at": datetime.now().isoformat(),
        "updated_at": datetime.now().isoformat(),
        "messages":   [],
    }
    # 写入 project_id（仅在首次传入或与已有值不同时更新）
    if project_id and session.get("project_id") != project_id:
        session["project_id"] = project_id
    for m in messages:
        if not m.get("id"):
            m["id"] = str(uuid.uuid4())
    session["messages"].extend(messages)
    # Trim to avoid unbounded growth
    session["messages"] = session["messages"][-settings.conversation_max_messages:]
    session["updated_at"] = datetime.now().isoformat()
    _save_session(user_id, session)
    return messages
```

在文件末尾（`append_conversation` 端点之后）新增 retract 相关代码：

```python
_MAX_RETRACT_BATCH = 50


def _suppress_distilled_memories(agent, retracted_message_ids: List[str]) -> int:
    """撤回后，把来源命中这些 message_id 的长期记忆标记为排除检索，不物理删除。

    一条摘要可能混合了多条消息的内容，物理删除有误伤其他未撤回消息的风险；
    硬过滤（excluded_from_retrieval）足以达到"不再污染上下文"的目标。
    """
    retracted = set(retracted_message_ids)
    count = 0
    for memory_id, item in list(agent.memory.long_term.memories.items()):
        source_ids = set(item.metadata.get("source_message_ids") or [])
        if source_ids & retracted:
            agent.memory.long_term.update(memory_id, metadata={"excluded_from_retrieval": True})
            count += 1
    return count


async def _suppress_distilled_memories_bg(agent, retracted_message_ids: List[str]) -> None:
    """后台异步执行：扫描长期记忆，命中来源 id 的条目标记排除检索。
    不放在 retract 请求主流程里同步跑，避免长期记忆条目较多时拖慢撤回响应。
    """
    try:
        count = await asyncio.to_thread(_suppress_distilled_memories, agent, retracted_message_ids)
        if count:
            logger.info(f"撤回级联：{count} 条长期记忆已标记排除检索")
    except Exception as e:
        logger.warning(f"长期记忆排除标记失败（不影响已完成的内部删除）: {e}")


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
    if removed_ids and _state.agent:
        purged = _state.agent.memory.short_term.delete_by_ids(removed_ids)
        asyncio.create_task(_suppress_distilled_memories_bg(_state.agent, removed_ids))

    return {
        "success": True,
        "requested": len(requested_ids),
        "deleted": len(removed_ids),
        "deleted_ids": removed_ids,
        "memory_purged": purged,
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd agent && python -m pytest tests/test_conversations_router.py -v`
Expected: 全部 passed

- [ ] **Step 5: 跑一遍现有依赖 append_messages 的测试，确认没有破坏既有调用方**

Run: `cd agent && python -m pytest tests/ -v -k conversation`
Expected: 全部 passed（`append_messages()` 调用方目前只有 `chat_router.py`，它当前对返回值没有任何使用——返回值从 `None` 变成消息列表是新增能力，不是破坏性变更）

- [ ] **Step 6: Commit**

```bash
git add agent/api/conversations_router.py agent/tests/test_conversations_router.py
git commit -m "feat(api): 新增 POST /api/conversations/{id}/retract 撤回端点 + append_messages 自动赋 id"
```

---

## Task 6: chat_router.py — 生成并传递 message_id，响应体/SSE 附带 id

**Files:**
- Modify: `agent/api/chat_router.py`

**Interfaces:**
- Consumes: `agent.chat(..., message_id=, assistant_message_id=)` / `agent.chat_stream(..., message_id=, assistant_message_id=)`（Task 4）、`append_messages()` 现在返回带 id 的消息列表（Task 5）
- Produces:
  - `POST /api/chat` 响应体新增 `user_message_id`、`assistant_message_id` 字段
  - `POST /api/chat/stream` 的最终 `done` SSE 事件 `data` 字段里新增同样两个字段
  - 供 Task 12（前端 websocket.js）、Task 16（CLI repl.py）读取

此任务不写新增 pytest（项目里没有 `chat_router.py` 的单元测试先例——它强耦合全局 `_state.agent`/`_state.OLLAMA_AVAILABLE`，现有测试套件对它的验证方式是手测/集成测）。改完后用 Step 4 的手测步骤验证。

- [ ] **Step 1: 顶部新增 `import uuid`**

`agent/api/chat_router.py` 第 1-6 行，把：

```python
"""聊天推理 API（POST /api/chat, POST /api/chat/stream）。"""
import asyncio
import json as _json
import traceback
from datetime import datetime
from typing import Any, Dict, List, Optional
```

改为：

```python
"""聊天推理 API（POST /api/chat, POST /api/chat/stream）。"""
import asyncio
import json as _json
import traceback
import uuid
from datetime import datetime
from typing import Any, Dict, List, Optional
```

- [ ] **Step 2: `/api/chat`（非流式）生成并传递 id**

第 69-101 行，把：

```python
    user_provider = _state._get_user_provider(user_id)
    user_persona_content = await _get_user_role_persona_content(user_id, request.message)

    async with _state._inference_slot():
        if _state.agent and _state.OLLAMA_AVAILABLE:
            try:
                result = await _state.agent.chat(
                    message=request.message,
                    use_tools=request.use_tools,
                    use_memory=request.use_memory,
                    user_id=user_id,
                    provider_override=user_provider,
                    persona_override=user_persona_content,
                    project_id=request.project_id,
                    pending_tasks=request.pending_tasks,
                    image_base64=request.image_base64,
                )
                _now = datetime.now().isoformat()
                _sid = request.session_id or user_id
                _user_msg: Dict[str, Any] = {"role": "user", "content": request.message, "timestamp": _now}
                if request.image_base64:
                    _user_msg["images_b64"] = [request.image_base64]
                _append_messages(user_id, _sid, [
                    _user_msg,
                    {"role": "assistant", "content": result["content"], "timestamp": _now},
                ], project_id=request.project_id)
                return {
                    "response":         result["content"],
                    "tool_calls":       result["tool_calls"],
                    "model":            user_provider.current_model if user_provider else "",
                    "agent_mode":       True,
                    "ollama_available": _state.OLLAMA_AVAILABLE,
                }
            except Exception as e:
                logger.error(f"Agent 调用异常: {e}\n{traceback.format_exc()}")
```

改为：

```python
    user_provider = _state._get_user_provider(user_id)
    user_persona_content = await _get_user_role_persona_content(user_id, request.message)

    async with _state._inference_slot():
        if _state.agent and _state.OLLAMA_AVAILABLE:
            try:
                _user_msg_id      = str(uuid.uuid4())
                _assistant_msg_id = str(uuid.uuid4())
                result = await _state.agent.chat(
                    message=request.message,
                    use_tools=request.use_tools,
                    use_memory=request.use_memory,
                    user_id=user_id,
                    provider_override=user_provider,
                    persona_override=user_persona_content,
                    project_id=request.project_id,
                    pending_tasks=request.pending_tasks,
                    image_base64=request.image_base64,
                    message_id=_user_msg_id,
                    assistant_message_id=_assistant_msg_id,
                )
                _now = datetime.now().isoformat()
                _sid = request.session_id or user_id
                _user_msg: Dict[str, Any] = {
                    "role": "user", "content": request.message, "timestamp": _now, "id": _user_msg_id,
                }
                if request.image_base64:
                    _user_msg["images_b64"] = [request.image_base64]
                _append_messages(user_id, _sid, [
                    _user_msg,
                    {
                        "role": "assistant", "content": result["content"], "timestamp": _now,
                        "id": _assistant_msg_id,
                    },
                ], project_id=request.project_id)
                return {
                    "response":             result["content"],
                    "tool_calls":           result["tool_calls"],
                    "model":                user_provider.current_model if user_provider else "",
                    "agent_mode":           True,
                    "ollama_available":     _state.OLLAMA_AVAILABLE,
                    "user_message_id":      _user_msg_id,
                    "assistant_message_id": _assistant_msg_id,
                }
            except Exception as e:
                logger.error(f"Agent 调用异常: {e}\n{traceback.format_exc()}")
```

- [ ] **Step 3: `/api/chat/stream` 生成并传递 id，`done` 事件附带 id**

第 162-195 行，把：

```python
    cancel_ev = asyncio.Event()
    _session_id = request.session_id or user_id

    async def generate():
        try:
            async with _state._inference_slot():
                if _state.agent:
                    _full_reply = []
                    async for event_type, data in _state.agent.chat_stream(
                            message=request.message,
                            use_tools=request.use_tools,
                            use_memory=request.use_memory,
                            cancel_event=cancel_ev,
                            user_id=user_id,
                            provider_override=user_provider,
                            persona_override=user_persona_content,
                            project_id=request.project_id,
                            pending_tasks=request.pending_tasks,
                            image_base64=request.image_base64,
                    ):
                        yield f"data: {_json.dumps({'type': event_type, 'data': data}, ensure_ascii=False)}\n\n"
                        if event_type == "done" and isinstance(data, dict):
                            _full_reply.append(data.get("content", ""))
                    if _full_reply:
                        _now = datetime.now().isoformat()
                        _stream_user_msg: Dict[str, Any] = {
                            "role": "user", "content": request.message, "timestamp": _now
                        }
                        if request.image_base64:
                            _stream_user_msg["images_b64"] = [request.image_base64]
                        _append_messages(user_id, _session_id, [
                            _stream_user_msg,
                            {"role": "assistant", "content": _full_reply[0], "timestamp": _now},
                        ], project_id=request.project_id)
```

改为：

```python
    cancel_ev = asyncio.Event()
    _session_id = request.session_id or user_id
    _stream_user_msg_id      = str(uuid.uuid4())
    _stream_assistant_msg_id = str(uuid.uuid4())

    async def generate():
        try:
            async with _state._inference_slot():
                if _state.agent:
                    _full_reply = []
                    async for event_type, data in _state.agent.chat_stream(
                            message=request.message,
                            use_tools=request.use_tools,
                            use_memory=request.use_memory,
                            cancel_event=cancel_ev,
                            user_id=user_id,
                            provider_override=user_provider,
                            persona_override=user_persona_content,
                            project_id=request.project_id,
                            pending_tasks=request.pending_tasks,
                            image_base64=request.image_base64,
                            message_id=_stream_user_msg_id,
                            assistant_message_id=_stream_assistant_msg_id,
                    ):
                        if event_type == "done" and isinstance(data, dict):
                            _full_reply.append(data.get("content", ""))
                            data = {
                                **data,
                                "user_message_id": _stream_user_msg_id,
                                "assistant_message_id": _stream_assistant_msg_id,
                            }
                        yield f"data: {_json.dumps({'type': event_type, 'data': data}, ensure_ascii=False)}\n\n"
                    if _full_reply:
                        _now = datetime.now().isoformat()
                        _stream_user_msg: Dict[str, Any] = {
                            "role": "user", "content": request.message, "timestamp": _now,
                            "id": _stream_user_msg_id,
                        }
                        if request.image_base64:
                            _stream_user_msg["images_b64"] = [request.image_base64]
                        _append_messages(user_id, _session_id, [
                            _stream_user_msg,
                            {
                                "role": "assistant", "content": _full_reply[0], "timestamp": _now,
                                "id": _stream_assistant_msg_id,
                            },
                        ], project_id=request.project_id)
```

注意 `yield` 那一行从循环体的开头挪到了 `if event_type == "done"` 判断之后——这样在改写 `data` 字典之后再序列化发出，前端才能在 `done` 事件里读到这两个 id。其余事件类型（`token`/`tool_call_start`/`tool_calls_done` 等）不受影响，原样直传。

- [ ] **Step 4: 手测验证**

启动 agent 服务后（确保 Ollama 可用），用 curl 验证非流式接口返回新字段：

```bash
curl -s -X POST http://localhost:8000/api/chat \
  -H "Authorization: Bearer <有效JWT>" -H "Content-Type: application/json" \
  -d '{"message": "你好", "use_tools": false}' | python -m json.tool
```

Expected: 响应 JSON 顶层包含 `"user_message_id"` 和 `"assistant_message_id"`，均为非空字符串。

再验证流式接口：

```bash
curl -s -N -X POST http://localhost:8000/api/chat/stream \
  -H "Authorization: Bearer <有效JWT>" -H "Content-Type: application/json" \
  -d '{"message": "你好", "use_tools": false}'
```

Expected: 最后一行 `data: {...}` 里 `"type":"done"` 对应的 `data` 字段包含 `user_message_id`/`assistant_message_id`。

再用 `GET /api/conversations/{session_id}`（`session_id` 不传时即 `user_id`）确认该轮的两条消息在持久化的 JSON 里也带着同样的 `id`：

```bash
curl -s http://localhost:8000/api/conversations/<user_id> \
  -H "Authorization: Bearer <有效JWT>" | python -m json.tool
```

Expected: `session.messages` 最后两条的 `id` 字段，与上面响应/SSE 里拿到的 `user_message_id`/`assistant_message_id` 完全一致。

- [ ] **Step 5: 跑一遍既有单测确认没有回归**

Run: `cd agent && python -m pytest tests/ -v`
Expected: 全部 passed（chat_router.py 没有专门的单测文件，这一步是确认本次改动没有间接破坏 `test_agent_core.py`/`test_conversations_router.py` 等其他测试）

- [ ] **Step 6: Commit**

```bash
git add agent/api/chat_router.py
git commit -m "feat(api): chat_router 生成 message_id 并透传到 agent.chat()/chat_stream()，响应体/SSE 附带 id"
```

---

## Task 7: Java ConversationsProxyController — retract 代理端点

**Files:**
- Modify: `backend/web/src/main/java/com/intelligent/agent/web/controller/ConversationsProxyController.java`
- Test: `backend/web/src/test/java/com/intelligent/agent/web/controller/ConversationsProxyControllerTest.java` (new)

**Interfaces:**
- Consumes: `AbstractProxyController.proxyPost(path, body, req)`（已存在）
- Produces: `POST /api/conversations/{sessionId}/retract`，透明转发到 Python `POST /api/conversations/{sessionId}/retract`。本任务暂不接入飞书联动（Task 9 再加），先保证基础代理可用、可独立测试。

**测试隔离说明：** 项目里没有现成的 `ConversationsProxyControllerTest.java`，参照 `FeishuMessageSenderTest.java` 的风格——这里更简单，直接用 Mockito mock `PythonProxyService`，不需要起 MockWebServer。

- [ ] **Step 1: 写失败测试**

创建 `backend/web/src/test/java/com/intelligent/agent/web/controller/ConversationsProxyControllerTest.java`：

```java
package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationsProxyControllerTest {

    @Mock PythonProxyService proxy;
    @Mock HttpServletRequest req;

    private ConversationsProxyController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ConversationsProxyController();
        controller.proxy = proxy;
        controller.objectMapper = new ObjectMapper();
        when(proxy.extractUserIdFromRequest(req)).thenReturn("u1");
    }

    @Test
    void retractMessages_forwardsToCorrectPythonPath() throws Exception {
        when(proxy.post(eq("/api/conversations/sess1/retract"), any(), eq("u1")))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"requested\":1,\"deleted\":1,\"deleted_ids\":[\"mid-1\"],\"memory_purged\":1}"));

        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", java.util.List.of("mid-1"));

        ResponseEntity<Map<String, Object>> resp = controller.retractMessages("sess1", body, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("deleted")).isEqualTo(1);
        verify(proxy).post(eq("/api/conversations/sess1/retract"), eq(body), eq("u1"));
    }

    @Test
    void retractMessages_proxyThrows_returnsErrorResponse() throws Exception {
        when(proxy.post(any(), any(), any())).thenThrow(new RuntimeException("network error"));

        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", java.util.List.of("mid-1"));

        ResponseEntity<Map<String, Object>> resp = controller.retractMessages("sess1", body, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }
}
```

注意：`java.util.List.of(...)` 不受 `Map.of()` 那条 Java 1.8 限制——`List.of` 是 Java 9+ API，本项目主代码（生产代码）禁止用 `Map.of()`/`List.of()`，但**测试代码**只要项目实际用的 JDK 版本支持即可。如果 CI 编译报 `List.of` 不存在，改用 `java.util.Collections.singletonList("mid-1")` 代替。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd backend/web && mvn test -Dtest=ConversationsProxyControllerTest`
Expected: FAIL — 编译错误，`retractMessages` 方法不存在

- [ ] **Step 3: 实现**

`backend/web/src/main/java/com/intelligent/agent/web/controller/ConversationsProxyController.java` 末尾（`branchConversation` 方法之后，闭合 `}` 之前）新增：

```java
    @PostMapping("/api/conversations/{sessionId}/retract")
    public ResponseEntity<Map<String, Object>> retractMessages(
            @PathVariable String sessionId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return proxyPost("/api/conversations/" + sessionId + "/retract", body, req);
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd backend/web && mvn test -Dtest=ConversationsProxyControllerTest`
Expected: 2 tests passed

- [ ] **Step 5: Commit**

```bash
git add backend/web/src/main/java/com/intelligent/agent/web/controller/ConversationsProxyController.java backend/web/src/test/java/com/intelligent/agent/web/controller/ConversationsProxyControllerTest.java
git commit -m "feat(web): 新增 POST /api/conversations/{id}/retract Java 代理端点"
```

---

## Task 8: FeishuMessageSender — 发送返回 message_id + 新增 recall()

**Files:**
- Modify: `backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuMessageSender.java`
- Modify: `backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuMessageSenderTest.java`（扩展现有文件）

**Interfaces:**
- Consumes: 无新依赖
- Produces:
  - `sendText(chatId, text) -> String`（飞书 message_id，原来是 `void`）
  - `sendPost(chatId, content) -> String`
  - `sendInteractive(chatId, cardJson) -> String`
  - `recall(String feishuMessageId)`（新方法，调用飞书官方撤回 API）
  - 供 Task 9（`FeishuEventController`/`FeishuRecallBridge`）使用

**⚠️ 待核实事项：** 飞书官方"撤回消息"API 的 HTTP method 和 path，本任务先按已知早期文档版本实现（`DELETE /open-apis/im/v1/messages/{message_id}`），落地上线前请对照飞书开放平台当前文档（搜索"撤回消息"）核实一遍，如有出入只需改 `recall()` 方法里的 `HttpMethod`/`url` 拼接，不影响其他逻辑。

- [ ] **Step 1: 写失败测试**

在 `FeishuMessageSenderTest.java` 的 `sendText_postsToFeishuApi` 测试后追加：

```java
    @Test
    void sendText_returnsMessageId() throws Exception {
        server.enqueue(tokenResponse("tok-id", 7200));
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"message_id\":\"om_abc123\"}}")
                .setResponseCode(200));

        String msgId = sender.sendText("oc_chat123", "Hello");

        assertThat(msgId).isEqualTo("om_abc123");
    }

    @Test
    void sendText_returnsNull_whenFallbackAlsoFails() throws Exception {
        server.enqueue(tokenResponse("tok-fail", 7200));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));  // fallback 也失败

        String msgId = sender.sendText("chat-err", "触发重试");

        assertThat(msgId).isNull();
    }

    @Test
    void recall_postsDeleteToCorrectUrl() throws Exception {
        server.enqueue(tokenResponse("tok-recall", 7200));
        server.enqueue(new MockResponse().setBody("{\"code\":0}").setResponseCode(200));

        sender.recall("om_abc123");

        server.takeRequest();  // token 请求
        RecordedRequest recallReq = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq).isNotNull();
        assertThat(recallReq.getMethod()).isEqualTo("DELETE");
        assertThat(recallReq.getPath()).contains("/im/v1/messages/om_abc123");
    }

    @Test
    void recall_throwsOnApiError() throws Exception {
        server.enqueue(tokenResponse("tok-recall-err", 7200));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"code\":1,\"msg\":\"too late\"}"));

        assertThatThrownBy(() -> sender.recall("om_expired"))
                .isInstanceOf(RuntimeException.class);
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd backend/web && mvn test -Dtest=FeishuMessageSenderTest`
Expected: FAIL — 编译错误（`recall` 方法不存在，`sendText` 返回类型不匹配 `String msgId = sender.sendText(...)`）

- [ ] **Step 3: 实现**

`FeishuMessageSender.java` 第 111-174 行整段替换为：

```java
    public String sendText(String chatId, String text) {
        Map<String, Object> content = new HashMap<>();
        content.put("text", text);
        return sendWithRetry(chatId, "text", content);
    }

    public String sendPost(String chatId, Map<String, Object> content) {
        return sendWithRetry(chatId, "post", content);
    }

    public String sendInteractive(String chatId, String cardJson) {
        try {
            Map<?, ?> card = objectMapper.readValue(cardJson, Map.class);
            return sendWithRetry(chatId, "interactive", card);
        } catch (Exception e) {
            log.error("sendInteractive 解析 cardJson 失败，chatId={}", chatId, e);
            return null;
        }
    }

    /** 调用飞书官方撤回消息 API。method/path 已知早期文档版本为
     *  DELETE /open-apis/im/v1/messages/{message_id}——落地前请对照飞书开放平台
     *  当前文档核实一遍，如有出入只需改这里的 HttpMethod/url 拼接。 */
    public void recall(String messageId) {
        String url   = feishuBase + "/open-apis/im/v1/messages/" + messageId;
        String token = getTenantAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));

        ResponseEntity<String> res = restTemplate.exchange(
                url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("飞书撤回 API 返回 " + res.getStatusCode() + ": " + res.getBody());
        }
    }

    private String sendWithRetry(String chatId, String msgType, Object content) {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try {
                return doSend(chatId, msgType, content);
            } catch (Exception e) {
                lastEx = e;
                log.warn("发送飞书消息第 {} 次失败，chatId={}: {}", i + 1, chatId, e.getMessage());
                if (i < 2) {
                    try { Thread.sleep(1000L << i); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        log.error("发送消息 3 次全部失败，chatId={}，发送 fallback", chatId, lastEx);
        try {
            return doSend(chatId, "text", Collections.singletonMap("text", "网络繁忙，请重试 🙏"));
        } catch (Exception e) {
            log.error("fallback 消息也发送失败，chatId={}", chatId, e);
            return null;
        }
    }

    private String doSend(String chatId, String msgType, Object content) throws Exception {
        String url   = feishuBase + "/open-apis/im/v1/messages?receive_id_type=chat_id";
        String token = getTenantAccessToken();

        Map<String, Object> body = new HashMap<>();
        body.put("receive_id", chatId);
        body.put("msg_type",   msgType);
        body.put("content",    objectMapper.writeValueAsString(content));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));

        ResponseEntity<String> res = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("飞书 API 返回 " + res.getStatusCode() + ": " + res.getBody());
        }
        return extractMessageId(res.getBody());
    }

    private String extractMessageId(String responseBody) {
        try {
            Map<?, ?> json = objectMapper.readValue(responseBody, Map.class);
            Map<?, ?> data = (Map<?, ?>) json.get("data");
            return data != null ? (String) data.get("message_id") : null;
        } catch (Exception e) {
            log.warn("解析飞书 message_id 失败: {}", e.getMessage());
            return null;
        }
    }
}
```

（保留文件最后一个 `}` 作为类的收尾——上面代码块结尾的 `}` 就是它，不要重复多加一个。）

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd backend/web && mvn test -Dtest=FeishuMessageSenderTest`
Expected: 全部 passed（原有 4 个 + 新增 4 个）

- [ ] **Step 5: Commit**

```bash
git add backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuMessageSender.java backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuMessageSenderTest.java
git commit -m "feat(feishu): 发送接口返回飞书 message_id，新增 recall() 撤回 API 调用"
```

---

## Task 9: FeishuRecallBridge + 接入 FeishuEventController / ConversationsProxyController

**Files:**
- Create: `backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuRecallBridge.java`
- Test: `backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuRecallBridgeTest.java` (new)
- Modify: `backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuEventController.java`
- Modify: `backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuEventControllerTest.java`
- Modify: `backend/web/src/main/java/com/intelligent/agent/web/controller/ConversationsProxyController.java`
- Modify: `backend/web/src/test/java/com/intelligent/agent/web/controller/ConversationsProxyControllerTest.java`

**Interfaces:**
- Consumes: `FeishuMessageSender.sendInteractive()`/`recall()`（Task 8）、`AgentService.chatFull()`（已存在）
- Produces:
  - `FeishuRecallBridge.register(String assistantMessageId, String feishuMessageId)`
  - `FeishuRecallBridge.onMessagesRetracted(Map<String, Object> retractResponse)`

### Part A — FeishuRecallBridge 本身

- [ ] **Step 1: 写失败测试**

创建 `backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuRecallBridgeTest.java`：

```java
package com.intelligent.agent.web.feishu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class FeishuRecallBridgeTest {

    @Mock FeishuMessageSender sender;
    private FeishuRecallBridge bridge;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bridge = new FeishuRecallBridge(sender);
    }

    @Test
    void onMessagesRetracted_callsRecall_forMappedIds() throws Exception {
        bridge.register("aid-1", "om_feishu1");

        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", List.of("aid-1"));

        bridge.onMessagesRetracted(retractResponse);

        verify(sender).recall("om_feishu1");
    }

    @Test
    void onMessagesRetracted_skipsUnmappedIds() throws Exception {
        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", List.of("aid-not-registered"));

        bridge.onMessagesRetracted(retractResponse);

        verify(sender, never()).recall(any());
    }

    @Test
    void onMessagesRetracted_recallThrows_doesNotPropagate() throws Exception {
        bridge.register("aid-1", "om_feishu1");
        doThrow(new RuntimeException("撤回超时")).when(sender).recall("om_feishu1");

        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", List.of("aid-1"));

        bridge.onMessagesRetracted(retractResponse);  // 不应抛出

        verify(sender).recall("om_feishu1");
    }

    @Test
    void onMessagesRetracted_nullResponse_doesNothing() {
        bridge.onMessagesRetracted(null);
        verifyNoInteractions(sender);
    }

    @Test
    void register_ignoresNullIds() throws Exception {
        bridge.register(null, "om_x");
        bridge.register("aid-1", null);

        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", List.of("aid-1"));
        bridge.onMessagesRetracted(retractResponse);

        verify(sender, never()).recall(any());
    }

    @Test
    void mapping_evictsOldestEntry_whenOverCapacity() throws Exception {
        for (int i = 0; i < 501; i++) {
            bridge.register("aid-" + i, "om-" + i);
        }
        // 最早插入的 aid-0 应已被淘汰
        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", List.of("aid-0"));
        bridge.onMessagesRetracted(retractResponse);
        verify(sender, never()).recall("om-0");

        // 最近插入的 aid-500 应仍在
        Map<String, Object> retractResponse2 = new HashMap<>();
        retractResponse2.put("deleted_ids", List.of("aid-500"));
        bridge.onMessagesRetracted(retractResponse2);
        verify(sender).recall("om-500");
    }
}
```

注：`java.util.List.of(...)` 同 Task 7 的注意事项——这是测试代码，若编译报错改用 `Collections.singletonList(...)`。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd backend/web && mvn test -Dtest=FeishuRecallBridgeTest`
Expected: FAIL — 编译错误，`FeishuRecallBridge` 类不存在

- [ ] **Step 3: 实现**

创建 `backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuRecallBridge.java`：

```java
package com.intelligent.agent.web.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维护内部 assistant_message_id → 飞书 message_id 的映射，支持撤回时联动调用
 * 飞书官方撤回 API。纯内存态，不落盘，重启即丢（与短期记忆一样是易失态，可接受）。
 */
@Slf4j
@Component
public class FeishuRecallBridge {

    private static final int MAX_ENTRIES = 500;

    private final FeishuMessageSender sender;
    private final Map<String, String> idMapping =
            new LinkedHashMap<String, String>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    @Autowired
    public FeishuRecallBridge(FeishuMessageSender sender) {
        this.sender = sender;
    }

    /** 飞书消息发送成功后调用，记录内部 id → 飞书 id 的映射。 */
    public synchronized void register(String assistantMessageId, String feishuMessageId) {
        if (assistantMessageId == null || feishuMessageId == null) return;
        idMapping.put(assistantMessageId, feishuMessageId);
    }

    /** retract 响应里携带的 deleted_ids 命中映射表的，逐个调用飞书官方撤回 API。 */
    public void onMessagesRetracted(Map<String, Object> retractResponse) {
        if (retractResponse == null) return;
        Object deletedIdsObj = retractResponse.get("deleted_ids");
        if (!(deletedIdsObj instanceof Iterable)) return;

        for (Object idObj : (Iterable<?>) deletedIdsObj) {
            String ourId = String.valueOf(idObj);
            String feishuMessageId;
            synchronized (this) {
                feishuMessageId = idMapping.remove(ourId);
            }
            if (feishuMessageId == null) continue;
            try {
                sender.recall(feishuMessageId);
            } catch (Exception e) {
                log.warn("飞书消息撤回失败（不影响内部存储已完成的删除），feishuMessageId={}: {}",
                        feishuMessageId, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd backend/web && mvn test -Dtest=FeishuRecallBridgeTest`
Expected: 6 tests passed

- [ ] **Step 5: Commit**

```bash
git add backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuRecallBridge.java backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuRecallBridgeTest.java
git commit -m "feat(feishu): 新增 FeishuRecallBridge，维护内部id到飞书message_id映射"
```

### Part B — 接入 FeishuEventController（发送成功后注册映射）

- [ ] **Step 6: 更新失败测试**

`FeishuEventControllerTest.java` 整份改为（核心变化：mock 新增 `FeishuRecallBridge`，`agentService.chat()` 换成 `agentService.chatFull()`）：

```java
package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuEventControllerTest {

    @Mock AgentService agentService;
    @Mock FeishuMessageSender sender;
    @Mock FeishuRecallBridge recallBridge;

    private FeishuEventController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        FeishuConfig config = new FeishuConfig();
        config.setVerificationToken("verify-tok");
        config.setEncryptKey("test-key");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Map<String, Object> chatFullResult = new HashMap<>();
        chatFullResult.put("response", "Agent 回复");
        chatFullResult.put("assistant_message_id", "aid-1");
        when(agentService.chatFull(any(ChatRequest.class))).thenReturn(chatFullResult);
        when(sender.sendInteractive(any(), any())).thenReturn("om_feishu1");

        controller = new FeishuEventController(config, agentService, sender,
                new ObjectMapper(), executor, recallBridge);
    }

    @Test
    void routeEvent_imMessage_extractsUserIdWithPrefix() throws Exception {
        String event = buildImMessageEvent("ou_test123", "oc_chat456", "你好");
        controller.routeEvent(event);
        Thread.sleep(200);

        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentService, timeout(1000)).chatFull(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo("feishu:ou_test123");
    }

    @Test
    void routeEvent_imMessage_sendsThinkingFirst() throws Exception {
        String event = buildImMessageEvent("ou_abc", "oc_chat789", "测试");
        controller.routeEvent(event);
        Thread.sleep(500);
        verify(sender, timeout(1000)).sendText(eq("oc_chat789"), contains("思考中"));
    }

    @Test
    void routeEvent_imMessage_registersFeishuRecallMapping() throws Exception {
        String event = buildImMessageEvent("ou_abc", "oc_chat789", "测试");
        controller.routeEvent(event);
        Thread.sleep(500);
        verify(recallBridge, timeout(1000)).register("aid-1", "om_feishu1");
    }

    @Test
    void routeEvent_unknownEventType_silentlyIgnored() {
        assertThatCode(() -> controller.routeEvent(
                "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"unknown.type\"},\"event\":{}}")
        ).doesNotThrowAnyException();
        verifyNoInteractions(agentService);
    }

    @Test
    void routeEvent_malformedJson_doesNotThrow() {
        assertThatCode(() -> controller.routeEvent("not-json")).doesNotThrowAnyException();
    }

    private String buildImMessageEvent(String openId, String chatId, String text) {
        String contentEscaped = "{\\\"text\\\":\\\"" + text + "\\\"}";
        return "{"
            + "\"schema\":\"2.0\","
            + "\"header\":{\"event_type\":\"im.message.receive_v1\"},"
            + "\"event\":{"
            +   "\"sender\":{\"sender_id\":{\"open_id\":\"" + openId + "\"}},"
            +   "\"message\":{\"chat_id\":\"" + chatId + "\","
            +              "\"msg_type\":\"text\","
            +              "\"content\":\"" + contentEscaped + "\"}"
            + "}"
            + "}";
    }
}
```

- [ ] **Step 7: 运行测试，确认失败**

Run: `cd backend/web && mvn test -Dtest=FeishuEventControllerTest`
Expected: FAIL — 构造方法参数数量不匹配（`FeishuEventController` 目前只接受 5 个参数，测试传了 6 个）

- [ ] **Step 8: 实现**

`FeishuEventController.java` 第 19-38 行的字段 + 构造方法，把：

```java
    private final FeishuConfig config;
    private final AgentService agentService;
    private final FeishuMessageSender sender;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    @Autowired
    public FeishuEventController(FeishuConfig config,
                                  AgentService agentService,
                                  FeishuMessageSender sender,
                                  ObjectMapper objectMapper,
                                  @Qualifier("feishuStreamExecutor") ExecutorService executor) {
        this.config       = config;
        this.agentService = agentService;
        this.sender       = sender;
        this.objectMapper = objectMapper;
        this.executor     = executor;
    }
```

改为：

```java
    private final FeishuConfig config;
    private final AgentService agentService;
    private final FeishuMessageSender sender;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final FeishuRecallBridge recallBridge;

    @Autowired
    public FeishuEventController(FeishuConfig config,
                                  AgentService agentService,
                                  FeishuMessageSender sender,
                                  ObjectMapper objectMapper,
                                  @Qualifier("feishuStreamExecutor") ExecutorService executor,
                                  FeishuRecallBridge recallBridge) {
        this.config       = config;
        this.agentService = agentService;
        this.sender       = sender;
        this.objectMapper = objectMapper;
        this.executor     = executor;
        this.recallBridge = recallBridge;
    }
```

第 75-97 行的 `executor.submit(...)` 内部逻辑，把：

```java
            executor.submit(() -> {
                try {
                    sender.sendText(finalChatId, "⏳ 思考中...");
                } catch (Exception e) {
                    log.warn("发送「思考中」失败，chatId={}: {}", finalChatId, e.getMessage());
                }

                try {
                    ChatRequest req = new ChatRequest();
                    req.setMessage(finalText);
                    req.setUserId(finalUserId);
                    req.setUseTools(true);
                    req.setUseMemory(true);
                    String reply = agentService.chat(req);
                    sender.sendInteractive(finalChatId,
                            FeishuCardBuilder.textCard("AI 回复", reply));
                } catch (Exception e) {
                    log.error("飞书消息处理失败，chatId={}", finalChatId, e);
                    try {
                        sender.sendText(finalChatId, "⚠️ 处理超时，请重试");
                    } catch (Exception ignored) {}
                }
            });
```

改为：

```java
            executor.submit(() -> {
                try {
                    sender.sendText(finalChatId, "⏳ 思考中...");
                } catch (Exception e) {
                    log.warn("发送「思考中」失败，chatId={}: {}", finalChatId, e.getMessage());
                }

                try {
                    ChatRequest req = new ChatRequest();
                    req.setMessage(finalText);
                    req.setUserId(finalUserId);
                    req.setUseTools(true);
                    req.setUseMemory(true);
                    Map<String, Object> result = agentService.chatFull(req);
                    String reply = String.valueOf(result.getOrDefault("response", ""));
                    String assistantMessageId = (String) result.get("assistant_message_id");
                    String feishuMessageId = sender.sendInteractive(finalChatId,
                            FeishuCardBuilder.textCard("AI 回复", reply));
                    recallBridge.register(assistantMessageId, feishuMessageId);
                } catch (Exception e) {
                    log.error("飞书消息处理失败，chatId={}", finalChatId, e);
                    try {
                        sender.sendText(finalChatId, "⚠️ 处理超时，请重试");
                    } catch (Exception ignored) {}
                }
            });
```

- [ ] **Step 9: 运行测试，确认通过**

Run: `cd backend/web && mvn test -Dtest=FeishuEventControllerTest`
Expected: 5 tests passed

- [ ] **Step 10: Commit**

```bash
git add backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuEventController.java backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuEventControllerTest.java
git commit -m "feat(feishu): 发送成功后向 FeishuRecallBridge 注册 id 映射"
```

### Part C — 接入 ConversationsProxyController（撤回时触发飞书联动）

- [ ] **Step 11: 更新失败测试**

在 `ConversationsProxyControllerTest.java` 的 `setUp()` 里新增 `@Mock FeishuRecallBridge recallBridge;` 并在 `controller` 构造后赋值 `controller.feishuRecallBridge = recallBridge;`，然后追加新测试：

```java
    @Mock FeishuRecallBridge recallBridge;
```

（加在已有的 `@Mock PythonProxyService proxy;`/`@Mock HttpServletRequest req;` 旁边）

`setUp()` 方法里 `controller.objectMapper = new ObjectMapper();` 那一行后面追加：

```java
        controller.feishuRecallBridge = recallBridge;
```

文件末尾追加新测试方法：

```java
    @Test
    void retractMessages_triggersFeishuRecallBridge() throws Exception {
        when(proxy.post(eq("/api/conversations/sess1/retract"), any(), eq("u1")))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"requested\":1,\"deleted\":1,\"deleted_ids\":[\"mid-1\"],\"memory_purged\":1}"));

        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", java.util.List.of("mid-1"));

        controller.retractMessages("sess1", body, req);

        verify(recallBridge).onMessagesRetracted(argThat(resp ->
                resp != null && Boolean.TRUE.equals(resp.get("success"))));
    }
```

- [ ] **Step 12: 运行测试，确认失败**

Run: `cd backend/web && mvn test -Dtest=ConversationsProxyControllerTest`
Expected: FAIL — 编译错误，`controller.feishuRecallBridge` 字段不存在

- [ ] **Step 13: 实现**

`ConversationsProxyController.java` 顶部新增字段注入（放在 `branchConversation` 方法之前即可，紧邻其他 `@xxxMapping` 方法之上）：

```java
    @Autowired
    FeishuRecallBridge feishuRecallBridge;
```

需在文件顶部 import 区新增：

```java
import com.intelligent.agent.web.feishu.FeishuRecallBridge;
```

把 Task 7 写的 `retractMessages` 方法：

```java
    @PostMapping("/api/conversations/{sessionId}/retract")
    public ResponseEntity<Map<String, Object>> retractMessages(
            @PathVariable String sessionId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return proxyPost("/api/conversations/" + sessionId + "/retract", body, req);
    }
```

改为：

```java
    @PostMapping("/api/conversations/{sessionId}/retract")
    public ResponseEntity<Map<String, Object>> retractMessages(
            @PathVariable String sessionId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> resp =
                proxyPost("/api/conversations/" + sessionId + "/retract", body, req);
        feishuRecallBridge.onMessagesRetracted(resp.getBody());  // 失败不影响本次响应
        return resp;
    }
```

- [ ] **Step 14: 运行测试，确认通过**

Run: `cd backend/web && mvn test -Dtest=ConversationsProxyControllerTest`
Expected: 3 tests passed

- [ ] **Step 15: 跑全量 Java 测试确认无回归**

Run: `cd backend/web && mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 16: Commit**

```bash
git add backend/web/src/main/java/com/intelligent/agent/web/controller/ConversationsProxyController.java backend/web/src/test/java/com/intelligent/agent/web/controller/ConversationsProxyControllerTest.java
git commit -m "feat(feishu): retract 端点接入 FeishuRecallBridge，撤回时联动飞书官方撤回"
```

---

## Task 10: frontend api.js — retractMessages()

**Files:**
- Modify: `frontend/src/services/api.js`

**Interfaces:**
- Produces: `retractMessages(sessionId: string, messageIds: string[]) -> Promise<{success, requested, deleted, deleted_ids, memory_purged}>`，供 Task 13（ChatView.vue）调用。

- [ ] **Step 1: 实现**

`frontend/src/services/api.js` 第 216-227 行（"Conversations history" 小节），在 `branchConversation` 定义后追加：

```js
export const retractMessages = (sessionId, messageIds) =>
  request(`${BASE}/conversations/${encodeURIComponent(sessionId)}/retract`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ message_ids: messageIds }),
  })
```

- [ ] **Step 2: 手测验证**

启动前端开发服务器（`cd frontend && npm run dev`），打开浏览器开发者工具 Console，登录后执行：

```js
import('/src/services/api.js').then(m => m.retractMessages('test-session-id', ['nonexistent-id']).then(console.log))
```

Expected: 打印 `{success: true, requested: 1, deleted: 0, deleted_ids: [], memory_purged: 0}`（因为 `test-session-id` 不存在，符合 Task 5 里"找不到 session 不报错"的设计）。这一步只验证请求路径/方法/body 格式正确，不验证业务逻辑（业务逻辑已在 Task 5 单测覆盖）。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/api.js
git commit -m "feat(api): 新增 retractMessages() 调用撤回端点"
```

---

## Task 11: frontend messageIdSync.js — 断流 fallback 的双重定位匹配逻辑

**Files:**
- Create: `frontend/src/utils/messageIdSync.js`
- Test: `frontend/src/__tests__/messageIdSync.test.js` (new)

**Interfaces:**
- Produces: `resolvePendingMessageIds(pendingLocalMessages, backendMessages) -> Map<localMessage, backendId>`
  - `pendingLocalMessages`: 本地消息对象数组（每个有 `role`、`content`），按时间正序，且尚未确认真实 id
  - `backendMessages`: 后端返回的消息数组（每个有 `id`、`role`、`content`）
  - 返回一个 `Map`，key 是传入的本地消息对象本身（引用），value 是匹配到的后端 `id`（找不到匹配则该 key 不出现在 Map 里）
  - 供 Task 12（`websocket.js` 的断流 fallback）调用

把这部分匹配逻辑抽成独立的纯函数模块，是因为它是整个断流 fallback 里唯一有"双重定位"分支逻辑、容易出 off-by-one/重复匹配 bug 的部分，值得用 vitest 单独覆盖（参照本项目现有的 `frontend/src/__tests__/jwt.test.js` 风格——是目前唯一的前端单测文件，本任务延用同样的约定）。

- [ ] **Step 1: 写失败测试**

创建 `frontend/src/__tests__/messageIdSync.test.js`：

```js
import { describe, it, expect } from 'vitest'
import { resolvePendingMessageIds } from '../utils/messageIdSync'

describe('resolvePendingMessageIds', () => {
  it('内容前缀完全匹配时直接命中', () => {
    const local = { role: 'assistant', content: '你好，世界' }
    const backend = [
      { id: 'b-1', role: 'user', content: '你好' },
      { id: 'b-2', role: 'assistant', content: '你好，世界' },
    ]
    const result = resolvePendingMessageIds([local], backend)
    expect(result.get(local)).toBe('b-2')
  })

  it('前端超长消息被截断加后缀，仍能通过前缀匹配命中', () => {
    const longContent = 'x'.repeat(300)
    const local = { role: 'assistant', content: longContent.slice(0, 200) + '…（响应过长已截断）' }
    const backend = [{ id: 'b-1', role: 'assistant', content: longContent }]
    const result = resolvePendingMessageIds([local], backend)
    expect(result.get(local)).toBe('b-1')
  })

  it('内容不匹配时退化为同 role 位置兜底（取最近一条未占用的）', () => {
    const local = { role: 'assistant', content: '本地内容和后端不一样' }
    const backend = [
      { id: 'b-1', role: 'user', content: '提问' },
      { id: 'b-2', role: 'assistant', content: '完全不同的内容' },
    ]
    const result = resolvePendingMessageIds([local], backend)
    expect(result.get(local)).toBe('b-2')
  })

  it('多条本地消息按倒序匹配，且不会重复占用同一条后端消息', () => {
    const localA = { role: 'user', content: '第一句' }
    const localB = { role: 'assistant', content: '第二句回复' }
    const backend = [
      { id: 'b-1', role: 'user', content: '第一句' },
      { id: 'b-2', role: 'assistant', content: '第二句回复' },
    ]
    const result = resolvePendingMessageIds([localA, localB], backend)
    expect(result.get(localA)).toBe('b-1')
    expect(result.get(localB)).toBe('b-2')
  })

  it('后端完全没有对应消息时返回空 Map（不报错）', () => {
    const local = { role: 'user', content: '没人接收的消息' }
    const result = resolvePendingMessageIds([local], [])
    expect(result.has(local)).toBe(false)
  })

  it('两条本地消息内容相同时，分别匹配到不同的后端条目（不会都指向同一条）', () => {
    const localA = { role: 'user', content: '你好' }
    const localB = { role: 'user', content: '你好' }
    const backend = [
      { id: 'b-1', role: 'user', content: '你好' },
      { id: 'b-2', role: 'user', content: '你好' },
    ]
    const result = resolvePendingMessageIds([localA, localB], backend)
    const ids = [result.get(localA), result.get(localB)].sort()
    expect(ids).toEqual(['b-1', 'b-2'])
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd frontend && npx vitest run src/__tests__/messageIdSync.test.js`
Expected: FAIL — 找不到模块 `../utils/messageIdSync`

- [ ] **Step 3: 实现**

创建 `frontend/src/utils/messageIdSync.js`：

```js
const PREFIX_LEN = 200

function prefixOf(content) {
  return (content || '').slice(0, PREFIX_LEN)
}

/**
 * 断流 fallback 用：把本地还没拿到真实 id 的消息，对齐到后端返回的消息列表上。
 *
 * 双重定位：
 *   1. 内容前缀匹配优先（不用完全相等——前端超长消息会被截断并加后缀标记，
 *      与后端存的完整内容不一致，只比前 200 字符）
 *   2. 匹配不到时退化为"同 role 里最近一条未被占用的"位置兜底
 *
 * 本地消息按传入顺序（应为时间正序）从后往前处理，每条匹配到的后端条目会被
 * 标记为已占用，避免重复匹配（典型场景：同一句话连续发了两次）。
 *
 * @param {Array<{role: string, content: string}>} pendingLocalMessages
 * @param {Array<{id: string, role: string, content: string}>} backendMessages
 * @returns {Map<object, string>} key 是传入的本地消息对象引用，value 是匹配到的后端 id
 */
export function resolvePendingMessageIds(pendingLocalMessages, backendMessages) {
  const result = new Map()
  const usedBackendIdx = new Set()

  for (let i = pendingLocalMessages.length - 1; i >= 0; i--) {
    const local = pendingLocalMessages[i]
    const localPrefix = prefixOf(local.content)

    let matchIdx = -1
    for (let j = backendMessages.length - 1; j >= 0; j--) {
      if (usedBackendIdx.has(j)) continue
      const b = backendMessages[j]
      if (b.role === local.role && prefixOf(b.content) === localPrefix) {
        matchIdx = j
        break
      }
    }

    if (matchIdx === -1) {
      for (let j = backendMessages.length - 1; j >= 0; j--) {
        if (usedBackendIdx.has(j)) continue
        if (backendMessages[j].role === local.role) {
          matchIdx = j
          break
        }
      }
    }

    if (matchIdx !== -1 && backendMessages[matchIdx].id) {
      result.set(local, backendMessages[matchIdx].id)
      usedBackendIdx.add(matchIdx)
    }
  }

  return result
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd frontend && npx vitest run src/__tests__/messageIdSync.test.js`
Expected: 6 passed

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/messageIdSync.js frontend/src/__tests__/messageIdSync.test.js
git commit -m "feat(frontend): resolvePendingMessageIds() 断流fallback双重定位匹配"
```

---

## Task 12: frontend websocket.js — 捕获 id、`_backendIdConfirmed`、断流 fallback 触发

**Files:**
- Modify: `frontend/src/stores/websocket.js`

**Interfaces:**
- Consumes: `resolvePendingMessageIds()`（Task 11）、`getConversation(sessionId)`（已存在于 `api.js`）
- Produces:
  - 每条 `messages.value` 里 role 为 `user`/`assistant` 的消息对象新增 `_backendIdConfirmed: boolean` 字段
  - `finalizeStream(responseTime, userMessageId, assistantMessageId)`（**签名变化**：新增两个可选参数）
  - 供 Task 13（ChatView.vue）：消息对象的 `id` 字段在拿到真实 id 后会被原地更新，`isRetracted`/勾选逻辑可以放心引用 `msg.id`

- [ ] **Step 1: 顶部新增 import**

`frontend/src/stores/websocket.js` 第 1-11 行，把：

```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { isTokenExpired } from '@/utils/jwt'
import { formatTime } from '@/utils/date'
import { genId } from '@/utils/string'
import {
  switchModel as apiSwitchModel,
  getModels as apiGetModels,
  clearAllMemory as apiClearAllMemory,
} from '@/services/api'
import { useProjectStore } from '@/stores/project'
```

改为：

```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { isTokenExpired } from '@/utils/jwt'
import { formatTime } from '@/utils/date'
import { genId } from '@/utils/string'
import { resolvePendingMessageIds } from '@/utils/messageIdSync'
import {
  switchModel as apiSwitchModel,
  getModels as apiGetModels,
  clearAllMemory as apiClearAllMemory,
  getConversation as apiGetConversation,
} from '@/services/api'
import { useProjectStore } from '@/stores/project'
```

- [ ] **Step 2: `chat_done` case 读取 id 并传给 `finalizeStream`**

第 227-234 行，把：

```js
      case 'chat_done':
        finalizeStream(data.response_time)
        lastResponseTime.value = data.response_time
        if (data.response_time != null) {
          responseTimes.value.push({ time: data.response_time, ts: Date.now() })
          if (responseTimes.value.length > 20) responseTimes.value.shift()
        }
        break
```

改为：

```js
      case 'chat_done':
        finalizeStream(data.response_time, data.user_message_id, data.assistant_message_id)
        lastResponseTime.value = data.response_time
        if (data.response_time != null) {
          responseTimes.value.push({ time: data.response_time, ts: Date.now() })
          if (responseTimes.value.length > 20) responseTimes.value.shift()
        }
        break
```

- [ ] **Step 3: `finalizeStream()` 回填 id + 触发断流 fallback**

第 347-359 行，把：

```js
  const finalizeStream = (responseTime) => {
    if (streamingIndex.value !== -1) {
      messages.value[streamingIndex.value].isStreaming = false
      if (responseTime != null) {
        messages.value[streamingIndex.value].responseTime = responseTime
      }
    }
    streamingIndex.value = -1
    isStreaming.value    = false
    chatEndSignal.value++  // notify watchers (e.g. ChatView.isThinking reset)
    // 流式完成后持久化（此时 isStreaming 已为 false）
    _saveChatHistory()
  }
```

改为：

```js
  const finalizeStream = (responseTime, userMessageId, assistantMessageId) => {
    const idx = streamingIndex.value
    if (idx !== -1) {
      const assistantMsg = messages.value[idx]
      assistantMsg.isStreaming = false
      if (responseTime != null) {
        assistantMsg.responseTime = responseTime
      }
      if (assistantMessageId) {
        assistantMsg.id = assistantMessageId
        assistantMsg._backendIdConfirmed = true
      }
      if (userMessageId) {
        // 当前流式消息之前最近的一条 user 消息即本轮发出的消息
        for (let i = idx - 1; i >= 0; i--) {
          if (messages.value[i].role === 'user') {
            messages.value[i].id = userMessageId
            messages.value[i]._backendIdConfirmed = true
            break
          }
        }
      }
      if (!userMessageId && !assistantMessageId) {
        _scheduleIdSyncFallback()
      }
    }
    streamingIndex.value = -1
    isStreaming.value    = false
    chatEndSignal.value++  // notify watchers (e.g. ChatView.isThinking reset)
    // 流式完成后持久化（此时 isStreaming 已为 false）
    _saveChatHistory()
  }

  /** 断流 fallback：本轮 chat_done 没带 id（SSE 中断、Java 兜底补发空 chat_done），
   *  延迟后重新拉取该会话，用内容前缀匹配 + 位置兜底回填最近几条消息的真实 id。 */
  const _scheduleIdSyncFallback = () => {
    setTimeout(async () => {
      try {
        const res = await apiGetConversation(currentSessionId.value)
        const backendMsgs = res?.session?.messages || res?.messages || []
        if (!backendMsgs.length) return

        const pending = messages.value
          .filter(m => (m.role === 'user' || m.role === 'assistant') && !m._backendIdConfirmed)
          .slice(-6)
        if (!pending.length) return

        const resolved = resolvePendingMessageIds(pending, backendMsgs)
        resolved.forEach((backendId, localMsg) => {
          localMsg.id = backendId
          localMsg._backendIdConfirmed = true
        })
      } catch (err) {
        console.warn('[WS] 断流后 id 同步失败（不影响正常使用）:', err)
      }
    }, 1500)
  }
```

- [ ] **Step 4: `startStreamMessage()` 标记新消息为未确认**

第 323-334 行，把：

```js
  const startStreamMessage = () => {
    messages.value.push({
      id:           genId(),
      role:         'assistant',
      content:      '',
      thinkingText: '',   // CoT <think> 内容，与正文分离存储
      isStreaming:  true,
      timestamp:    new Date()
    })
    streamingIndex.value = messages.value.length - 1
    isStreaming.value    = true
  }
```

改为：

```js
  const startStreamMessage = () => {
    messages.value.push({
      id:                 genId(),
      role:               'assistant',
      content:            '',
      thinkingText:       '',   // CoT <think> 内容，与正文分离存储
      isStreaming:        true,
      timestamp:          new Date(),
      _backendIdConfirmed: false,
    })
    streamingIndex.value = messages.value.length - 1
    isStreaming.value    = true
  }
```

- [ ] **Step 5: 手测验证**

`cd frontend && npm run dev`，登录后发一条消息，等回复完成，在浏览器 Console 执行（需要先拿到 pinia store 实例——若项目暴露了 `window.__pinia` 之类的调试钩子就用那个，否则用 Vue Devtools 的 Pinia 面板手动查看 `websocket` store 的 `messages` 数组）：检查最后两条消息（user + assistant）的 `id` 字段是否为后端返回的 uuid 格式（而不是 `genId()` 生成的本地临时格式），且 `_backendIdConfirmed === true`。

再用浏览器开发者工具的 Network 面板，在发消息后立刻切到 Offline 模拟断网（或直接断开 WebSocket 连接），观察 1.5s 后是否触发了对 `/api/conversations/{sessionId}` 的 GET 请求（断流 fallback 生效的标志）。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/stores/websocket.js
git commit -m "feat(frontend): chat_done 回填消息真实id，断流时自动fallback同步"
```

---

## Task 13: frontend ChatView.vue — 撤回模式 UI

**Files:**
- Modify: `frontend/src/views/ChatView.vue`

**Interfaces:**
- Consumes: `retractMessages()`（Task 10）、`confirmDialog.confirm()`（已存在）、`ElMessage`（已存在）
- Produces: 无供其他任务消费——这是用户可见的终端 UI，本任务用手测验证（项目里没有 ChatView 级别的组件测试先例，`frontend/src/__tests__/` 目前只测纯逻辑工具函数，遵循既有约定）

**关于本任务标记 `_backendIdConfirmed` 的补充：** Task 12 只在 `startStreamMessage()`/`finalizeStream()`/断流 fallback 三处维护了这个字段，本任务还需要在 `sendMessage()`（用户消息刚 push 时）和 `loadSession()`/`openSessionSignal` watcher（历史加载）这几处补上，否则历史消息会被断流 fallback 逻辑错误地当成"待确认"反复尝试匹配。

- [ ] **Step 1: `sendMessage()` 标记用户消息为未确认**

`frontend/src/views/ChatView.vue` 第 879-882 行，把：

```js
  const userMsg = {
    id: genId(), role: 'user', content: text, timestamp: new Date(),
    ...(imgPreview ? { imagePreview: imgPreview } : {}),
  }
```

改为：

```js
  const userMsg = {
    id: genId(), role: 'user', content: text, timestamp: new Date(),
    _backendIdConfirmed: false,
    ...(imgPreview ? { imagePreview: imgPreview } : {}),
  }
```

- [ ] **Step 2: 历史加载路径标记为已确认 + 还原 retracted 标记字段（虽然撤回消息已被后端删除不会出现，但要保证字段命名一致不报错）**

第 1021-1033 行（`openSessionSignal` watcher），把：

```js
watch(() => store.openSessionSignal, () => {
  const msgs = sessionStore.messages
  if (!msgs?.length) return
  store.messages.splice(0)
  msgs.forEach(m => store.messages.push({
    id:        m.id || genId(),
    role:      m.role,
    content:   m.content,
    timestamp: m.timestamp || new Date().toISOString(),
  }))
  nextTick(scrollToBottom)
  ElMessage({ message: '已加载历史会话', type: 'success', duration: 1500 })
})
```

改为：

```js
watch(() => store.openSessionSignal, () => {
  const msgs = sessionStore.messages
  if (!msgs?.length) return
  store.messages.splice(0)
  msgs.forEach(m => store.messages.push({
    id:        m.id || genId(),
    role:      m.role,
    content:   m.content,
    timestamp: m.timestamp || new Date().toISOString(),
    _backendIdConfirmed: !!m.id,
  }))
  nextTick(scrollToBottom)
  ElMessage({ message: '已加载历史会话', type: 'success', duration: 1500 })
})
```

第 1051-1078 行（`loadSession()`），把：

```js
const loadSession = async (sessionId) => {
  const res = await getConversation(sessionId)
  const msgs = res?.session?.messages || res?.messages
  if (!msgs?.length) {
    ElMessage({ message: '该会话暂无消息', type: 'warning', duration: 2000 })
    return
  }
  store.messages.splice(0)
  store.currentSessionId = sessionId
  localStorage.setItem('ia_session_id', sessionId)
  msgs.forEach(m => {
    const entry = {
      id: genId(),
      role: m.role,
      content: m.content,
      timestamp: m.timestamp || new Date().toISOString(),
    }
    // 还原多模态图片预览（base64 存于 images_b64[0]）
    if (m.images_b64?.length) {
      entry.images_b64 = m.images_b64
      entry.imagePreview = `data:image/jpeg;base64,${m.images_b64[0]}`
    }
    store.messages.push(entry)
  })
  showHistory.value = false
  nextTick(scrollToBottom)
  ElMessage({ message: '已加载历史会话', type: 'success', duration: 1500 })
}
```

改为：

```js
const loadSession = async (sessionId) => {
  const res = await getConversation(sessionId)
  const msgs = res?.session?.messages || res?.messages
  if (!msgs?.length) {
    ElMessage({ message: '该会话暂无消息', type: 'warning', duration: 2000 })
    return
  }
  store.messages.splice(0)
  store.currentSessionId = sessionId
  localStorage.setItem('ia_session_id', sessionId)
  msgs.forEach(m => {
    const entry = {
      id: m.id || genId(),
      role: m.role,
      content: m.content,
      timestamp: m.timestamp || new Date().toISOString(),
      _backendIdConfirmed: !!m.id,
    }
    // 还原多模态图片预览（base64 存于 images_b64[0]）
    if (m.images_b64?.length) {
      entry.images_b64 = m.images_b64
      entry.imagePreview = `data:image/jpeg;base64,${m.images_b64[0]}`
    }
    store.messages.push(entry)
  })
  showHistory.value = false
  nextTick(scrollToBottom)
  ElMessage({ message: '已加载历史会话', type: 'success', duration: 1500 })
}
```

- [ ] **Step 3: import `retractMessages`**

第 473-477 行，把：

```js
import {
  submitFeedback as apiFeedback,
  listConversations, getConversation, deleteConversation,
  branchConversation as apiBranchConversation,
} from '@/services/api'
```

改为：

```js
import {
  submitFeedback as apiFeedback,
  listConversations, getConversation, deleteConversation,
  branchConversation as apiBranchConversation,
  retractMessages as apiRetractMessages,
} from '@/services/api'
```

- [ ] **Step 4: 撤回模式状态 + 操作函数**

在 `const showExportMenu = ref(false)`（约第 979 行）附近新增一段（放在 `handleClearChat` 定义之后即可，紧邻同类对话操作函数）：

```js
// ── 撤回模式 ──────────────────────────────────────────────
const MAX_RETRACT_BATCH = 50
const retractMode       = ref(false)
const selectedRetractIds = ref(new Set())

const canRetract = (msg) => (msg.role === 'user' || msg.role === 'assistant') && !msg.isRetracted

const toggleRetractMode = () => {
  retractMode.value = !retractMode.value
  if (!retractMode.value) selectedRetractIds.value = new Set()
}

const toggleRetractSelect = (msg) => {
  if (!canRetract(msg) || msg.id == null) return
  const next = new Set(selectedRetractIds.value)
  if (next.has(msg.id)) {
    next.delete(msg.id)
  } else if (next.size < MAX_RETRACT_BATCH) {
    next.add(msg.id)
  }
  selectedRetractIds.value = next
}

const cancelRetractSelection = () => {
  retractMode.value = false
  selectedRetractIds.value = new Set()
}

const confirmRetract = async () => {
  const ids = Array.from(selectedRetractIds.value)
  if (!ids.length) return

  const warningSuffix = ids.length > 1
    ? '\n\n⚠️ 同时撤回多条消息可能造成对话上下文不连贯，请确认这些消息之间没有被后续内容依赖引用。'
    : ''
  const ok = await confirmDialog.confirm(
    `确认撤回${ids.length > 1 ? `这 ${ids.length} 条消息` : '这条消息'}？此操作将从存储中永久删除，无法恢复。${warningSuffix}`,
    { title: '撤回消息', confirmText: '撤回', danger: true }
  )
  if (!ok) return

  try {
    const res = await apiRetractMessages(store.currentSessionId, ids)
    const deletedIds = new Set(res?.deleted_ids || [])
    messages.value.forEach(msg => {
      if (deletedIds.has(msg.id)) {
        msg.content = ''
        msg.isRetracted = true
      }
    })
    if ((res?.deleted ?? 0) < (res?.requested ?? ids.length)) {
      ElMessage({
        message: `部分消息已不存在或删除失败（${res.requested} 条中成功 ${res.deleted} 条）`,
        type: 'warning', duration: 3000,
      })
    } else {
      ElMessage({ message: `已撤回 ${res.deleted} 条消息`, type: 'success', duration: 1500 })
    }
  } catch {
    ElMessage({ message: '撤回失败，请重试', type: 'error', duration: 2000 })
  } finally {
    retractMode.value = false
    selectedRetractIds.value = new Set()
  }
}
```

- [ ] **Step 5: 工具条新增「撤回」按钮**

第 400-421 行（`.input-toolbar` 区块），把：

```html
          <!-- 会话操作工具条：历史 / 导出 / 清空（水平排列，右下角） -->
          <div class="input-toolbar">
            <button class="toolbar-btn" :class="{ active: showHistory }" title="查看历史会话" @click="toggleHistory">
              <i class="fas fa-history" />
            </button>
            <div class="toolbar-export-wrap" v-if="messages.length > 0">
              <button class="toolbar-btn" title="导出对话" @click.stop="showExportMenu = !showExportMenu">
                <i class="fas fa-download" />
              </button>
              <div v-if="showExportMenu" class="export-menu" @click.stop>
                <button @click="exportChat('md'); showExportMenu = false">
                  <i class="fab fa-markdown" /> Markdown
                </button>
                <button @click="exportChat('txt'); showExportMenu = false">
                  <i class="fas fa-file-alt" /> TXT
                </button>
              </div>
            </div>
            <button v-if="messages.length > 0" class="toolbar-btn toolbar-btn-danger" title="清空对话" @click.stop="handleClearChat">
              <i class="fas fa-trash-alt" />
            </button>
          </div>
```

改为：

```html
          <!-- 会话操作工具条：历史 / 导出 / 撤回 / 清空（水平排列，右下角） -->
          <div class="input-toolbar">
            <button class="toolbar-btn" :class="{ active: showHistory }" title="查看历史会话" @click="toggleHistory">
              <i class="fas fa-history" />
            </button>
            <div class="toolbar-export-wrap" v-if="messages.length > 0">
              <button class="toolbar-btn" title="导出对话" @click.stop="showExportMenu = !showExportMenu">
                <i class="fas fa-download" />
              </button>
              <div v-if="showExportMenu" class="export-menu" @click.stop>
                <button @click="exportChat('md'); showExportMenu = false">
                  <i class="fab fa-markdown" /> Markdown
                </button>
                <button @click="exportChat('txt'); showExportMenu = false">
                  <i class="fas fa-file-alt" /> TXT
                </button>
              </div>
            </div>
            <button v-if="messages.length > 0" class="toolbar-btn" :class="{ active: retractMode }" title="撤回消息" @click.stop="toggleRetractMode">
              <i class="fas fa-rotate-left" />
            </button>
            <button v-if="messages.length > 0" class="toolbar-btn toolbar-btn-danger" title="清空对话" @click.stop="handleClearChat">
              <i class="fas fa-trash-alt" />
            </button>
          </div>
```

- [ ] **Step 6: 消息气泡渲染——撤回模式勾选框 + 已撤回占位条**

第 70-76 行（消息气泡循环开头），把：

```html
      <!-- 消息气泡 -->
      <div
        v-for="(msg, index) in messages"
        :key="msg.id != null ? msg.id : index"
        class="message-row"
        :class="msg.role"
      >
```

改为：

```html
      <!-- 消息气泡 -->
      <div
        v-for="(msg, index) in messages"
        :key="msg.id != null ? msg.id : index"
        class="message-row"
        :class="[msg.role, { 'retract-mode': retractMode && canRetract(msg) }]"
        @click="retractMode && canRetract(msg) ? toggleRetractSelect(msg) : null"
      >
        <!-- 撤回模式勾选框 -->
        <div v-if="retractMode && canRetract(msg)" class="retract-checkbox" @click.stop="toggleRetractSelect(msg)">
          <i :class="selectedRetractIds.has(msg.id) ? 'fas fa-check-square' : 'far fa-square'" />
        </div>
        <!-- 已撤回占位条 -->
        <div v-if="msg.isRetracted" class="retracted-placeholder">
          <i class="fas fa-rotate-left" /> 该消息已被撤回
        </div>
```

紧接着原有的 `<template v-if="msg.role === 'tool_calls'">`（第 78 行）整段，外面包一层 `v-if="!msg.isRetracted"`，即把：

```html
        <!-- 工具调用卡片 -->
        <template v-if="msg.role === 'tool_calls'">
```

改为：

```html
        <!-- 工具调用卡片 -->
        <template v-if="!msg.isRetracted && msg.role === 'tool_calls'">
```

以及紧接着的 `<!-- 普通消息（头像 + 气泡） -->` 那个 `<template v-else>`（第 107 行）：

```html
        <!-- 普通消息（头像 + 气泡） -->
        <template v-else>
```

改为：

```html
        <!-- 普通消息（头像 + 气泡） -->
        <template v-else-if="!msg.isRetracted">
```

（这样已撤回的消息只显示占位条，不渲染原来的头像/气泡/操作栏。）

- [ ] **Step 7: 底部浮层「已选 N 条」操作条**

在 `</div>` 闭合 `.message-list`（即 Step 6 提到的消息气泡 `v-for` 所在的外层容器结束处，紧邻"思考中指示器"代码块之前或之后任意位置，保持在 `.message-list` 内部即可）旁新增一个独立于 `.message-list` 的浮层，放在 `.message-list` 这个 `<div>` 结束 `</div>` 之后、整个 `.chat-view` 结束 `</div>` 之前：

```html
    <!-- 撤回模式底部浮层 -->
    <div v-if="retractMode" class="retract-toolbar">
      <span class="retract-count">
        已选 {{ selectedRetractIds.size }} 条
        <template v-if="selectedRetractIds.size >= MAX_RETRACT_BATCH"> （已达单次上限，请先确认或取消部分选择）</template>
      </span>
      <button class="retract-cancel-btn" @click="cancelRetractSelection">取消</button>
      <button class="retract-confirm-btn" :disabled="!selectedRetractIds.size" @click="confirmRetract">确认撤回</button>
    </div>
```

- [ ] **Step 8: 新增 CSS**

在 `<style>` 区块里 `.bubble-actions { ... }`（约第 1781 行）附近新增：

```css
.message-row.retract-mode { cursor: pointer; }
.retract-checkbox {
  display: flex; align-items: center; padding: 0 6px; color: var(--color-primary, #667eea);
  font-size: 1rem; flex-shrink: 0;
}
.retracted-placeholder {
  color: #9ca3af; font-style: italic; font-size: 0.85rem; padding: 6px 12px;
  display: flex; align-items: center; gap: 6px;
}
.retract-toolbar {
  position: sticky; bottom: 0; left: 0; right: 0;
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; background: #fff7ed; border-top: 1px solid #fed7aa;
  font-size: 0.85rem; z-index: 5;
}
.retract-count { flex: 1; color: #9a3412; }
.retract-cancel-btn, .retract-confirm-btn {
  padding: 6px 14px; border-radius: 6px; font-size: 0.85rem; cursor: pointer;
}
.retract-cancel-btn { background: #fff; border: 1px solid #d1d5db; color: #374151; }
.retract-confirm-btn { background: #ea580c; border: none; color: #fff; }
.retract-confirm-btn:disabled { background: #fdba74; cursor: not-allowed; }
```

- [ ] **Step 9: 手测验证（dev server + 浏览器）**

```bash
cd frontend && npm run dev
```

在浏览器里完整走一遍：

1. 发送 2-3 轮对话，确保每轮都有 user + assistant 消息
2. 点击工具条「撤回」图标（↺），确认进入撤回模式：每条 user/assistant 气泡左侧出现勾选框，气泡变成可点击态
3. 勾选其中 1 条 assistant 消息，点击「确认撤回」，确认弹出 `useConfirmDialogStore` 的确认框（不是浏览器原生 `confirm`），文案是单条措辞
4. 确认后，该消息应变成灰色斜体「该消息已被撤回」占位条，原内容/头像/操作按钮全部消失
5. 重新进入撤回模式，同时勾选 2 条消息，点击「确认撤回」，确认弹窗文案里多了"上下文连贯性"警告那句
6. 勾选满 50 条（如果消息不够 50 条，改小 `MAX_RETRACT_BATCH` 临时验证后改回 50），确认浮层文案变成"已达单次上限"
7. 撤回一条根本不存在于后端的 id（可以在 Console 手动 `store.messages.push({id:'fake-id', role:'user', content:'测试', timestamp:new Date()})` 后进入撤回模式勾选它），确认弹出 `ElMessage.warning`「部分消息已不存在或删除失败」
8. 撤回后刷新页面、重新从历史会话列表加载同一会话，确认被撤回的消息确实不再出现（且前后消息衔接自然，没有空位）

- [ ] **Step 10: Commit**

```bash
git add frontend/src/views/ChatView.vue
git commit -m "feat(frontend): ChatView 撤回模式UI——勾选/批量上限/确认/占位条渲染"
```

---

## Task 14: client/session.py — id 字段 + retract() + set_last_message_id()

**Files:**
- Modify: `client/session.py`
- Test: `client/tests/test_session.py`（扩展现有文件）

**Interfaces:**
- Produces:
  - `ChatSession.add_user(content, msg_id=None)`
  - `ChatSession.add_assistant(content, tool_calls=None, msg_id=None)`
  - `ChatSession.set_last_message_id(role, msg_id) -> None`
  - `ChatSession.retract(message_ids: list[str]) -> int`
  - 供 Task 16（`repl.py`）调用

- [ ] **Step 1: 写失败测试**

在 `client/tests/test_session.py` 末尾追加：

```python
# ── message id ────────────────────────────────────────────────────────────────

def test_add_user_with_msg_id(no_save_session):
    no_save_session.add_user("hello", msg_id="mid-1")
    assert no_save_session.messages[0]["id"] == "mid-1"


def test_add_user_without_msg_id_has_no_id_key(no_save_session):
    no_save_session.add_user("hello")
    assert "id" not in no_save_session.messages[0]


def test_add_assistant_with_msg_id(no_save_session):
    no_save_session.add_assistant("hi", msg_id="mid-2")
    assert no_save_session.messages[0]["id"] == "mid-2"


def test_set_last_message_id_backfills_matching_role(no_save_session):
    no_save_session.add_user("hello")
    no_save_session.set_last_message_id("user", "mid-backfilled")
    assert no_save_session.messages[-1]["id"] == "mid-backfilled"


def test_set_last_message_id_noop_when_role_mismatch(no_save_session):
    no_save_session.add_user("hello")
    no_save_session.add_assistant("hi")
    no_save_session.set_last_message_id("user", "mid-x")  # 最后一条是 assistant，不该被改
    assert "id" not in no_save_session.messages[-1]


def test_set_last_message_id_noop_when_id_is_none(no_save_session):
    no_save_session.add_user("hello")
    no_save_session.set_last_message_id("user", None)
    assert "id" not in no_save_session.messages[-1]


def test_set_last_message_id_persists(tmp_session):
    tmp_session.add_user("hello")
    tmp_session.set_last_message_id("user", "mid-persisted")
    data = json.loads(tmp_session._file.read_text(encoding="utf-8"))
    assert data["messages"][-1]["id"] == "mid-persisted"


# ── retract ──────────────────────────────────────────────────────────────────

def test_retract_removes_matching_ids(no_save_session):
    no_save_session.add_user("first", msg_id="mid-1")
    no_save_session.add_assistant("second", msg_id="mid-2")
    no_save_session.add_user("third", msg_id="mid-3")

    removed = no_save_session.retract(["mid-1", "mid-2"])

    assert removed == 2
    assert [m["content"] for m in no_save_session.messages] == ["third"]


def test_retract_ignores_unknown_ids(no_save_session):
    no_save_session.add_user("first", msg_id="mid-1")

    removed = no_save_session.retract(["mid-does-not-exist"])

    assert removed == 0
    assert len(no_save_session.messages) == 1


def test_retract_persists(tmp_session):
    tmp_session.add_user("first", msg_id="mid-1")
    tmp_session.retract(["mid-1"])
    data = json.loads(tmp_session._file.read_text(encoding="utf-8"))
    assert data["messages"] == []
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd client && python -m pytest tests/test_session.py -v`
Expected: FAIL with `TypeError: add_user() got an unexpected keyword argument 'msg_id'`

- [ ] **Step 3: 实现**

`client/session.py` 第 28-53 行，把：

```python
    def add_user(self, content: str) -> None:
        self.messages.append({
            "role": "user",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        })
        self._persist()

    def add_assistant(self, content: str, tool_calls: Optional[list] = None) -> None:
        entry = {
            "role": "assistant",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        }
        if tool_calls:
            entry["tool_calls"] = tool_calls
        self.messages.append(entry)
        self._persist()

    def add_system(self, content: str) -> None:
        self.messages.append({
            "role": "system",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        })
        self._persist()
```

改为：

```python
    def add_user(self, content: str, msg_id: Optional[str] = None) -> None:
        entry = {
            "role": "user",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        }
        if msg_id:
            entry["id"] = msg_id
        self.messages.append(entry)
        self._persist()

    def add_assistant(self, content: str, tool_calls: Optional[list] = None,
                       msg_id: Optional[str] = None) -> None:
        entry = {
            "role": "assistant",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        }
        if tool_calls:
            entry["tool_calls"] = tool_calls
        if msg_id:
            entry["id"] = msg_id
        self.messages.append(entry)
        self._persist()

    def add_system(self, content: str) -> None:
        self.messages.append({
            "role": "system",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        })
        self._persist()

    def set_last_message_id(self, role: str, msg_id: Optional[str]) -> None:
        """Backfill the id on the most recent message of the given role —
        used once the backend returns the canonical id after the request completes."""
        if msg_id and self.messages and self.messages[-1].get("role") == role:
            self.messages[-1]["id"] = msg_id
            self._persist()

    def retract(self, message_ids: list[str]) -> int:
        """Remove messages matching the given ids. Returns the number removed."""
        ids_set = set(message_ids)
        before = len(self.messages)
        self.messages = [m for m in self.messages if m.get("id") not in ids_set]
        removed = before - len(self.messages)
        if removed:
            self._persist()
        return removed
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd client && python -m pytest tests/test_session.py -v`
Expected: 全部 passed

- [ ] **Step 5: Commit**

```bash
git add client/session.py client/tests/test_session.py
git commit -m "feat(client): ChatSession 支持消息id、set_last_message_id()、retract()"
```

---

## Task 15: client/api.py — retract_messages()

**Files:**
- Modify: `client/api.py`
- Test: `client/tests/test_api.py`（扩展现有文件——先读取该文件确认现有测试用什么 mock 方式，与之保持一致；若文件用 `responses` 库或 `unittest.mock.patch("requests.post")`，沿用同一种）

**Interfaces:**
- Produces: `AgentClient.retract_messages(session_id: str, message_ids: list[str]) -> dict`

- [ ] **Step 1: 写失败测试**

先打开 `client/tests/test_api.py` 看现有测试对 `requests.post` 的 mock 方式（比如是否用 `@patch("api.requests.post")` 或 `responses.activate`），然后用同样的方式追加：

```python
def test_retract_messages_posts_to_correct_endpoint(monkeypatch):
    captured = {}

    class _FakeResp:
        def raise_for_status(self): pass
        def json(self): return {"success": True, "requested": 2, "deleted": 2, "deleted_ids": ["m1", "m2"], "memory_purged": 1}

    def _fake_post(url, headers=None, json=None, timeout=None):
        captured["url"] = url
        captured["json"] = json
        return _FakeResp()

    monkeypatch.setattr("requests.post", _fake_post)

    client = AgentClient(url="http://localhost:8000", jwt_secret="secret", user_id="u1")
    result = client.retract_messages("sess1", ["m1", "m2"])

    assert captured["url"] == "http://localhost:8000/api/conversations/sess1/retract"
    assert captured["json"] == {"message_ids": ["m1", "m2"]}
    assert result["deleted"] == 2
```

（如果项目现有测试用的不是 `monkeypatch.setattr("requests.post", ...)` 这种方式，按现有文件里实际的 mock 写法改写本测试，断言内容不变。）

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd client && python -m pytest tests/test_api.py -v -k retract`
Expected: FAIL with `AttributeError: 'AgentClient' object has no attribute 'retract_messages'`

- [ ] **Step 3: 实现**

`client/api.py` 第 107-118 行（`chat()` 方法之前），新增方法：

```python
    # ── Conversations ────────────────────────────────────────────────
    def retract_messages(self, session_id: str, message_ids: list) -> dict:
        """Retract (permanently delete) messages from a conversation session."""
        r = requests.post(
            f"{self.base_url}/api/conversations/{session_id}/retract",
            headers=self._headers(),
            json={"message_ids": message_ids},
            timeout=15,
        )
        r.raise_for_status()
        return r.json()
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd client && python -m pytest tests/test_api.py -v`
Expected: 全部 passed

- [ ] **Step 5: Commit**

```bash
git add client/api.py client/tests/test_api.py
git commit -m "feat(client): AgentClient.retract_messages() 调用撤回端点"
```

---

## Task 16: client/repl.py — !history 编号、id 捕获、!retract 命令

**Files:**
- Modify: `client/repl.py`
- Test: `client/tests/test_repl.py`（扩展现有文件——先读取确认现有测试如何驱动 `run_repl`/mock `console.input`，沿用同一方式）

**Interfaces:**
- Consumes: `session.retract()`/`session.set_last_message_id()`（Task 14）、`client.retract_messages()`（Task 15）
- Produces:
  - `stream_response(...) -> Tuple[str, list, Optional[str], Optional[str]]`（**签名变化**：原来返回 2 元组，现在返回 4 元组，多出 `user_message_id`、`assistant_message_id`）
  - `non_stream_response(...) -> Tuple[str, list, Optional[str], Optional[str]]`（同样的签名变化）

- [ ] **Step 1: 写失败测试**

先读 `client/tests/test_repl.py` 确认现有测试如何构造 fake `client`/`session`（很可能用 `unittest.mock.MagicMock` 模拟 `AgentClient`），然后追加（以下示例假设现有测试已有 `_make_client()`/`_make_session()` 之类的 helper，若没有就直接 `MagicMock()`）：

```python
def test_stream_response_returns_ids_from_done_event():
    client = MagicMock()
    client.chat_stream.return_value = iter([
        {"type": "token", "data": "Hi"},
        {"type": "done", "data": {
            "content": "Hi",
            "user_message_id": "mid-u",
            "assistant_message_id": "mid-a",
        }},
    ])
    session = MagicMock()

    text, tool_calls, user_id, assistant_id = stream_response(
        client, session, "hello", use_tools=True, use_memory=True,
    )

    assert text == "Hi"
    assert user_id == "mid-u"
    assert assistant_id == "mid-a"


def test_stream_response_missing_ids_returns_none():
    client = MagicMock()
    client.chat_stream.return_value = iter([
        {"type": "token", "data": "Hi"},
        {"type": "done", "data": {"content": "Hi"}},
    ])
    session = MagicMock()

    _, _, user_id, assistant_id = stream_response(
        client, session, "hello", use_tools=True, use_memory=True,
    )

    assert user_id is None
    assert assistant_id is None


def test_non_stream_response_returns_ids():
    client = MagicMock()
    client.chat.return_value = {
        "response": "ok", "tool_calls": [],
        "user_message_id": "mid-u2", "assistant_message_id": "mid-a2",
    }

    text, tool_calls, user_id, assistant_id = non_stream_response(
        client, "hello", use_tools=True, use_memory=True,
    )

    assert text == "ok"
    assert user_id == "mid-u2"
    assert assistant_id == "mid-a2"
```

（顶部需要 `from unittest.mock import MagicMock` 和 `from repl import stream_response, non_stream_response` —— 若现有文件已有这些 import 就不用重复加。）

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd client && python -m pytest tests/test_repl.py -v -k "returns_ids"`
Expected: FAIL —— `ValueError: not enough values to unpack (expected 4, got 2)`

- [ ] **Step 3: 实现 — `stream_response()`/`non_stream_response()` 返回 id**

`client/repl.py` 第 95-167 行，把函数签名和 `done` 分支、返回语句：

```python
def stream_response(
    client: AgentClient,
    session: ChatSession,
    message: str,
    use_tools: bool,
    use_memory: bool,
) -> Tuple[str, list]:
    """Send message with streaming; print tokens live. Returns (text, tool_calls)."""
    full_text = ""
    tool_calls: list = []
    in_tool_phase = False
```

改为：

```python
def stream_response(
    client: AgentClient,
    session: ChatSession,
    message: str,
    use_tools: bool,
    use_memory: bool,
) -> Tuple[str, list, Optional[str], Optional[str]]:
    """Send message with streaming; print tokens live.
    Returns (text, tool_calls, user_message_id, assistant_message_id)."""
    full_text = ""
    tool_calls: list = []
    in_tool_phase = False
    user_message_id: Optional[str] = None
    assistant_message_id: Optional[str] = None
```

第 147-150 行的 `done` 分支：

```python
            elif etype == "done":
                if not full_text and isinstance(data, dict):
                    full_text = data.get("content", "")
                break
```

改为：

```python
            elif etype == "done":
                if isinstance(data, dict):
                    if not full_text:
                        full_text = data.get("content", "")
                    user_message_id = data.get("user_message_id")
                    assistant_message_id = data.get("assistant_message_id")
                break
```

第 166-167 行的返回语句：

```python
    print()  # newline after stream ends
    return full_text, tool_calls
```

改为：

```python
    print()  # newline after stream ends
    return full_text, tool_calls, user_message_id, assistant_message_id
```

`non_stream_response()`（第 170-192 行），把：

```python
def non_stream_response(
    client: AgentClient,
    message: str,
    use_tools: bool,
    use_memory: bool,
) -> Tuple[str, list]:
    """Send message without streaming. Returns (text, tool_calls)."""
    result = client.chat(message, use_tools=use_tools, use_memory=use_memory)
    text = result.get("response", "")
    tool_calls = result.get("tool_calls", [])
    if _RICH and console:
        console.print("\n[bold green]Assistant:[/bold green]")
        _print_md(text)
    else:
        print(f"\nAssistant:\n{text}")
    if tool_calls:
        if _RICH and console:
            console.print("[dim yellow]⚙ Tool calls:[/dim yellow]")
        else:
            print("⚙ Tool calls:")
        for tc in tool_calls:
            _print_tool_call(tc)
    return text, tool_calls
```

改为：

```python
def non_stream_response(
    client: AgentClient,
    message: str,
    use_tools: bool,
    use_memory: bool,
) -> Tuple[str, list, Optional[str], Optional[str]]:
    """Send message without streaming.
    Returns (text, tool_calls, user_message_id, assistant_message_id)."""
    result = client.chat(message, use_tools=use_tools, use_memory=use_memory)
    text = result.get("response", "")
    tool_calls = result.get("tool_calls", [])
    if _RICH and console:
        console.print("\n[bold green]Assistant:[/bold green]")
        _print_md(text)
    else:
        print(f"\nAssistant:\n{text}")
    if tool_calls:
        if _RICH and console:
            console.print("[dim yellow]⚙ Tool calls:[/dim yellow]")
        else:
            print("⚙ Tool calls:")
        for tc in tool_calls:
            _print_tool_call(tc)
    return text, tool_calls, result.get("user_message_id"), result.get("assistant_message_id")
```

顶部 import（第 1-7 行）需要 `Optional`，把：

```python
"""Interactive REPL for the CLI client."""
from __future__ import annotations
import sys
from typing import Optional, Tuple

from api import AgentClient
from session import ChatSession
```

确认 `Optional` 已经在 import 列表里——是的，已经有了，本步骤不需要改动这一行。

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd client && python -m pytest tests/test_repl.py -v`
Expected: 全部 passed（注意：如果项目里还有其他调用 `stream_response`/`non_stream_response` 的测试用旧的 2 元组解包方式，会在这一步暴露出来——按 Step 5 处理）

- [ ] **Step 5: 修复主循环里的调用点 + 新增 `!retract` 命令 + `!history` 编号**

第 313-326 行（`!history` 命令处理），把：

```python
            elif cmd == "!history":
                recent = session.recent(10)
                if not recent:
                    _print("No messages yet.", "dim")
                else:
                    for msg in recent:
                        role = msg["role"]
                        content = msg["content"][:200]
                        ts = msg.get("timestamp", "")[:16]
                        if _RICH and console:
                            color = "blue" if role == "user" else "green"
                            console.print(f"[{color}]{role}[/{color}] [{ts}]: {content}")
                        else:
                            print(f"{role} [{ts}]: {content}")
```

改为：

```python
            elif cmd == "!history":
                recent = session.recent(10)
                if not recent:
                    _print("No messages yet.", "dim")
                else:
                    for i, msg in enumerate(recent, start=1):
                        role = msg["role"]
                        content = msg["content"][:200]
                        ts = msg.get("timestamp", "")[:16]
                        if _RICH and console:
                            color = "blue" if role == "user" else "green"
                            console.print(f"[{i}] [{color}]{role}[/{color}] [{ts}]: {content}")
                        else:
                            print(f"[{i}] {role} [{ts}]: {content}")
```

第 336-338 行（`!clear` 命令）后面新增 `!retract` 命令：

```python
            elif cmd == "!clear":
                session.clear()
                _print("Session cleared. New session started.", "yellow")

            elif cmd == "!retract":
                if not arg:
                    _print("Usage: !retract <编号>[,<编号>...]（编号见 !history）", "yellow")
                else:
                    recent = session.recent(10)
                    try:
                        indices = [int(x.strip()) for x in arg.split(",")]
                    except ValueError:
                        _print("编号格式错误，应为逗号分隔的数字，如: !retract 2,4", "red")
                        indices = []

                    targets = []
                    for idx in indices:
                        if idx < 1 or idx > len(recent):
                            _print(f"编号 {idx} 超出范围（当前 !history 共 {len(recent)} 条）", "red")
                            continue
                        msg = recent[idx - 1]
                        if not msg.get("id"):
                            _print(f"编号 {idx} 的消息无法撤回（旧版本数据，缺少 id）", "yellow")
                            continue
                        targets.append(msg)

                    if targets:
                        for t in targets:
                            preview = t["content"][:60]
                            _print(f"  [{t['role']}] {preview}", "dim")
                        warn = ""
                        if len(targets) > 1:
                            warn = "\n⚠️ 同时撤回多条消息可能造成对话上下文不连贯，请确认这些消息之间没有被后续内容依赖引用。"
                        confirm = input(f"确认撤回以上 {len(targets)} 条消息？此操作将从存储中永久删除，无法恢复。{warn}\n输入 y 确认: ")
                        if confirm.strip().lower() == "y":
                            target_ids = [t["id"] for t in targets]
                            try:
                                client.retract_messages(session.session_id, target_ids)
                                removed = session.retract(target_ids)
                                _print(f"已撤回 {removed} 条消息", "green")
                            except Exception as e:
                                _print(f"撤回失败: {e}", "red")
                        else:
                            _print("已取消", "dim")
```

第 64-90 行的 `_HELP`/`_HELP_PLAIN` 文本，把：

```python
_HELP = """
[bold]Available commands:[/bold]
  [cyan]!help[/cyan]              Show this help
  [cyan]!models[/cyan]            List available models
  [cyan]!model <name>[/cyan]      Switch to a different model
  [cyan]!personas[/cyan]          List available personas
  [cyan]!persona <name>[/cyan]    Switch to a different persona
  [cyan]!history[/cyan]           Show recent conversation (last 10 messages)
  [cyan]!sessions[/cyan]          List saved session files
  [cyan]!clear[/cyan]             Start a new session (clears history)
  [cyan]!exit / !quit[/cyan]      Exit the REPL
  [dim]Anything else → sent as a chat message[/dim]
"""

_HELP_PLAIN = """
Available commands:
  !help              Show this help
  !models            List available models
  !model <name>      Switch to a different model
  !personas          List available personas
  !persona <name>    Switch to a different persona
  !history           Show recent conversation (last 10 messages)
  !sessions          List saved session files
  !clear             Start a new session (clears history)
  !exit / !quit      Exit the REPL
  Anything else → sent as a chat message
"""
```

改为：

```python
_HELP = """
[bold]Available commands:[/bold]
  [cyan]!help[/cyan]              Show this help
  [cyan]!models[/cyan]            List available models
  [cyan]!model <name>[/cyan]      Switch to a different model
  [cyan]!personas[/cyan]          List available personas
  [cyan]!persona <name>[/cyan]    Switch to a different persona
  [cyan]!history[/cyan]           Show recent conversation (last 10 messages, numbered)
  [cyan]!retract <编号>[/cyan]    Permanently delete message(s) by number from !history
                       （编号取自 !history，逗号分隔可批量；注意：终端里已打印的旧行不会被改写，
                        只影响存储和下一次 !history 的展示）
  [cyan]!sessions[/cyan]          List saved session files
  [cyan]!clear[/cyan]             Start a new session (clears history)
  [cyan]!exit / !quit[/cyan]      Exit the REPL
  [dim]Anything else → sent as a chat message[/dim]
"""

_HELP_PLAIN = """
Available commands:
  !help              Show this help
  !models            List available models
  !model <name>      Switch to a different model
  !personas          List available personas
  !persona <name>    Switch to a different persona
  !history           Show recent conversation (last 10 messages, numbered)
  !retract <编号>    Permanently delete message(s) by number from !history
                     （编号取自 !history，逗号分隔可批量；注意：终端里已打印的旧行不会被改写，
                      只影响存储和下一次 !history 的展示）
  !sessions          List saved session files
  !clear             Start a new session (clears history)
  !exit / !quit      Exit the REPL
  Anything else → sent as a chat message
"""
```

最后，第 345-360 行的主循环（聊天消息发送处），把：

```python
        # ── Chat message ─────────────────────────────────────────
        session.add_user(line)
        try:
            if stream:
                text, tool_calls = stream_response(
                    client, session, line, use_tools, use_memory
                )
            else:
                text, tool_calls = non_stream_response(
                    client, line, use_tools, use_memory
                )
            session.add_assistant(text, tool_calls or None)
        except requests_error() as e:
            _print(f"Request failed: {e}", "red")
        except Exception as e:
            _print(f"Error: {e}", "red")
```

改为：

```python
        # ── Chat message ─────────────────────────────────────────
        session.add_user(line)
        try:
            if stream:
                text, tool_calls, user_msg_id, assistant_msg_id = stream_response(
                    client, session, line, use_tools, use_memory
                )
            else:
                text, tool_calls, user_msg_id, assistant_msg_id = non_stream_response(
                    client, line, use_tools, use_memory
                )
            session.set_last_message_id("user", user_msg_id)
            session.add_assistant(text, tool_calls or None, msg_id=assistant_msg_id)
        except requests_error() as e:
            _print(f"Request failed: {e}", "red")
        except Exception as e:
            _print(f"Error: {e}", "red")
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd client && python -m pytest tests/ -v`
Expected: 全部 passed

- [ ] **Step 7: 手测验证**

```bash
cd client && python main.py
```

走一遍：发 2 条消息 → `!history`（确认带编号）→ `!retract 1`（确认弹出预览+二次确认，输入 `y`）→ 再 `!history`（确认那条已经不在列表里）→ `!retract 99`（确认提示"超出范围"）。

- [ ] **Step 8: Commit**

```bash
git add client/repl.py client/tests/test_repl.py
git commit -m "feat(client): !history 编号 + !retract 命令 + 主循环回填 message id"
```

---

## 全部任务完成后的回归检查

- [ ] Run: `cd agent && python -m pytest tests/ -v` — 全部 passed
- [ ] Run: `cd backend/web && mvn test` — BUILD SUCCESS
- [ ] Run: `cd frontend && npx vitest run` — 全部 passed
- [ ] Run: `cd client && python -m pytest tests/ -v` — 全部 passed
- [ ] 按设计文档 `docs/superpowers/specs/2026-06-21-message-retraction-design.md` 第 7 节"测试要点"逐项过一遍手测/集成测清单，重点是飞书联动（需要真实飞书测试环境，若没有可先跳过并在 PR 描述里注明）

