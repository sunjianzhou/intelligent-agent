"""Unit tests for client/session.py (ChatSession)."""
import json
import time
from pathlib import Path

import pytest

from session import ChatSession


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def tmp_session(tmp_path):
    return ChatSession(data_dir=str(tmp_path), save=True)


@pytest.fixture
def no_save_session(tmp_path):
    return ChatSession(data_dir=str(tmp_path), save=False)


# ── Init ──────────────────────────────────────────────────────────────────────

def test_init_no_save(tmp_path):
    s = ChatSession(data_dir=str(tmp_path), save=False)
    assert s._save is False
    assert s._file is None
    assert s.messages == []
    assert s.persona == "default"


def test_init_save_creates_dir(tmp_path):
    target = tmp_path / "nested" / "sessions"
    s = ChatSession(data_dir=str(target), save=True)
    assert target.exists()
    assert s._file is not None
    assert s._file.parent == target


def test_init_session_id_unique():
    s1 = ChatSession(save=False)
    s2 = ChatSession(save=False)
    assert s1.session_id != s2.session_id


# ── add_user ─────────────────────────────────────────────────────────────────

def test_add_user_appends(no_save_session):
    no_save_session.add_user("hello")
    assert len(no_save_session.messages) == 1
    msg = no_save_session.messages[0]
    assert msg["role"] == "user"
    assert msg["content"] == "hello"
    assert "timestamp" in msg


def test_add_user_triggers_persist(tmp_session):
    tmp_session.add_user("ping")
    assert tmp_session._file.exists()
    data = json.loads(tmp_session._file.read_text(encoding="utf-8"))
    assert data["messages"][0]["content"] == "ping"


# ── add_assistant ─────────────────────────────────────────────────────────────

def test_add_assistant_no_tool_calls(no_save_session):
    no_save_session.add_assistant("pong")
    msg = no_save_session.messages[0]
    assert msg["role"] == "assistant"
    assert msg["content"] == "pong"
    assert "tool_calls" not in msg


def test_add_assistant_empty_tool_calls_excluded(no_save_session):
    no_save_session.add_assistant("pong", tool_calls=[])
    assert "tool_calls" not in no_save_session.messages[0]


def test_add_assistant_with_tool_calls(no_save_session):
    tc = [{"tool": "search", "result": "ok"}]
    no_save_session.add_assistant("pong", tool_calls=tc)
    assert no_save_session.messages[0]["tool_calls"] == tc


# ── add_system ────────────────────────────────────────────────────────────────

def test_add_system_appends(no_save_session):
    no_save_session.add_system("sys msg")
    assert no_save_session.messages[0]["role"] == "system"
    assert no_save_session.messages[0]["content"] == "sys msg"


def test_add_system_also_persists(tmp_session):
    tmp_session.add_system("sys msg")
    assert tmp_session._file.exists()
    data = json.loads(tmp_session._file.read_text(encoding="utf-8"))
    assert data["messages"][0]["role"] == "system"


# ── recent ────────────────────────────────────────────────────────────────────

def test_recent_returns_last_n(no_save_session):
    for i in range(15):
        no_save_session.messages.append({"role": "user", "content": str(i)})
    recent = no_save_session.recent(10)
    assert len(recent) == 10
    assert recent[-1]["content"] == "14"


def test_recent_fewer_than_n_returns_all(no_save_session):
    no_save_session.messages.append({"role": "user", "content": "only"})
    assert len(no_save_session.recent(10)) == 1


# ── clear ─────────────────────────────────────────────────────────────────────

def test_clear_resets_messages(tmp_session):
    tmp_session.add_user("hello")
    old_id = tmp_session.session_id
    tmp_session.clear()
    assert tmp_session.messages == []
    assert tmp_session.session_id != old_id


def test_clear_assigns_new_file(tmp_session):
    old_file = tmp_session._file
    tmp_session.clear()
    assert tmp_session._file != old_file


# ── persist ───────────────────────────────────────────────────────────────────

def test_persist_no_save_no_file(no_save_session):
    no_save_session.add_user("test")
    # no file should have been created in no-save mode
    assert no_save_session._file is None


def test_persist_writes_valid_json(tmp_session):
    tmp_session.model = "dolphin"
    tmp_session.persona = "assistant"
    tmp_session.add_user("question")
    data = json.loads(tmp_session._file.read_text(encoding="utf-8"))
    assert data["model"] == "dolphin"
    assert data["persona"] == "assistant"
    assert data["messages"][0]["content"] == "question"


# ── load ──────────────────────────────────────────────────────────────────────

def test_load_roundtrip(tmp_path):
    s = ChatSession(data_dir=str(tmp_path), save=True)
    s.model = "dolphin"
    s.persona = "coder"
    s.add_user("hi")
    s.add_assistant("hello back")
    path = str(s._file)

    loaded = ChatSession.load(path, data_dir=str(tmp_path))
    assert loaded.session_id == s.session_id
    assert loaded.model == "dolphin"
    assert loaded.persona == "coder"
    assert len(loaded.messages) == 2
    assert loaded.messages[0]["content"] == "hi"
    assert loaded.messages[1]["content"] == "hello back"


# ── list_saved ────────────────────────────────────────────────────────────────

def test_list_saved_returns_newest_first(tmp_path):
    s = ChatSession(data_dir=str(tmp_path), save=True)
    s.add_user("first")
    time.sleep(0.01)
    s2 = ChatSession(data_dir=str(tmp_path), save=True)
    s2.add_user("second")

    files = s.list_saved()
    assert len(files) == 2
    assert files[0].stat().st_mtime >= files[1].stat().st_mtime


def test_list_saved_nonexistent_dir(tmp_path):
    s = ChatSession(data_dir=str(tmp_path / "ghost"), save=False)
    assert s.list_saved() == []


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
