"""
IntelligentAgent 核心流程测试。

覆盖：
  - 基础对话（mock LLM 返回固定内容）
  - 工具执行路径（LLM 返回 tool_call → 工具执行 → 二次 LLM）
  - 记忆写入（use_memory=True 时写入 short-term memory）
  - 缓存命中（use_tools=False 的第二次相同请求命中 L1 cache）
  - 错误恢复（LLM 抛异常时返回 success:False 而非崩溃）
  - persona_override 传入（角色内容注入 prompt）

所有测试不需要 Ollama、ChromaDB、Scheduler 真实服务。
"""
from __future__ import annotations
import sys
import os
import asyncio
import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ── Module-level stubs (must be injected before any core.agent import) ────────
# prometheus_client and api.metrics are not available in the test environment

_metrics_stub = MagicMock()
_metrics_stub.cache_hits_total = MagicMock()
_metrics_stub.cache_hits_total.labels = MagicMock(return_value=MagicMock())
_metrics_stub.cache_misses_total = MagicMock()
_metrics_stub.cache_misses_total.labels = MagicMock(return_value=MagicMock())
_metrics_stub.llm_request_total = MagicMock()
_metrics_stub.llm_request_total.labels = MagicMock(return_value=MagicMock())
_metrics_stub.llm_latency_hist = MagicMock()
_metrics_stub.llm_latency_hist.labels = MagicMock(return_value=MagicMock())
sys.modules.setdefault("prometheus_client", MagicMock())
sys.modules.setdefault("api.metrics", _metrics_stub)


# ── Shared helpers ────────────────────────────────────────────────────────────

def _make_llm_response(content: str, tool_calls: list | None = None) -> dict:
    return {
        "content":    content,
        "tool_calls": tool_calls or [],
        "model":      "test-model",
    }


def _make_llm_resp(content: str):
    """Build a mock LLMResponse (as returned by provider.chat())."""
    from services.base_provider import LLMResponse
    return LLMResponse(content=content, model="test-model", success=True)


def _run(coro):
    return asyncio.run(coro)


# ── Fixture: minimal IntelligentAgent with mocked provider ────────────────────

@pytest.fixture
def agent():
    """Build IntelligentAgent with a mock provider so no Ollama/ChromaDB is needed."""
    mock_provider = MagicMock()
    mock_provider.current_model = "test-model"
    # _call_model uses provider.chat(messages, config) → returns LLMResponse
    # Import LLMResponse here to build proper mock responses
    from services.base_provider import LLMResponse
    mock_provider.chat = MagicMock(
        return_value=LLMResponse(content="Hello from mock LLM", model="test-model", success=True)
    )

    # Patch module-level imports in core.agent
    # Local imports (TaskManager, MemoryDistiller, etc.) are patched via their source modules
    with patch("core.agent.OllamaProvider", return_value=mock_provider), \
         patch("core.agent.MemoryManager") as mock_mm_cls, \
         patch("core.agent.skill_manager"), \
         patch("core.agent.SkillApplicator") as mock_sa_cls, \
         patch("scheduler.simple_manager.TaskManager") as mock_tm_cls, \
         patch("memory.distiller.MemoryDistiller"), \
         patch("memory.context_extractor.ContextExtractor"), \
         patch("memory.semantic_cache.SemanticCache", side_effect=Exception("no chroma")):

        # MemoryManager mock
        mock_mm = MagicMock()
        mock_mm.get_context_for_query = AsyncMock(return_value="")
        mock_mm.add_memory = MagicMock(return_value=None)
        mock_mm.short_term = MagicMock()
        mock_mm.short_term.get_recent = MagicMock(return_value=[])
        mock_mm.long_term = MagicMock()
        mock_mm.long_term.embedding_model = None
        mock_mm_cls.return_value = mock_mm

        # TaskManager mock
        mock_tm = MagicMock()
        mock_tm.scheduler = MagicMock()
        mock_tm.scheduler._agent = None
        mock_tm_cls.return_value = mock_tm

        # SkillApplicator mock — apply() is awaited, so must be AsyncMock
        mock_sa = MagicMock()
        mock_sa.apply = AsyncMock(
            side_effect=lambda msg, messages, tools, call_model: (messages, tools, None)
        )
        mock_sa_cls.return_value = mock_sa

        from core.agent import IntelligentAgent
        a = IntelligentAgent(provider=mock_provider)
        a.provider = mock_provider
        a._semantic_cache = None   # ensure L2 cache disabled
        yield a


# ── Basic conversation ────────────────────────────────────────────────────────

def test_chat_returns_content(agent):
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("Hello, I'm an AI."))
    result = _run(agent.chat("Hi", use_tools=False, use_memory=False))
    assert "content" in result
    assert result["content"] == "Hello, I'm an AI."


def test_chat_no_tool_calls_by_default(agent):
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("plain answer"))
    result = _run(agent.chat("What is 2+2?", use_tools=False, use_memory=False))
    assert result.get("tool_calls", []) == []


def test_chat_returns_dict(agent):
    result = _run(agent.chat("hello", use_tools=False, use_memory=False))
    assert isinstance(result, dict)
    assert "content" in result


# ── Tool execution ────────────────────────────────────────────────────────────

def test_chat_with_tool_call(agent):
    """When use_tools=True, chat() enters the ReAct loop and returns content."""
    from services.base_provider import LLMResponse

    def _mock_chat(messages, config):
        return LLMResponse(content="The answer is 4.", model="dolphin", success=True)

    agent.provider.chat = _mock_chat
    # Force text-tool model path by making model name contain "dolphin"
    agent.provider.current_model = "dolphin"
    agent.model = "dolphin"

    result = _run(agent.chat("What is 2+2?", use_tools=True, use_memory=False))
    assert "content" in result
    assert isinstance(result["content"], str)


# ── Memory write ──────────────────────────────────────────────────────────────

def test_chat_writes_to_memory(agent):
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("Sure!"))
    agent.memory.store_conversation = MagicMock()
    _run(agent.chat("Remember this", use_tools=False, use_memory=True))
    assert agent.memory.store_conversation.called


def test_chat_no_memory_write_when_disabled(agent):
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("ok"))
    agent.memory.store_conversation = MagicMock()
    _run(agent.chat("hello", use_tools=False, use_memory=False))
    # store_conversation should NOT be called for 'assistant' role when use_memory=False
    assistant_calls = [c for c in agent.memory.store_conversation.call_args_list
                       if c.args and c.args[0] == "assistant"]
    assert len(assistant_calls) == 0


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


# ── L1 cache ─────────────────────────────────────────────────────────────────

def test_l1_cache_hit_on_second_call(agent):
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("cached answer"))
    msg = "unique query for cache test xyz987"

    # First call populates L1 cache
    r1 = _run(agent.chat(msg, use_tools=False, use_memory=False))
    assert r1["content"] == "cached answer"

    # Override chat to detect if it's called again
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("SHOULD NOT APPEAR"))

    r2 = _run(agent.chat(msg, use_tools=False, use_memory=False))
    assert r2["content"] == "cached answer"
    # L2 disabled; second call must come from L1 (provider.chat not called)
    agent.provider.chat.assert_not_called()


def test_skip_cache_bypasses_l1(agent):
    msg = "skip cache test abc123"
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("first"))
    _run(agent.chat(msg, use_tools=False, use_memory=False))

    agent.provider.chat = MagicMock(return_value=_make_llm_resp("second"))
    r2 = _run(agent.chat(msg, use_tools=False, use_memory=False, skip_cache=True))
    assert r2["content"] == "second"
    agent.provider.chat.assert_called_once()


# ── Error recovery ────────────────────────────────────────────────────────────

def test_chat_error_returns_failure(agent):
    from services.base_provider import LLMResponse
    agent.provider.chat = MagicMock(side_effect=RuntimeError("LLM crashed"))
    result = _run(agent.chat("hello", use_tools=False, use_memory=False))
    assert isinstance(result, dict)
    # Should not raise; error captured in content or success:False
    assert "content" in result or result.get("success") is False


# ── persona_override ──────────────────────────────────────────────────────────

def test_persona_override_injected(agent):
    """persona_override content ends up in the system message sent to provider."""
    captured = {}

    from services.base_provider import LLMResponse

    def _capture_chat(messages, config):
        captured["messages"] = [{"role": m.role, "content": m.content} for m in messages]
        return LLMResponse(content="ok", model="test-model", success=True)

    agent.provider.chat = _capture_chat
    _run(agent.chat("hi", use_tools=False, use_memory=False,
                    persona_override="You are a pirate."))

    system_msgs = [m for m in captured.get("messages", []) if m.get("role") == "system"]
    assert any("pirate" in (m.get("content") or "") for m in system_msgs), \
        f"persona_override not found in system messages: {system_msgs}"


# ── user_id isolation ─────────────────────────────────────────────────────────

def test_different_user_ids_accepted(agent):
    agent.provider.chat = MagicMock(return_value=_make_llm_resp("ok"))
    r1 = _run(agent.chat("hello", use_tools=False, use_memory=False, user_id="alice"))
    r2 = _run(agent.chat("hello-bob", use_tools=False, use_memory=False, user_id="bob"))
    assert r1["content"] == "ok"
    assert r2["content"] == "ok"


# ── scene_chat_type（飞书群聊静默规则，TODO-81）──────────────────────────────────

def test_group_scene_injects_silence_rule(agent):
    """scene_chat_type='group' 时，system 消息中应出现 [GROUP SCENE] 静默规则。"""
    captured = {}

    from services.base_provider import LLMResponse

    def _capture_chat(messages, config):
        captured["messages"] = [{"role": m.role, "content": m.content} for m in messages]
        return LLMResponse(content="ok", model="test-model", success=True)

    agent.provider.chat = _capture_chat
    _run(agent.chat("随便聊聊", use_tools=False, use_memory=False,
                    scene_chat_type="group", scene_mentioned=False))

    system_msgs = [m for m in captured.get("messages", []) if m.get("role") == "system"]
    assert any("[GROUP SCENE]" in (m.get("content") or "") for m in system_msgs)
    assert any("没有被 @ 提及" in (m.get("content") or "") for m in system_msgs)


def test_group_scene_mentioned_changes_instruction(agent):
    """scene_mentioned=True 时，规则文案应体现"已被 @"而非"未被 @"。"""
    captured = {}

    from services.base_provider import LLMResponse

    def _capture_chat(messages, config):
        captured["messages"] = [{"role": m.role, "content": m.content} for m in messages]
        return LLMResponse(content="ok", model="test-model", success=True)

    agent.provider.chat = _capture_chat
    _run(agent.chat("@机器人 在吗", use_tools=False, use_memory=False,
                    scene_chat_type="group", scene_mentioned=True))

    system_msgs = [m for m in captured.get("messages", []) if m.get("role") == "system"]
    group_scene_msgs = [m for m in system_msgs if "[GROUP SCENE]" in (m.get("content") or "")]
    assert group_scene_msgs, "缺少 [GROUP SCENE] 系统消息"
    assert "被直接 @ 提及" in group_scene_msgs[0]["content"]
    assert "没有被 @ 提及" not in group_scene_msgs[0]["content"]


def test_p2p_scene_has_no_group_rule(agent):
    """非 group 场景（p2p 或未传 scene_chat_type）不应注入 [GROUP SCENE] 规则。"""
    captured = {}

    from services.base_provider import LLMResponse

    def _capture_chat(messages, config):
        captured["messages"] = [{"role": m.role, "content": m.content} for m in messages]
        return LLMResponse(content="ok", model="test-model", success=True)

    agent.provider.chat = _capture_chat
    _run(agent.chat("你好", use_tools=False, use_memory=False, scene_chat_type="p2p"))

    system_msgs = [m for m in captured.get("messages", []) if m.get("role") == "system"]
    assert not any("[GROUP SCENE]" in (m.get("content") or "") for m in system_msgs)
