"""
End-to-end tests for client/.

Strategy: spin up a real ThreadedHTTPServer that mimics the Python agent API,
then exercise AgentClient, ChatSession, and the REPL against live TCP sockets.
No agent / Ollama required.
"""
from __future__ import annotations

import io
import json
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import patch

import pytest

# Ensure client/ is on path
sys.path.insert(0, str(Path(__file__).parent.parent))

from api import AgentClient
from session import ChatSession

# ── Mock HTTP server ───────────────────────────────────────────────────────────

_SSE_STREAM = "\n".join([
    'data: {"type":"token","data":"Hello"}',
    'data: {"type":"token","data":" world"}',
    'data: {"type":"tool_call","data":{"tool_name":"search","result":"ok","success":true}}',
    'data: {"type":"tool_calls_done","data":[{"tool_name":"search","result":"ok","success":true}]}',
    'data: {"type":"done","data":{"content":"Hello world"}}',
    "",
])


class MockAgentHandler(BaseHTTPRequestHandler):
    """Realistic mock of agent/api/fastapi_app.py endpoints."""

    # Track received requests so tests can assert on them
    received: list[dict] = []

    def log_message(self, *args):
        pass  # suppress access log noise

    def _send(self, data: dict, status: int = 200, content_type: str = "application/json"):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_sse(self, body: str, status: int = 200):
        body_bytes = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body_bytes)))
        self.end_headers()
        self.wfile.write(body_bytes)

    def _read_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length:
            return json.loads(self.rfile.read(length))
        return {}

    def _record(self, method: str, body: dict = {}):
        MockAgentHandler.received.append({
            "method": method,
            "path": self.path,
            "auth": self.headers.get("Authorization", ""),
            "body": body,
        })

    def do_GET(self):
        self._record("GET")
        if self.path == "/health":
            self._send({"status": "connected", "model": "dolphin",
                         "service": "intelligent-agent", "version": "test"})
        elif self.path == "/api/models":
            self._send({"current_model": "dolphin",
                         "available_models": ["dolphin", "llama3", "mistral"]})
        elif self.path == "/api/personas":
            self._send({"personas": [
                {"name": "default", "title": "Default Assistant"},
                {"name": "coder",   "title": "Expert Coder"},
            ]})
        elif self.path == "/api/personas/current":
            self._send({"name": "default", "title": "Default Assistant"})
        else:
            self._send({"error": "not found"}, status=404)

    def do_POST(self):
        body = self._read_body()
        self._record("POST", body)
        if self.path == "/api/model/switch":
            model = body.get("model", "dolphin")
            self._send({"success": True, "current_model": model,
                         "message": f"Switched to {model}"})
        elif self.path == "/api/personas/switch":
            persona = body.get("persona", "default")
            self._send({"success": True, "persona": persona,
                         "message": f"Switched to {persona}"})
        elif self.path == "/api/chat":
            msg = body.get("message", "")
            self._send({"response": f"Echo: {msg}", "tool_calls": [],
                         "tokens_used": 42})
        elif self.path == "/api/chat/stream":
            self._send_sse(_SSE_STREAM)
        else:
            self._send({"error": "not found"}, status=404)


@pytest.fixture(scope="module")
def server_url():
    """Start mock server once per module; return base URL."""
    MockAgentHandler.received.clear()
    srv = HTTPServer(("127.0.0.1", 0), MockAgentHandler)
    port = srv.server_address[1]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{port}"
    srv.shutdown()


@pytest.fixture
def client(server_url):
    MockAgentHandler.received.clear()
    return AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)


# ── E2E: Auth header ──────────────────────────────────────────────────────────

def test_e2e_auth_header_sent(client):
    """Every authenticated request must include Authorization: Bearer <jwt>."""
    client.get_models()
    req = MockAgentHandler.received[-1]
    assert req["auth"].startswith("Bearer ")
    # JWT has 3 dot-separated segments
    token = req["auth"].split(" ", 1)[1]
    assert token.count(".") == 2


# ── E2E: Health ───────────────────────────────────────────────────────────────

def test_e2e_health_returns_connected(client):
    result = client.health()
    assert result["status"] == "connected"
    assert result["model"] == "dolphin"


def test_e2e_health_no_auth_header(client):
    """Health endpoint is public — client must NOT send auth."""
    client.health()
    req = [r for r in MockAgentHandler.received if r["path"] == "/health"][-1]
    # Health skips _headers(), so Authorization should be absent
    assert req["auth"] == ""


# ── E2E: Models ───────────────────────────────────────────────────────────────

def test_e2e_get_models(client):
    result = client.get_models()
    assert result["current_model"] == "dolphin"
    assert "llama3" in result["available_models"]
    assert len(result["available_models"]) == 3


def test_e2e_switch_model(client):
    result = client.switch_model("llama3")
    assert result["success"] is True
    assert result["current_model"] == "llama3"
    # Verify correct JSON body sent
    req = [r for r in MockAgentHandler.received if r["path"] == "/api/model/switch"][-1]
    assert req["body"]["model"] == "llama3"


# ── E2E: Personas ─────────────────────────────────────────────────────────────

def test_e2e_get_personas(client):
    result = client.get_personas()
    names = [p["name"] for p in result["personas"]]
    assert "default" in names
    assert "coder" in names


def test_e2e_get_current_persona(client):
    result = client.get_current_persona()
    assert result["name"] == "default"


def test_e2e_switch_persona(client):
    result = client.switch_persona("coder")
    assert result["success"] is True
    assert result["persona"] == "coder"
    req = [r for r in MockAgentHandler.received if r["path"] == "/api/personas/switch"][-1]
    assert req["body"]["persona"] == "coder"


# ── E2E: Chat (non-streaming) ─────────────────────────────────────────────────

def test_e2e_chat_echo(client):
    result = client.chat("hello e2e")
    assert result["response"] == "Echo: hello e2e"
    assert "tool_calls" in result


def test_e2e_chat_sends_all_flags(client):
    client.chat("test", use_tools=False, use_memory=False)
    req = [r for r in MockAgentHandler.received if r["path"] == "/api/chat"][-1]
    assert req["body"]["use_tools"] is False
    assert req["body"]["use_memory"] is False
    assert req["body"]["message"] == "test"


# ── E2E: Chat streaming (SSE) ─────────────────────────────────────────────────

def test_e2e_chat_stream_receives_token_events(client):
    events = list(client.chat_stream("stream test"))
    types = [e["type"] for e in events]
    assert "token" in types
    assert "done" in types


def test_e2e_chat_stream_tokens_concatenate(client):
    events = list(client.chat_stream("stream test"))
    tokens = "".join(e["data"] for e in events if e["type"] == "token")
    assert tokens == "Hello world"


def test_e2e_chat_stream_tool_call_event(client):
    events = list(client.chat_stream("use tools"))
    tool_events = [e for e in events if e["type"] == "tool_call"]
    assert len(tool_events) == 1
    assert tool_events[0]["data"]["tool_name"] == "search"


def test_e2e_chat_stream_done_event_last(client):
    events = list(client.chat_stream("stream test"))
    assert events[-1]["type"] == "done"


# ── E2E: Session persistence ──────────────────────────────────────────────────

def test_e2e_session_persists_to_disk(tmp_path):
    s = ChatSession(data_dir=str(tmp_path), save=True)
    s.model = "dolphin"
    s.persona = "coder"
    s.add_user("hi")
    s.add_assistant("hello", tool_calls=[{"tool": "search"}])

    assert s._file.exists()
    raw = json.loads(s._file.read_text(encoding="utf-8"))
    assert raw["model"] == "dolphin"
    assert raw["persona"] == "coder"
    assert len(raw["messages"]) == 2


def test_e2e_session_load_preserves_messages(tmp_path):
    s = ChatSession(data_dir=str(tmp_path), save=True)
    s.add_user("question one")
    s.add_user("question two")
    path = str(s._file)

    loaded = ChatSession.load(path, data_dir=str(tmp_path), save=False)
    assert len(loaded.messages) == 2
    assert loaded.messages[0]["content"] == "question one"
    assert loaded._save is False  # respects save=False


def test_e2e_session_clear_starts_fresh(tmp_path):
    s = ChatSession(data_dir=str(tmp_path), save=True)
    s.add_user("before clear")
    old_id = s.session_id
    old_file = s._file

    s.clear()
    s.add_user("after clear")

    # New file should be written
    assert s._file != old_file
    raw = json.loads(s._file.read_text(encoding="utf-8"))
    assert len(raw["messages"]) == 1
    assert raw["messages"][0]["content"] == "after clear"


def test_e2e_session_list_saved_finds_files(tmp_path):
    s1 = ChatSession(data_dir=str(tmp_path), save=True)
    s1.add_user("msg1")
    time.sleep(0.02)
    s2 = ChatSession(data_dir=str(tmp_path), save=True)
    s2.add_user("msg2")

    files = s1.list_saved()
    assert len(files) == 2
    # Newest first
    assert files[0].stat().st_mtime >= files[1].stat().st_mtime


# ── E2E: REPL command flow ────────────────────────────────────────────────────

import repl as repl_mod
from repl import run_repl


@pytest.fixture(autouse=False)
def plain_repl(monkeypatch):
    """Force plain-text REPL for all REPL tests."""
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)


def _run_repl_with_inputs(client, session, inputs: list[str], capsys) -> str:
    """Drive run_repl() by injecting lines then EOF."""
    input_iter = iter(inputs)

    def fake_input(_prompt=""):
        try:
            return next(input_iter)
        except StopIteration:
            raise EOFError

    with patch("builtins.input", side_effect=fake_input):
        run_repl(client, session, use_tools=True, use_memory=True, stream=False)

    return capsys.readouterr().out


def test_e2e_repl_help_command(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    out = _run_repl_with_inputs(client, session, ["!help"], capsys)
    assert "!models" in out
    assert "!persona" in out
    assert "!exit" in out


def test_e2e_repl_models_command(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    out = _run_repl_with_inputs(client, session, ["!models"], capsys)
    assert "dolphin" in out
    assert "llama3" in out


def test_e2e_repl_personas_command(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    out = _run_repl_with_inputs(client, session, ["!personas"], capsys)
    assert "default" in out
    assert "coder" in out


def test_e2e_repl_persona_switch(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    _run_repl_with_inputs(client, session, ["!persona coder"], capsys)
    # Server returns {"persona": "coder"} — session should reflect it
    assert session.persona == "coder"


def test_e2e_repl_model_switch(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    _run_repl_with_inputs(client, session, ["!model llama3"], capsys)
    assert session.model == "llama3"


def test_e2e_repl_chat_message(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    out = _run_repl_with_inputs(client, session, ["hello from e2e"], capsys)
    assert "Echo: hello from e2e" in out
    # Session should record user + assistant messages
    assert len(session.messages) == 2
    assert session.messages[0]["role"] == "user"
    assert session.messages[1]["role"] == "assistant"


def test_e2e_repl_history_command(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    # Send a message then check history
    out = _run_repl_with_inputs(client, session, ["hello", "!history"], capsys)
    assert "user" in out
    assert "hello" in out


def test_e2e_repl_clear_command(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    _run_repl_with_inputs(client, session, ["hello", "!clear"], capsys)
    assert session.messages == []


def test_e2e_repl_unknown_command(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    out = _run_repl_with_inputs(client, session, ["!nonexistent"], capsys)
    assert "Unknown command" in out or "unknown" in out.lower()


def test_e2e_repl_sessions_command(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    # Use save=True so files exist to list
    session = ChatSession(data_dir=str(tmp_path), save=True)
    session.add_user("saved message")
    out = _run_repl_with_inputs(client, session, ["!sessions"], capsys)
    # Should list at least one session file
    assert "session_" in out


def test_e2e_repl_empty_input_skipped(server_url, tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(repl_mod, "_RICH", False)
    monkeypatch.setattr(repl_mod, "console", None)
    client = AgentClient(server_url, "test-secret-32chars-for-testing!!", "e2e-user", timeout=10)
    session = ChatSession(data_dir=str(tmp_path), save=False)
    # Empty inputs should be skipped, not sent as messages
    _run_repl_with_inputs(client, session, ["", "  ", ""], capsys)
    assert session.messages == []


# ── E2E: main.py single-shot via subprocess ───────────────────────────────────

def test_e2e_main_single_shot_nonstream(server_url, tmp_path):
    """main.py with a message arg should print assistant response and exit."""
    result = subprocess.run(
        [
            sys.executable, str(Path(__file__).parent.parent / "main.py"),
            "hello subprocess",
            "--url", server_url,
            "--no-stream",
            "--no-save",
            "--no-memory",
        ],
        capture_output=True,
        text=True,
        timeout=15,
        cwd=str(Path(__file__).parent.parent),
    )
    assert result.returncode == 0
    assert "Echo: hello subprocess" in result.stdout


def test_e2e_main_model_flag(server_url, tmp_path):
    """--model flag should switch model before chatting."""
    result = subprocess.run(
        [
            sys.executable, str(Path(__file__).parent.parent / "main.py"),
            "test",
            "--url", server_url,
            "--model", "llama3",
            "--no-stream",
            "--no-save",
        ],
        capture_output=True,
        text=True,
        timeout=15,
        cwd=str(Path(__file__).parent.parent),
    )
    assert result.returncode == 0
    # Should show model switch confirmation
    assert "llama3" in result.stdout


def test_e2e_main_persona_flag(server_url, tmp_path):
    """--persona flag should switch persona before chatting."""
    result = subprocess.run(
        [
            sys.executable, str(Path(__file__).parent.parent / "main.py"),
            "test",
            "--url", server_url,
            "--persona", "coder",
            "--no-stream",
            "--no-save",
        ],
        capture_output=True,
        text=True,
        timeout=15,
        cwd=str(Path(__file__).parent.parent),
    )
    assert result.returncode == 0
    assert "coder" in result.stdout
