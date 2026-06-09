"""对话质量反馈存储"""
import json
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any, Optional
from loguru import logger


class FeedbackStore:

    def __init__(self, base_dir: str = "./data/feedback"):
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

    def add(self, username: str, record: Dict[str, Any]) -> Dict:
        records = self._load(username)
        record["id"]         = f"fb_{datetime.now().strftime('%Y%m%d%H%M%S')}_{len(records)}"
        record["created_at"] = datetime.now().isoformat()
        record["username"]   = username
        records.append(record)
        self._save(username, records)
        return record

    def list_records(self, username: str,
                     limit: int = 100,
                     rating: Optional[str] = None) -> List[Dict]:
        records = self._load(username)
        if rating:
            records = [r for r in records if r.get("rating") == rating]
        return sorted(records, key=lambda r: r.get("created_at", ""), reverse=True)[:limit]

    def get_stats(self, username: str) -> Dict[str, Any]:
        records = self._load(username)
        if not records:
            return {
                "total": 0, "likes": 0, "dislikes": 0,
                "like_rate": 0, "avg_response_time": 0,
                "tool_usage": {}, "skill_usage": {},
                "daily_counts": [], "response_time_trend": []
            }

        likes    = sum(1 for r in records if r.get("rating") == "like")
        dislikes = sum(1 for r in records if r.get("rating") == "dislike")
        rated    = likes + dislikes

        # 平均响应时间
        times = [r["response_time"] for r in records if r.get("response_time")]
        avg_time = round(sum(times) / len(times), 2) if times else 0

        # 工具调用统计
        tool_usage: Dict[str, int] = {}
        for r in records:
            for tool in r.get("tools_used", []):
                tool_usage[tool] = tool_usage.get(tool, 0) + 1

        # Skill 触发统计
        skill_usage: Dict[str, int] = {}
        for r in records:
            skill = r.get("skill_triggered")
            if skill:
                skill_usage[skill] = skill_usage.get(skill, 0) + 1

        # 最近 14 天每日对话数
        from collections import defaultdict
        daily: Dict[str, int] = defaultdict(int)
        for r in records:
            day = r.get("created_at", "")[:10]
            if day:
                daily[day] += 1
        # 只取最近 14 天
        sorted_days = sorted(daily.keys())[-14:]
        daily_counts = [{"date": d, "count": daily[d]} for d in sorted_days]

        # 最近 20 次响应时间趋势
        time_records = [r for r in records if r.get("response_time")][-20:]
        rt_trend = [
            {
                "time":  r.get("created_at", "")[:16].replace("T", " "),
                "value": r["response_time"]
            }
            for r in time_records
        ]

        return {
            "total":               len(records),
            "likes":               likes,
            "dislikes":            dislikes,
            "like_rate":           round(likes / rated * 100, 1) if rated > 0 else 0,
            "avg_response_time":   avg_time,
            "tool_usage":          dict(sorted(tool_usage.items(), key=lambda x: x[1], reverse=True)[:10]),
            "skill_usage":         dict(sorted(skill_usage.items(), key=lambda x: x[1], reverse=True)),
            "daily_counts":        daily_counts,
            "response_time_trend": rt_trend,
        }


feedback_store = FeedbackStore()