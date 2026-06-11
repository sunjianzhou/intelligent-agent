"""管理员运维 CLI — 直连 Python Agent FastAPI 服务。

用法:
  python cli.py health
  python cli.py model list
  python cli.py model switch <model_name>
  python cli.py memory stats
  python cli.py memory export <output.json>
  python cli.py memory clear [--category conversation|task|knowledge|fact|preference]
  python cli.py task list [--status pending|running|done|failed]
  python cli.py task cancel <task_id>
"""
import argparse
import json
import sys
import urllib.request
import urllib.error
from datetime import datetime

BASE_URL = "http://localhost:8000"
_TIMEOUT = 10


# ── HTTP 工具 ─────────────────────────────────────────────────────────────────

def _req(method: str, path: str, body: dict | None = None) -> dict:
    url = BASE_URL + path
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return json.loads(raw)
        except Exception:
            return {"success": False, "message": f"HTTP {e.code}: {raw[:200]}"}
    except Exception as e:
        return {"success": False, "message": str(e)}


def _get(path: str) -> dict:
    return _req("GET", path)


def _post(path: str, body: dict) -> dict:
    return _req("POST", path, body)


def _delete(path: str) -> dict:
    return _req("DELETE", path)


# ── 子命令 ────────────────────────────────────────────────────────────────────

def cmd_health(args):
    data = _get("/health")
    status = data.get("status", "unknown")
    ollama = data.get("ollama_available", False)
    model  = data.get("current_model", "—")
    print(f"Status  : {status}")
    print(f"Ollama  : {'ok' if ollama else 'unavailable'}")
    print(f"Model   : {model}")
    uptime = data.get("uptime_seconds")
    if uptime is not None:
        h, rem = divmod(int(uptime), 3600)
        m, s   = divmod(rem, 60)
        print(f"Uptime  : {h:02d}:{m:02d}:{s:02d}")
    return 0 if status == "ok" else 1


def cmd_model_list(args):
    data = _get("/api/models")
    models  = data.get("models", [])
    current = data.get("current_model", "")
    for m in models:
        marker = "*" if m == current else " "
        print(f"  {marker} {m}")
    return 0


def cmd_model_switch(args):
    data = _post("/api/model/switch", {"model": args.model_name})
    if data.get("success"):
        print(f"Switched to: {args.model_name}")
        return 0
    print(f"Failed: {data.get('message', 'unknown error')}", file=sys.stderr)
    return 1


def cmd_memory_stats(args):
    data = _get("/api/memory/stats")
    short = data.get("short_term", {})
    long_ = data.get("long_term", {})
    print(f"Short-term : {short.get('count', '?')} entries")
    print(f"Long-term  : {long_.get('count', '?')} entries")
    if "categories" in long_:
        for cat, cnt in long_["categories"].items():
            print(f"  {cat:<18} {cnt}")
    return 0


def cmd_memory_export(args):
    data = _get("/api/memory/export")
    entries = data.get("memories", data.get("entries", []))
    out = args.output if hasattr(args, "output") and args.output else None
    payload = json.dumps(entries, ensure_ascii=False, indent=2)
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write(payload)
        print(f"Exported {len(entries)} entries → {out}")
    else:
        print(payload)
    return 0


def cmd_memory_clear(args):
    category = getattr(args, "category", None)
    path = f"/api/memory/clear"
    if category:
        path += f"?category={category}"
    data = _delete(path)
    if data.get("success"):
        scope = f"category={category}" if category else "all"
        print(f"Memory cleared ({scope})")
        return 0
    print(f"Failed: {data.get('message', 'unknown error')}", file=sys.stderr)
    return 1


def cmd_task_list(args):
    status = getattr(args, "status", None)
    path = "/api/tasks/list"
    if status:
        path += f"?status={status}"
    data = _get(path)
    tasks = data.get("tasks", [])
    if not tasks:
        print("No tasks found.")
        return 0
    fmt = "{:<36}  {:<10}  {:<20}  {}"
    print(fmt.format("ID", "STATUS", "NEXT_RUN", "NAME"))
    print("-" * 90)
    for t in tasks:
        next_run = t.get("next_run") or t.get("scheduled_time") or "—"
        if next_run and "T" in next_run:
            try:
                next_run = datetime.fromisoformat(next_run).strftime("%Y-%m-%d %H:%M:%S")
            except Exception:
                pass
        print(fmt.format(t.get("id", "?"), t.get("status", "?"), next_run, t.get("name", "?")))
    return 0


def cmd_task_cancel(args):
    data = _post(f"/api/tasks/{args.task_id}/cancel", {})
    if data.get("success"):
        print(f"Task {args.task_id} cancelled")
        return 0
    print(f"Failed: {data.get('message', 'unknown error')}", file=sys.stderr)
    return 1


# ── 参数解析 ──────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="python cli.py",
        description="Intelligent Agent admin CLI",
    )
    p.add_argument("--base-url", default=BASE_URL, help="Agent API base URL")
    sub = p.add_subparsers(dest="command")

    sub.add_parser("health", help="Check service health")

    model_p = sub.add_parser("model", help="Model management")
    model_sub = model_p.add_subparsers(dest="model_cmd")
    model_sub.add_parser("list", help="List available models")
    sw = model_sub.add_parser("switch", help="Switch active model")
    sw.add_argument("model_name")

    mem_p = sub.add_parser("memory", help="Memory management")
    mem_sub = mem_p.add_subparsers(dest="mem_cmd")
    mem_sub.add_parser("stats", help="Show memory statistics")
    exp = mem_sub.add_parser("export", help="Export all memories to JSON")
    exp.add_argument("output", nargs="?", help="Output file (default: stdout)")
    clr = mem_sub.add_parser("clear", help="Clear memories")
    clr.add_argument("--category", choices=["conversation", "task", "knowledge", "fact", "preference"],
                     help="Clear only this category (default: all)")

    task_p = sub.add_parser("task", help="Task management")
    task_sub = task_p.add_subparsers(dest="task_cmd")
    tl = task_sub.add_parser("list", help="List tasks")
    tl.add_argument("--status", choices=["pending", "running", "done", "failed"])
    tc = task_sub.add_parser("cancel", help="Cancel a task")
    tc.add_argument("task_id")

    return p


def main():
    global BASE_URL
    parser = build_parser()
    args = parser.parse_args()

    if hasattr(args, "base_url"):
        BASE_URL = args.base_url.rstrip("/")

    if args.command == "health":
        sys.exit(cmd_health(args))
    elif args.command == "model":
        if args.model_cmd == "list":
            sys.exit(cmd_model_list(args))
        elif args.model_cmd == "switch":
            sys.exit(cmd_model_switch(args))
        else:
            parser.print_help()
    elif args.command == "memory":
        if args.mem_cmd == "stats":
            sys.exit(cmd_memory_stats(args))
        elif args.mem_cmd == "export":
            sys.exit(cmd_memory_export(args))
        elif args.mem_cmd == "clear":
            sys.exit(cmd_memory_clear(args))
        else:
            parser.print_help()
    elif args.command == "task":
        if args.task_cmd == "list":
            sys.exit(cmd_task_list(args))
        elif args.task_cmd == "cancel":
            sys.exit(cmd_task_cancel(args))
        else:
            parser.print_help()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
