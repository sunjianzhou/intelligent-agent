"""HTTP client for the Python Agent REST API."""
import json
import logging
import time
from datetime import datetime, timezone, timedelta
from typing import Generator, Optional

_log = logging.getLogger(__name__)

import requests

try:
    import jwt as pyjwt
    _HAS_JWT = True
except ImportError:
    _HAS_JWT = False


class AgentClient:
    """Thin HTTP client for agent/api/fastapi_app.py endpoints."""

    def __init__(self, url: str, jwt_secret: str, user_id: str, timeout: int = 300):
        self.base_url = url.rstrip("/")
        self._jwt_secret = jwt_secret
        self._user_id = user_id
        self._timeout = timeout
        self._token: Optional[str] = None
        self._token_exp: float = 0.0

    # ── Auth ─────────────────────────────────────────────────────────
    def _get_token(self) -> str:
        if not _HAS_JWT:
            raise RuntimeError(
                "PyJWT is required: pip install PyJWT"
            )
        if self._token and time.time() < self._token_exp - 60:
            return self._token
        exp = datetime.now(tz=timezone.utc) + timedelta(hours=24)
        payload = {"sub": self._user_id, "exp": exp}
        self._token = pyjwt.encode(payload, self._jwt_secret, algorithm="HS256")
        self._token_exp = exp.timestamp()
        return self._token

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self._get_token()}",
            "Content-Type": "application/json",
        }

    # ── Health ───────────────────────────────────────────────────────
    def health(self) -> dict:
        try:
            r = requests.get(f"{self.base_url}/health", timeout=5)
            return r.json()
        except Exception as e:
            return {"status": "unreachable", "error": str(e)}

    # ── Models ───────────────────────────────────────────────────────
    def get_models(self) -> dict:
        r = requests.get(
            f"{self.base_url}/api/models",
            headers=self._headers(),
            timeout=10,
        )
        r.raise_for_status()
        return r.json()

    def switch_model(self, model_name: str) -> dict:
        r = requests.post(
            f"{self.base_url}/api/model/switch",
            headers=self._headers(),
            json={"model": model_name},
            timeout=15,
        )
        r.raise_for_status()
        return r.json()

    # ── Personas ─────────────────────────────────────────────────────
    def get_personas(self) -> dict:
        r = requests.get(
            f"{self.base_url}/api/personas",
            headers=self._headers(),
            timeout=10,
        )
        r.raise_for_status()
        return r.json()

    def get_current_persona(self) -> dict:
        r = requests.get(
            f"{self.base_url}/api/personas/current",
            headers=self._headers(),
            timeout=10,
        )
        r.raise_for_status()
        return r.json()

    def switch_persona(self, persona_name: str) -> dict:
        r = requests.post(
            f"{self.base_url}/api/personas/switch",
            headers=self._headers(),
            json={"persona": persona_name},
            timeout=10,
        )
        r.raise_for_status()
        return r.json()

    # ── Chat ─────────────────────────────────────────────────────────
    def chat(self, message: str, use_tools: bool = True, use_memory: bool = True) -> dict:
        """Non-streaming chat. Returns full response dict."""
        r = requests.post(
            f"{self.base_url}/api/chat",
            headers=self._headers(),
            json={"message": message, "use_tools": use_tools, "use_memory": use_memory},
            timeout=self._timeout,
        )
        r.raise_for_status()
        return r.json()

    def chat_stream(
        self,
        message: str,
        use_tools: bool = True,
        use_memory: bool = True,
    ) -> Generator[dict, None, None]:
        """Streaming chat via SSE. Yields parsed event dicts.

        Each event: {"type": str, "data": any}
        Known types: "token", "tool_call_start", "tool_call", "tool_calls_done", "done", "error"
        """
        with requests.post(
            f"{self.base_url}/api/chat/stream",
            headers=self._headers(),
            json={"message": message, "use_tools": use_tools, "use_memory": use_memory},
            stream=True,
            timeout=self._timeout,
        ) as resp:
            resp.raise_for_status()
            for raw_line in resp.iter_lines():
                if not raw_line:
                    continue
                line = raw_line.decode("utf-8") if isinstance(raw_line, bytes) else raw_line
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if not payload:
                    continue
                try:
                    yield json.loads(payload)
                except json.JSONDecodeError:
                    _log.debug("Skipping malformed SSE payload: %r", payload)
