"""Tests for PromptManager.get_overlay()."""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from prompts.prompt_manager import prompt_manager


def test_get_overlay_returns_string():
    result = prompt_manager.get_overlay("")
    assert isinstance(result, str)


def test_get_overlay_default_contains_tool_call_format():
    result = prompt_manager.get_overlay("")
    assert "tool_call" in result


def test_get_overlay_dolphin_model():
    result = prompt_manager.get_overlay("dolphin")
    assert isinstance(result, str)
