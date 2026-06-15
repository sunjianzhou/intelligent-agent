"""Tests for SystemPromptBuilder — 9 cases covering section order, whisper, persona, HEARTBEAT."""
import os
import sys
import pytest
from unittest.mock import MagicMock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from soul.loader import SoulData


def _make_soul(soul="灵魂内容", user="用户画像", memory="精选记忆",
               identity="霖君身份", heartbeat="自检铁规内容", whisper=""):
    loader = MagicMock()
    loader.data = SoulData(
        soul=soul, user=user, memory=memory,
        identity=identity, heartbeat=heartbeat, whisper=whisper,
    )
    return loader


def test_no_role_no_whisper_contains_all_soul_sections():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul())
    assert "【灵魂核心】" in result
    assert "【身份】" in result
    assert "【用户画像】" in result
    assert "【精选记忆】" in result
    assert "【自检铁规】" in result
    assert "【私密档案】" not in result


def test_whisper_nonempty_appears_in_prompt():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(whisper="私密内容"))
    assert "【私密档案】" in result
    assert "私密内容" in result


def test_persona_md_role_ctx_injected():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(), role_ctx={"persona_md": "角色描述内容"})
    assert "角色描述内容" in result


def test_empty_tool_overlay_not_appended():
    from core.system_prompt_builder import SystemPromptBuilder
    result_with = SystemPromptBuilder().build(_make_soul(), tool_overlay="工具规则")
    result_without = SystemPromptBuilder().build(_make_soul(), tool_overlay="")
    assert "工具规则" in result_with
    assert "工具规则" not in result_without


def test_soul_data_none_raises_runtime_error():
    from core.system_prompt_builder import SystemPromptBuilder
    loader = MagicMock()
    loader.data = None
    with pytest.raises(RuntimeError, match="soul not loaded"):
        SystemPromptBuilder().build(loader)


def test_soul_before_user_in_prompt():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul())
    assert result.index("【灵魂核心】") < result.index("【用户画像】")


def test_sections_separated_by_divider():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul())
    assert "─" * 60 in result


def test_logger_debug_called_with_char_count(monkeypatch):
    import core.system_prompt_builder as spb_module
    from core.system_prompt_builder import SystemPromptBuilder
    mock_log = MagicMock()
    monkeypatch.setattr(spb_module, "logger", mock_log)
    result = SystemPromptBuilder().build(_make_soul())
    mock_log.debug.assert_called_once()
    args = mock_log.debug.call_args.args
    assert "chars" in args[0]
    assert args[1] == len(result)


def test_heartbeat_content_in_prompt():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(heartbeat="禁止编造数据"))
    assert "【自检铁规】" in result
    assert "禁止编造数据" in result
