"""工具调用历史持久化存储"""
import json
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any, Optional


class ToolCallStore:

    def __init__(self, base_dir: str = "./data/tool_calls"):
        self.base_dir = Path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self._log_file = self.base_dir / "calls.jsonl"

    def record(self, tool_name: str, args: dict, result: Any,
               success: bool, duration_ms: float,
               username: str = "system") -> None:
        entry = {
            "id":          f"tc_{datetime.now().strftime('%Y%m%d%H%M%S%f')}",
            "tool_name":   tool_name,
            "args":        args,
            "result_brief": str(result)[:200] if result else "",
            "success":     success,
            "duration_ms": round(duration_ms, 1),
            "username":    username,
            "timestamp":   datetime.now().isoformat(),
        }
        try:
            with self._log_file.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        except Exception:
            pass

    def list_calls(self, limit: int = 100, tool_name: Optional[str] = None,
                   success: Optional[bool] = None) -> List[Dict[str, Any]]:
        if not self._log_file.exists():
            return []
        lines = self._log_file.read_text(encoding="utf-8").strip().splitlines()
        records = []
        for line in reversed(lines):
            try:
                r = json.loads(line)
                if tool_name and r.get("tool_name") != tool_name:
                    continue
                if success is not None and r.get("success") != success:
                    continue
                records.append(r)
                if len(records) >= limit:
                    break
            except Exception:
                continue
        return records

    def get_stats(self) -> Dict[str, Any]:
        calls = self.list_calls(limit=10000)
        if not calls:
            return {"total": 0, "by_tool": {}, "success_rate": 0}
        by_tool: Dict[str, Dict] = {}
        for c in calls:
            n = c["tool_name"]
            if n not in by_tool:
                by_tool[n] = {"count": 0, "success": 0, "avg_ms": 0, "_total_ms": 0}
            by_tool[n]["count"] += 1
            by_tool[n]["_total_ms"] += c.get("duration_ms", 0)
            if c.get("success"):
                by_tool[n]["success"] += 1
        for n, d in by_tool.items():
            d["avg_ms"] = round(d["_total_ms"] / d["count"], 1) if d["count"] else 0
            d["success_rate"] = round(d["success"] / d["count"] * 100, 1) if d["count"] else 0
            del d["_total_ms"]
        total_ok = sum(1 for c in calls if c.get("success"))
        return {
            "total": len(calls),
            "by_tool": by_tool,
            "success_rate": round(total_ok / len(calls) * 100, 1) if calls else 0,
        }


tool_call_store = ToolCallStore()
