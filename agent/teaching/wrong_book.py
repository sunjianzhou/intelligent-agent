"""错题本：JSON 持久化，按 question_id 去重，支持已掌握标记。"""
import json
from datetime import datetime
from pathlib import Path
from typing import List, Optional

_BASE = Path(__file__).parent.parent / "data" / "memory"

_TOPIC_DIR = {
    "k8s": "k8s-learning",
    "k8s_review": "k8s-learning",
    "llm": "llm-learning",
    "agent": "agent-design",
}


def _path(topic: str) -> Path:
    dirname = _TOPIC_DIR.get(topic, f"{topic}-learning")
    return _BASE / dirname / "wrong_book.json"


def _load(topic: str) -> List[dict]:
    p = _path(topic)
    if not p.exists():
        return []
    data = json.loads(p.read_text(encoding="utf-8"))
    return data.get("wrong_records", [])


def _save(topic: str, records: List[dict]) -> None:
    p = _path(topic)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(
        json.dumps({"wrong_records": records}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def add(question_id: str, topic: str, user_answer: str, correct_answer: str) -> None:
    records = _load(topic)
    now = datetime.now().isoformat()
    for r in records:
        if r["question_id"] == question_id:
            r["last_wrong_time"] = now
            r["wrong_count"] = r.get("wrong_count", 1) + 1
            r["user_answer"] = user_answer
            _save(topic, records)
            return
    records.append({
        "question_id": question_id,
        "topic": topic,
        "user_answer": user_answer,
        "correct_answer": correct_answer,
        "wrong_time": now,
        "last_wrong_time": now,
        "wrong_count": 1,
        "resolved": False,
        "resolved_time": None,
    })
    _save(topic, records)


def resolve(question_id: str, topic: str) -> bool:
    records = _load(topic)
    for r in records:
        if r["question_id"] == question_id:
            r["resolved"] = True
            r["resolved_time"] = datetime.now().isoformat()
            _save(topic, records)
            return True
    return False


def list_records(topic: str, include_resolved: bool = False) -> List[dict]:
    records = _load(topic)
    if not include_resolved:
        records = [r for r in records if not r["resolved"]]
    return sorted(records, key=lambda r: r["last_wrong_time"], reverse=True)
