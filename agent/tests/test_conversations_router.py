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
            self.memories[memory_id].metadata.update(metadata or {})


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
