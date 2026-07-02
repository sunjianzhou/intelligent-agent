"""Tests for branch failure detection — 5 signals + 2 integration + 1 boundary = 8 cases."""
import os
import sys
import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from typing import List, Dict, Any

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from core.conversation_flow import (
    _text_jaccard_similarity,
    _BRANCH_FAILURE_WINDOW,
    _RETRACT_ROUNDS_ON_FAILURE,
    ConversationFlowMixin,
)


# ---------------------------------------------------------------------------
# Jaccard similarity unit tests (used by signal 2)
# ---------------------------------------------------------------------------

def test_jaccard_identical_strings():
    assert _text_jaccard_similarity("a b c", "a b c") == 1.0


def test_jaccard_completely_different():
    assert _text_jaccard_similarity("a b c", "x y z") == 0.0


def test_jaccard_partial_overlap():
    sim = _text_jaccard_similarity("a b c d", "a b c e")
    # intersection = {a,b,c} = 3, union = {a,b,c,d,e} = 5 → 0.6
    assert 0.5 < sim < 0.7


def test_jaccard_empty_input():
    assert _text_jaccard_similarity("", "anything") == 0.0
    assert _text_jaccard_similarity("anything", "") == 0.0
    assert _text_jaccard_similarity("", "") == 0.0


# ---------------------------------------------------------------------------
# Fixture: create a ConversationFlowMixin instance with minimal mocking
# ---------------------------------------------------------------------------

@pytest.fixture
def mixin():
    """返回一个可用于测试检测方法的 ConversationFlowMixin 实例。"""
    m = ConversationFlowMixin()
    # Mock 必要的属性
    m.memory = MagicMock()
    m.memory.short_term = MagicMock()
    m.memory.short_term.delete_by_ids = MagicMock()
    return m


def _make_round(assistant_text="", tool_results=None,
                has_runtime_error=False, has_empty_response=False):
    return {
        "assistant_text": assistant_text,
        "tool_results": tool_results or [],
        "has_runtime_error": has_runtime_error,
        "has_empty_response": has_empty_response,
    }


# ---------------------------------------------------------------------------
# Signal 1: same tool, same error 3 times
# ---------------------------------------------------------------------------

def test_signal_1_same_tool_same_error_triggers(mixin):
    """同工具同错误 ≥3 次 → 检测到失败。"""
    history = []
    for _ in range(3):
        history.append(_make_round(tool_results=[
            {"tool": "web_search", "success": False, "result": "timeout error"}
        ]))
    reason = mixin._detect_branch_failure(history, 3, 5)
    assert reason is not None
    assert "same_tool_same_error" in reason


def test_signal_1_below_threshold_does_not_trigger(mixin):
    """同工具同错误 <3 次 → 不触发。"""
    history = [
        _make_round(tool_results=[
            {"tool": "web_search", "success": False, "result": "timeout error"}
        ]),
        _make_round(tool_results=[
            {"tool": "web_search", "success": False, "result": "timeout error"}
        ]),
    ]
    reason = mixin._detect_branch_failure(history, 2, 5)
    assert reason is None


# ---------------------------------------------------------------------------
# Signal 2: LLM consecutive duplicate output >80% similarity
# ---------------------------------------------------------------------------

def test_signal_2_consecutive_duplicate_triggers(mixin):
    """连续 2 轮输出高相似 → 检测到失败。"""
    text_a = "I need to search for information about the weather today"
    text_b = "I need to search for information about the weather now"
    history = [
        _make_round(assistant_text=text_a),
        _make_round(assistant_text=text_b),
        _make_round(assistant_text=text_b),  # 第 3 轮也相似 → 连续 2 次
    ]
    reason = mixin._detect_branch_failure(history, 3, 5)
    assert reason is not None
    assert "consecutive_duplicate" in reason


def test_signal_2_diverse_output_does_not_trigger(mixin):
    """输出差异大 → 不触发。"""
    history = [
        _make_round(assistant_text="search for weather"),
        _make_round(assistant_text="calculate math result"),
        _make_round(assistant_text="read a file from disk"),
    ]
    reason = mixin._detect_branch_failure(history, 3, 5)
    assert reason is None


# ---------------------------------------------------------------------------
# Signal 3: user explicit correction
# ---------------------------------------------------------------------------

def test_signal_3_user_correction_triggers(mixin):
    """user_correction=True → 立即触发，无需窗口。"""
    history = [_make_round(assistant_text="some output")]
    reason = mixin._detect_branch_failure(history, 1, 5,
                                          user_correction=True)
    assert reason is not None
    assert "user_correction" in reason


def test_signal_3_no_correction_does_not_trigger(mixin):
    """user_correction=False → 不触发信号 3。"""
    history = [_make_round(assistant_text="some output")]
    reason = mixin._detect_branch_failure(history, 1, 5,
                                          user_correction=False)
    # 可能为 None（没有其他信号触发）或由其他信号触发，这里检查不是 user_correction
    if reason is not None:
        assert "user_correction" not in reason


# ---------------------------------------------------------------------------
# Signal 4: RuntimeError + empty response in the same window
# ---------------------------------------------------------------------------

def test_signal_4_runtime_error_and_empty_triggers(mixin):
    """窗口内同时存在 RuntimeError 和空响应 → 立即触发。"""
    history = [
        _make_round(has_runtime_error=True),
        _make_round(has_empty_response=True),
    ]
    reason = mixin._detect_branch_failure(history, 2, 5)
    assert reason is not None
    assert "runtime_error_and_empty" in reason


def test_signal_4_only_runtime_error_does_not_trigger(mixin):
    """仅 RuntimeError 无空响应 → 不触发信号 4。"""
    history = [_make_round(has_runtime_error=True)]
    reason = mixin._detect_branch_failure(history, 1, 5)
    if reason is not None:
        assert "runtime_error_and_empty" not in reason


# ---------------------------------------------------------------------------
# Signal 5: tool retry exhaustion
# ---------------------------------------------------------------------------

def test_signal_5_retry_exhausted_triggers(mixin):
    """工具重试耗尽标记 → 检测到失败。"""
    history = [_make_round(tool_results=[
        {"tool": "database_query", "success": False,
         "result": "connection refused",
         "_retry_exhausted": True}
    ])]
    reason = mixin._detect_branch_failure(history, 1, 5)
    assert reason is not None
    assert "tool_retry_exhausted" in reason


def test_signal_5_no_retry_exhaustion_does_not_trigger(mixin):
    """工具失败但未耗尽重试 → 不触发信号 5。"""
    history = [_make_round(tool_results=[
        {"tool": "database_query", "success": False,
         "result": "connection refused"}
    ])]
    reason = mixin._detect_branch_failure(history, 1, 5)
    if reason is not None:
        assert "tool_retry_exhausted" not in reason


# ---------------------------------------------------------------------------
# Integration: multiple signals don't clash
# ---------------------------------------------------------------------------

def test_integration_empty_history_returns_none(mixin):
    """空 history → 不触发任何信号。"""
    reason = mixin._detect_branch_failure([], 0, 5)
    assert reason is None


def test_integration_normal_rounds_dont_trigger(mixin):
    """正常的多轮对话（有成功工具调用，输出多样化）→ 不触发。"""
    history = [
        _make_round(
            assistant_text="Let me search for that",
            tool_results=[{"tool": "web_search", "success": True, "result": "found"}]
        ),
        _make_round(
            assistant_text="Here are the results you asked for",
            tool_results=[{"tool": "file_tool", "success": True, "result": "ok"}]
        ),
    ]
    reason = mixin._detect_branch_failure(history, 2, 5)
    assert reason is None


# ---------------------------------------------------------------------------
# Boundary: retract_on_failure=False (switch off)
# ---------------------------------------------------------------------------

def test_boundary_retract_off_skips_detection(mixin):
    """retract_on_failure=False 时不调用检测逻辑 — 此测试验证开关参数存在。"""
    # 验证 ConversationFlowMixin 的 chat/chats_tream 方法签名包含 retract_on_failure
    import inspect
    sig = inspect.signature(ConversationFlowMixin.chat)
    assert 'retract_on_failure' in sig.parameters
    assert sig.parameters['retract_on_failure'].default is True

    sig2 = inspect.signature(ConversationFlowMixin.chat_stream)
    assert 'retract_on_failure' in sig2.parameters
    assert sig2.parameters['retract_on_failure'].default is True


# ---------------------------------------------------------------------------
# _auto_retract_last_n_rounds unit test
# ---------------------------------------------------------------------------

def test_auto_retract_removes_assistant_and_tool_messages(mixin):
    """撤回操作应从 messages 中移除最近 N 轮 assistant+tool 消息。"""
    messages = [
        {"role": "system", "content": "sys"},
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "assistant round 1"},
        {"role": "tool", "content": "tool result 1"},
        {"role": "assistant", "content": "assistant round 2"},
        {"role": "tool", "content": "tool result 2"},
    ]
    removed = mixin._auto_retract_last_n_rounds(messages, 1, "test_user")
    assert removed >= 2  # at least assistant + tool for last round
    # system + user should remain
    roles = [m["role"] for m in messages]
    assert "system" in roles
    assert "user" in roles
    # last round's assistant should be removed
    assert "assistant round 2" not in [m["content"] for m in messages]
    assert "tool result 2" not in [m["content"] for m in messages]
