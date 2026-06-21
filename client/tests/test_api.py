"""Unit tests for client/api.py (AgentClient)."""
import json
import time
from unittest.mock import MagicMock, patch

import pytest
import responses as resp_mock

from api import AgentClient

BASE = "http://localhost:8000"
SECRET = "test-secret-32-chars-for-testing!!"
USER = "test-user"


@pytest.fixture
def client():
    return AgentClient(BASE, SECRET, USER, timeout=30)


# ── Init & URL normalisation ──────────────────────────────────────────────────

def test_base_url_strips_trailing_slash():
    c = AgentClient(BASE + "/", SECRET, USER)
    assert c.base_url == BASE


def test_timeout_stored():
    c = AgentClient(BASE, SECRET, USER, timeout=99)
    assert c._timeout == 99


# ── JWT / auth ────────────────────────────────────────────────────────────────

def test_get_token_returns_string(client):
    token = client._get_token()
    assert isinstance(token, str)
    assert len(token) > 20


def test_get_token_payload_has_sub(client):
    import jwt as pyjwt
    token = client._get_token()
    payload = pyjwt.decode(token, SECRET, algorithms=["HS256"])
    assert payload["sub"] == USER


def test_get_token_cached(client):
    t1 = client._get_token()
    t2 = client._get_token()
    assert t1 == t2


def test_get_token_refreshed_when_near_expiry(client):
    # Pre-set a placeholder token with near-expiry to confirm it gets replaced
    client._token = "old-placeholder-token"
    client._token_exp = time.time() + 30  # within 60s window → must refresh
    t2 = client._get_token()
    assert t2 != "old-placeholder-token"
    assert len(t2) > 30  # real JWT string


def test_headers_contain_bearer(client):
    headers = client._headers()
    assert headers["Authorization"].startswith("Bearer ")
    assert headers["Content-Type"] == "application/json"


# ── Health ────────────────────────────────────────────────────────────────────

@resp_mock.activate
def test_health_success(client):
    resp_mock.add(resp_mock.GET, f"{BASE}/health", json={"status": "connected", "model": "dolphin"})
    result = client.health()
    assert result["status"] == "connected"


@resp_mock.activate
def test_health_unreachable(client):
    resp_mock.add(resp_mock.GET, f"{BASE}/health", body=ConnectionError("refused"))
    result = client.health()
    assert result["status"] == "unreachable"
    assert "error" in result


# ── Models ────────────────────────────────────────────────────────────────────

@resp_mock.activate
def test_get_models(client):
    payload = {"current_model": "dolphin", "available_models": ["dolphin", "llama3"]}
    resp_mock.add(resp_mock.GET, f"{BASE}/api/models", json=payload)
    result = client.get_models()
    assert result["current_model"] == "dolphin"
    assert "llama3" in result["available_models"]


@resp_mock.activate
def test_switch_model_sends_correct_body(client):
    resp_mock.add(resp_mock.POST, f"{BASE}/api/model/switch",
                  json={"success": True, "current_model": "llama3"})
    result = client.switch_model("llama3")
    assert result["success"] is True
    sent_body = json.loads(resp_mock.calls[0].request.body)
    assert sent_body["model"] == "llama3"


# ── Personas ──────────────────────────────────────────────────────────────────

@resp_mock.activate
def test_get_personas(client):
    payload = {"personas": [{"name": "default", "title": "Default"}]}
    resp_mock.add(resp_mock.GET, f"{BASE}/api/personas", json=payload)
    result = client.get_personas()
    assert result["personas"][0]["name"] == "default"


@resp_mock.activate
def test_get_current_persona(client):
    resp_mock.add(resp_mock.GET, f"{BASE}/api/personas/current",
                  json={"name": "coder", "title": "Coder"})
    result = client.get_current_persona()
    assert result["name"] == "coder"


@resp_mock.activate
def test_switch_persona_sends_correct_body(client):
    resp_mock.add(resp_mock.POST, f"{BASE}/api/personas/switch",
                  json={"success": True, "persona": "coder"})
    result = client.switch_persona("coder")
    assert result["success"] is True
    sent_body = json.loads(resp_mock.calls[0].request.body)
    assert sent_body["persona"] == "coder"


# ── Conversations ────────────────────────────────────────────────────────────

@resp_mock.activate
def test_retract_messages_posts_to_correct_endpoint(client):
    resp_mock.add(resp_mock.POST, f"{BASE}/api/conversations/sess1/retract",
                  json={"success": True, "requested": 2, "deleted": 2, "deleted_ids": ["m1", "m2"], "memory_purged": 1})
    result = client.retract_messages("sess1", ["m1", "m2"])
    assert result["deleted"] == 2
    sent = json.loads(resp_mock.calls[0].request.body)
    assert sent["message_ids"] == ["m1", "m2"]


# ── Chat (non-streaming) ──────────────────────────────────────────────────────

@resp_mock.activate
def test_chat_returns_response(client):
    resp_mock.add(resp_mock.POST, f"{BASE}/api/chat",
                  json={"response": "Hello!", "tool_calls": []})
    result = client.chat("hi")
    assert result["response"] == "Hello!"
    sent = json.loads(resp_mock.calls[0].request.body)
    assert sent["message"] == "hi"
    assert sent["use_tools"] is True
    assert sent["use_memory"] is True


@resp_mock.activate
def test_chat_raises_on_http_error(client):
    resp_mock.add(resp_mock.POST, f"{BASE}/api/chat", status=500)
    with pytest.raises(Exception):
        client.chat("error")


# ── Chat (streaming / SSE) ────────────────────────────────────────────────────

def _make_stream_mock(lines: list):
    """Build a mock requests response that yields SSE lines."""
    mock_resp = MagicMock()
    mock_resp.raise_for_status.return_value = None
    mock_resp.iter_lines.return_value = [
        (line.encode() if isinstance(line, str) else line) for line in lines
    ]
    mock_resp.__enter__ = lambda s: mock_resp
    mock_resp.__exit__ = MagicMock(return_value=False)
    return mock_resp


@patch("api.requests.post")
def test_chat_stream_yields_events(mock_post, client):
    mock_post.return_value = _make_stream_mock([
        'data: {"type": "token", "data": "Hi"}',
        '',
        'data: {"type": "done", "data": {}}',
    ])
    events = list(client.chat_stream("hello"))
    assert events[0] == {"type": "token", "data": "Hi"}
    assert events[1] == {"type": "done", "data": {}}


@patch("api.requests.post")
def test_chat_stream_skips_non_data_lines(mock_post, client):
    mock_post.return_value = _make_stream_mock([
        'event: message',
        ': heartbeat',
        'data: {"type": "token", "data": "ok"}',
    ])
    events = list(client.chat_stream("hello"))
    assert len(events) == 1
    assert events[0]["data"] == "ok"


@patch("api.requests.post")
def test_chat_stream_skips_bad_json(mock_post, client):
    mock_post.return_value = _make_stream_mock([
        'data: {not valid json}',
        'data: {"type": "token", "data": "good"}',
    ])
    events = list(client.chat_stream("hello"))
    assert len(events) == 1
    assert events[0]["data"] == "good"


@patch("api.requests.post")
def test_chat_stream_skips_empty_data(mock_post, client):
    mock_post.return_value = _make_stream_mock([
        'data: ',
        'data:',
        'data: {"type": "token", "data": "x"}',
    ])
    events = list(client.chat_stream("hello"))
    assert len(events) == 1
