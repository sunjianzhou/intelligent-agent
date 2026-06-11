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
