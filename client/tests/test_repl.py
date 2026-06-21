"""Unit tests for client/repl.py (stream_response, non_stream_response, helpers)."""
import io
import sys
from unittest.mock import MagicMock, patch

import pytest

import repl as repl_mod
from repl import non_stream_response, requests_error, stream_response
from session import ChatSession


@pytest.fixture(autouse=True)
def disable_rich(monkeypatch):
    """Force plain-text mode so tests don't depend on Rich being installed."""
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)


def _make_client(stream_events=None, chat_return=None):
    client = MagicMock()
    if stream_events is not None:
        client.chat_stream.return_value = iter(stream_events)
    if chat_return is not None:
        client.chat.return_value = chat_return
    return client


def _make_session(tmp_path):
    return ChatSession(data_dir=str(tmp_path), save=False)


# ── stream_response ───────────────────────────────────────────────────────────

def test_stream_response_returns_tuple(tmp_path, capsys):
    client = _make_client(stream_events=[
        {"type": "token", "data": "Hello"},
        {"type": "done", "data": {}},
    ])
    session = _make_session(tmp_path)
    result = stream_response(client, session, "hi", True, True)
    assert isinstance(result, tuple)
    assert len(result) == 4
    text, tool_calls, user_id, assistant_id = result
    assert isinstance(text, str)
    assert isinstance(tool_calls, list)


def test_stream_response_accumulates_tokens(tmp_path, capsys):
    client = _make_client(stream_events=[
        {"type": "token", "data": "Hello"},
        {"type": "token", "data": " world"},
        {"type": "done", "data": {}},
    ])
    session = _make_session(tmp_path)
    text, _, _, _ = stream_response(client, session, "hi", True, True)
    assert text == "Hello world"


def test_stream_response_done_event_content(tmp_path, capsys):
    """When no tokens arrive, done event content is the fallback text."""
    client = _make_client(stream_events=[
        {"type": "done", "data": {"content": "fallback text"}},
    ])
    session = _make_session(tmp_path)
    text, _, _, _ = stream_response(client, session, "hi", True, True)
    assert text == "fallback text"


def test_stream_response_collects_tool_calls(tmp_path, capsys):
    tc = {"tool_name": "search", "result": "results", "success": True}
    client = _make_client(stream_events=[
        {"type": "tool_call", "data": tc},
        {"type": "tool_calls_done", "data": [tc]},
        {"type": "done", "data": {}},
    ])
    session = _make_session(tmp_path)
    _, tool_calls, _, _ = stream_response(client, session, "search", True, True)
    assert len(tool_calls) == 1
    assert tool_calls[0]["tool_name"] == "search"


def test_stream_response_error_event(tmp_path, capsys):
    client = _make_client(stream_events=[
        {"type": "error", "data": "something broke"},
    ])
    session = _make_session(tmp_path)
    text, _, _, _ = stream_response(client, session, "hi", True, True)
    out = capsys.readouterr().out
    assert "Error:" in out or text == ""


# ── non_stream_response ───────────────────────────────────────────────────────

def test_non_stream_response_parses_response(tmp_path, capsys):
    client = _make_client(chat_return={
        "response": "Hello!",
        "tool_calls": [],
    })
    session = _make_session(tmp_path)
    text, tool_calls, _, _ = non_stream_response(client, "hi", True, True)
    assert text == "Hello!"
    assert tool_calls == []


def test_non_stream_response_with_tool_calls(tmp_path, capsys):
    tc = [{"tool": "search", "result": "data", "success": True}]
    client = _make_client(chat_return={
        "response": "done",
        "tool_calls": tc,
    })
    session = _make_session(tmp_path)
    text, tool_calls, _, _ = non_stream_response(client, "search for something", True, True)
    assert tool_calls == tc
    out = capsys.readouterr().out
    assert "Tool calls" in out or "search" in out


# ── id capture (Task 16) ──────────────────────────────────────────────────────

def test_stream_response_returns_ids_from_done_event(tmp_path, capsys):
    client = _make_client(stream_events=[
        {"type": "token", "data": "Hi"},
        {"type": "done", "data": {
            "content": "Hi",
            "user_message_id": "mid-u",
            "assistant_message_id": "mid-a",
        }},
    ])
    session = _make_session(tmp_path)

    text, tool_calls, user_id, assistant_id = stream_response(
        client, session, "hello", True, True,
    )

    assert text == "Hi"
    assert user_id == "mid-u"
    assert assistant_id == "mid-a"


def test_stream_response_missing_ids_returns_none(tmp_path, capsys):
    client = _make_client(stream_events=[
        {"type": "token", "data": "Hi"},
        {"type": "done", "data": {"content": "Hi"}},
    ])
    session = _make_session(tmp_path)

    _, _, user_id, assistant_id = stream_response(
        client, session, "hello", True, True,
    )

    assert user_id is None
    assert assistant_id is None


def test_non_stream_response_returns_ids(tmp_path, capsys):
    client = _make_client(chat_return={
        "response": "ok", "tool_calls": [],
        "user_message_id": "mid-u2", "assistant_message_id": "mid-a2",
    })

    text, tool_calls, user_id, assistant_id = non_stream_response(
        client, "hello", True, True,
    )

    assert text == "ok"
    assert user_id == "mid-u2"
    assert assistant_id == "mid-a2"


# ── requests_error ────────────────────────────────────────────────────────────

def test_requests_error_returns_exception_class():
    exc_class = requests_error()
    assert isinstance(exc_class, type)
    assert issubclass(exc_class, BaseException)


def test_requests_error_is_requests_exception():
    import requests
    assert requests_error() is requests.RequestException
