"""测试 soul/MEMORY.md 记忆归并的节流/备份/原子写逻辑（TODO-83）。"""
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock

import pytest

from scheduler.simple_scheduler import SimpleTaskScheduler


@pytest.fixture
def tmp_tasks_file(tmp_path):
    return tmp_path / "tasks.json"


def make_scheduler(tasks_file, memory_md_path):
    sched = SimpleTaskScheduler(check_interval=60, tasks_file=tasks_file)
    sched._memory_md_path = memory_md_path
    return sched


def make_agent(chat_content: str):
    agent = MagicMock()
    agent.chat = AsyncMock(return_value={"content": chat_content})
    return agent


@pytest.mark.asyncio
async def test_no_existing_file_triggers_llm(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    result = await sched._consolidate_memory(agent)

    assert result["ran"] is True
    agent.chat.assert_called_once()
    _, kwargs = agent.chat.call_args
    assert kwargs["allowed_tool_categories"] == ["file"]
    assert kwargs["use_tools"] is True
    assert "last_consolidated:" in mem_path.read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_recent_timestamp_throttles_without_calling_llm(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    recent = datetime.now() - timedelta(hours=1)
    mem_path.write_text(
        f"<!-- last_consolidated: {recent.isoformat()} -->\n# 精选记忆\n", encoding="utf-8"
    )
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    result = await sched._consolidate_memory(agent)

    assert result == {"ran": False, "reason": "throttled"}
    agent.chat.assert_not_called()


@pytest.mark.asyncio
async def test_stale_timestamp_triggers_llm_and_updates_timestamp(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    stale = datetime.now() - timedelta(hours=25)
    mem_path.write_text(
        f"<!-- last_consolidated: {stale.isoformat()} -->\n# 精选记忆\n", encoding="utf-8"
    )
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    result = await sched._consolidate_memory(agent)

    assert result["ran"] is True
    agent.chat.assert_called_once()
    new_text = mem_path.read_text(encoding="utf-8")
    assert stale.isoformat() not in new_text
    assert "# 精选记忆" in new_text


@pytest.mark.asyncio
async def test_consolidation_backs_up_existing_file_before_llm_call(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    mem_path.write_text("# 原始内容\n", encoding="utf-8")
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    await sched._consolidate_memory(agent)

    backup_path = mem_path.with_name("MEMORY.md.bak.1")
    assert backup_path.exists()
    assert "# 原始内容" in backup_path.read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_timestamp_written_even_when_llm_call_fails(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    mem_path.write_text("# 精选记忆\n", encoding="utf-8")
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = MagicMock()
    agent.chat = AsyncMock(side_effect=RuntimeError("模型挂了"))

    result = await sched._consolidate_memory(agent)

    assert result["ran"] is True
    assert "last_consolidated:" in mem_path.read_text(encoding="utf-8")
