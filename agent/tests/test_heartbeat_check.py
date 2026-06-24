"""测试 heartbeat_check 调度动作（TODO-80）：心跳巡检 LLM 判定 + 静默/发送分支。"""
from unittest.mock import AsyncMock, MagicMock

import pytest

from scheduler.simple_scheduler import SimpleTaskScheduler


# ── Fixtures ─────────────────────────────────────────────────

@pytest.fixture
def tmp_tasks_file(tmp_path):
    return tmp_path / "tasks.json"


def make_scheduler(tasks_file):
    return SimpleTaskScheduler(check_interval=60, tasks_file=tasks_file)


def make_agent(chat_content: str):
    agent = MagicMock()
    agent.chat = AsyncMock(return_value={"content": chat_content})
    agent.memory.store = MagicMock()
    return agent


# ── 测试：安静时段直接跳过，不调用 LLM ────────────────────────

@pytest.mark.asyncio
async def test_quiet_hours_skips_without_calling_llm(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    sched._agent = make_agent("SPEAK: 不该被调用")

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        quiet_hour_start=0,
        quiet_hour_end=24,  # 覆盖全部小时，始终安静
    )

    assert result == {"success": True, "sent": False, "reason": "quiet_hours"}
    sched._agent.chat.assert_not_called()


# ── 测试：LLM 判定 SILENT 时不发送 ─────────────────────────────

@pytest.mark.asyncio
async def test_silent_decision_does_not_send(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    sched._agent = make_agent("SILENT")
    im_tool = MagicMock()
    sched._tool_manager = MagicMock()
    sched._tool_manager.get_tool.return_value = im_tool

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        quiet_hour_start=5, quiet_hour_end=5,  # 永不安静
    )

    assert result == {"success": True, "sent": False, "reason": "silent"}
    im_tool.assert_not_called()


# ── 测试：LLM 判定 SPEAK 时通过 im_message 发送 ─────────────────

@pytest.mark.asyncio
async def test_speak_decision_sends_via_im_message(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    sched._agent = make_agent("SPEAK: 好久没聊了，最近还好吗？")
    im_tool = MagicMock()
    sched._tool_manager = MagicMock()
    sched._tool_manager.get_tool.return_value = im_tool

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        receive_id_type="open_id",
        quiet_hour_start=5, quiet_hour_end=5,
    )

    assert result["success"] is True
    assert result["sent"] is True
    assert result["message"] == "好久没聊了，最近还好吗？"
    im_tool.assert_called_once_with(
        receiver_id="ou_123",
        msg_type="text",
        content={"text": "好久没聊了，最近还好吗？"},
        receive_id_type="open_id",
    )
    sched._agent.memory.store.assert_called_once()

    # 判定调用必须走 feishu_im 渠道，保证生成内容本身就是 IM 克制风格
    _, kwargs = sched._agent.chat.call_args
    assert kwargs["channel"] == "feishu_im"


# ── 测试：im_message 工具未注册时安全失败，不抛异常 ─────────────

@pytest.mark.asyncio
async def test_speak_decision_without_im_tool_registered(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    sched._agent = make_agent("SPEAK: 消息内容")
    sched._tool_manager = MagicMock()
    sched._tool_manager.get_tool.return_value = None

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        quiet_hour_start=5, quiet_hour_end=5,
    )

    assert result["success"] is False
    assert "im_message" in result["error"]


# ── 测试：im_message 工具内部失败（ToolResult.success=False）时正确识别 ────────

@pytest.mark.asyncio
async def test_im_tool_failure_result_is_detected(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    sched._agent = make_agent("SPEAK: 消息内容")
    im_tool = MagicMock()
    # 模拟 BaseTool.__call__ 吞掉异常后返回的失败 ToolResult（不会抛异常）
    im_tool.return_value = MagicMock(success=False, error="网络超时")
    sched._tool_manager = MagicMock()
    sched._tool_manager.get_tool.return_value = im_tool

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        quiet_hour_start=5, quiet_hour_end=5,
    )

    assert result["success"] is False
    assert "网络超时" in result["error"]


# ── 测试：agent 未初始化时安全失败 ──────────────────────────────

@pytest.mark.asyncio
async def test_no_agent_returns_error(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    sched._agent = None

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        quiet_hour_start=5, quiet_hour_end=5,
    )

    assert result == {"success": False, "error": "agent not initialized"}
