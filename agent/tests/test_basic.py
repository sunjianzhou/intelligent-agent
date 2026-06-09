"""Basic agent initialisation tests (no Ollama required)."""
import sys
import os
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config.settings import settings
from core.agent import IntelligentAgent


@pytest.fixture
def agent():
    return IntelligentAgent()


def test_settings_defaults():
    assert settings.api_port == 8000
    assert settings.ollama_max_tokens > 0
    assert settings.memory_distill_interval > 0
    assert settings.memory_summary_interval > 0


def test_agent_initialization(agent):
    assert agent is not None
    # model may be overridden by cloud config; just verify it's a non-empty string
    assert isinstance(agent.model, str) and len(agent.model) > 0


def test_agent_has_memory(agent):
    info = agent.get_memory_info()
    assert "short_term" in info
    assert "long_term" in info


def test_agent_has_tools(agent):
    # list_tools() returns a formatted string; just verify tools are registered
    assert len(agent.tool_manager.tools) > 0


def test_agent_has_distiller(agent):
    """9.3 — MemoryDistiller is wired up."""
    from memory.distiller import MemoryDistiller
    assert isinstance(agent._distiller, MemoryDistiller)


def test_agent_has_summary_counter(agent):
    """9.4 — per-user summary turn counter exists."""
    assert hasattr(agent, "_summary_turn_counts")
    assert isinstance(agent._summary_turn_counts, dict)


def test_agent_provider_ctx():
    """9.1 — module-level ContextVar for per-user provider isolation exists."""
    import core.agent as agent_module
    import contextvars
    assert hasattr(agent_module, "_request_provider_ctx")
    assert isinstance(agent_module._request_provider_ctx, contextvars.ContextVar)


def test_agent_persona_ctx():
    """9.2 — module-level ContextVar for per-user persona isolation exists."""
    import core.agent as agent_module
    import contextvars
    assert hasattr(agent_module, "_request_persona_ctx")
    assert isinstance(agent_module._request_persona_ctx, contextvars.ContextVar)
