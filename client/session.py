"""Chat session state and datas/ persistence."""
from __future__ import annotations
import json
import uuid
from datetime import datetime
from pathlib import Path
from typing import Optional


class ChatSession:
    """Holds in-memory messages for one conversation session and auto-saves to datas/."""

    def __init__(self, data_dir: str = "./datas", save: bool = True):
        self.session_id: str = str(uuid.uuid4())[:8]
        self.started_at: str = datetime.now().isoformat()
        self.model: str = ""
        self.persona: str = "default"
        self.messages: list[dict] = []
        self._data_dir = Path(data_dir)
        self._save = save
        self._file: Optional[Path] = None

        if save:
            self._data_dir.mkdir(parents=True, exist_ok=True)
            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            self._file = self._data_dir / f"session_{ts}_{self.session_id}.json"

    def add_user(self, content: str) -> None:
        self.messages.append({
            "role": "user",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        })
        self._persist()

    def add_assistant(self, content: str, tool_calls: Optional[list] = None) -> None:
        entry = {
            "role": "assistant",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        }
        if tool_calls:
            entry["tool_calls"] = tool_calls
        self.messages.append(entry)
        self._persist()

    def add_system(self, content: str) -> None:
        self.messages.append({
            "role": "system",
            "content": content,
            "timestamp": datetime.now().isoformat(),
        })

    def clear(self) -> None:
        self.messages.clear()
        self.session_id = str(uuid.uuid4())[:8]
        self.started_at = datetime.now().isoformat()
        if self._save:
            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            self._file = self._data_dir / f"session_{ts}_{self.session_id}.json"

    def recent(self, n: int = 10) -> list[dict]:
        return self.messages[-n:]

    def _persist(self) -> None:
        if not self._save or self._file is None:
            return
        data = {
            "session_id": self.session_id,
            "started_at": self.started_at,
            "saved_at": datetime.now().isoformat(),
            "model": self.model,
            "persona": self.persona,
            "messages": self.messages,
        }
        try:
            self._file.write_text(
                json.dumps(data, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        except OSError:
            pass

    @classmethod
    def load(cls, path: str, data_dir: str = "./datas") -> "ChatSession":
        """Load a previous session from a JSON file."""
        p = Path(path)
        data = json.loads(p.read_text(encoding="utf-8"))
        s = cls(data_dir=data_dir, save=True)
        s.session_id = data.get("session_id", s.session_id)
        s.started_at = data.get("started_at", s.started_at)
        s.model = data.get("model", "")
        s.persona = data.get("persona", "default")
        s.messages = data.get("messages", [])
        return s

    def list_saved(self) -> list[Path]:
        """List session files sorted by modification time (newest first)."""
        if not self._data_dir.exists():
            return []
        return sorted(
            self._data_dir.glob("session_*.json"),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )
