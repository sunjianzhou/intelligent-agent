"""Skill 触发日志，独立于用户反馈，每次命中都记录"""
import json
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any, Optional
from loguru import logger


class SkillLogStore:

    def __init__(self, base_dir: str = "./data/skill_logs"):
        self.base_dir = Path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def _user_file(self, username: str) -> Path:
        user_dir = self.base_dir / username
        user_dir.mkdir(parents=True, exist_ok=True)
        return user_dir / "records.json"

    def _load(self, username: str) -> List[Dict]:
        f = self._user_file(username)
        if not f.exists():
            return []
        try:
            return json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            return []

    def _save(self, username: str, records: List[Dict]):
        f = self._user_file(username)
        f.write_text(
            json.dumps(records, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )

    def record(self, username: str, skill_name: str, message: str,
               steps_count: int = 0, tools: List[str] = None) -> Dict:
        records = self._load(username)
        entry = {
            "id":           f"sl_{datetime.now().strftime('%Y%m%d%H%M%S')}_{len(records)}",
            "skill_name":   skill_name,
            "message":      message[:100],
            "steps_count":  steps_count,
            "tools":        tools or [],
            "triggered_at": datetime.now().isoformat(),
            "username":     username,
        }
        records.append(entry)
        self._save(username, records)
        return entry

    def list_records(self, username: str, limit: int = 100,
                     skill_name: Optional[str] = None) -> List[Dict]:
        records = self._load(username)
        if skill_name:
            records = [r for r in records if r.get("skill_name") == skill_name]
        return sorted(
            records, key=lambda r: r.get("triggered_at", ""), reverse=True
        )[:limit]

    def get_stats(self, username: str) -> Dict[str, Any]:
        records = self._load(username)
        if not records:
            return {"total": 0, "by_skill": {}, "recent": []}

        by_skill: Dict[str, int] = {}
        for r in records:
            name = r.get("skill_name", "未知")
            by_skill[name] = by_skill.get(name, 0) + 1

        by_skill = dict(sorted(by_skill.items(), key=lambda x: x[1], reverse=True))

        return {
            "total":    len(records),
            "by_skill": by_skill,
            "recent":   records[:10],
        }


skill_log_store = SkillLogStore()