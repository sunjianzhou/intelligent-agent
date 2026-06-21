"""Interactive REPL for the CLI client."""
from __future__ import annotations
import sys
from typing import Optional, Tuple

from api import AgentClient
from session import ChatSession

try:
    from rich.console import Console
    from rich.markdown import Markdown
    from rich.panel import Panel
    from rich.text import Text
    from rich import print as rprint
    _RICH = True
    console = Console()
except ImportError:
    _RICH = False
    console = None


# ── Output helpers ────────────────────────────────────────────────────────────

def _print(msg: str, style: str = "") -> None:
    if _RICH and console:
        console.print(msg, style=style)
    else:
        print(msg)


def _print_md(text: str) -> None:
    if _RICH and console:
        console.print(Markdown(text))
    else:
        print(text)


def _print_banner() -> None:
    banner = (
        "[bold cyan]Intelligent Agent CLI[/bold cyan]  "
        "[dim]type [bold]!help[/bold] for commands, "
        "[bold]!exit[/bold] to quit[/dim]"
    )
    if _RICH and console:
        console.print(Panel(banner, expand=False, border_style="cyan"))
    else:
        print("=== Intelligent Agent CLI ===  (!help | !exit)")


def _print_tool_call(entry: dict) -> None:
    tool = entry.get("tool_name") or entry.get("tool", "?")
    result = entry.get("result", "")
    ok = entry.get("success", True)
    mark = "✓" if ok else "✗"
    if _RICH and console:
        color = "green" if ok else "red"
        console.print(f"  [{color}]{mark}[/{color}] [dim]{tool}[/dim] → {str(result)[:120]}")
    else:
        print(f"  {mark} {tool} → {str(result)[:120]}")


# ── Help text ─────────────────────────────────────────────────────────────────

_HELP = """
[bold]Available commands:[/bold]
  [cyan]!help[/cyan]              Show this help
  [cyan]!models[/cyan]            List available models
  [cyan]!model <name>[/cyan]      Switch to a different model
  [cyan]!personas[/cyan]          List available personas
  [cyan]!persona <name>[/cyan]    Switch to a different persona
  [cyan]!history[/cyan]           Show recent conversation (last 10 messages, numbered)
  [cyan]!retract <编号>[/cyan]    Permanently delete message(s) by number from !history
                       （编号取自 !history，逗号分隔可批量；注意：终端里已打印的旧行不会被改写，
                        只影响存储和下一次 !history 的展示）
  [cyan]!sessions[/cyan]          List saved session files
  [cyan]!clear[/cyan]             Start a new session (clears history)
  [cyan]!exit / !quit[/cyan]      Exit the REPL
  [dim]Anything else → sent as a chat message[/dim]
"""

_HELP_PLAIN = """
Available commands:
  !help              Show this help
  !models            List available models
  !model <name>      Switch to a different model
  !personas          List available personas
  !persona <name>    Switch to a different persona
  !history           Show recent conversation (last 10 messages, numbered)
  !retract <编号>    Permanently delete message(s) by number from !history
                     （编号取自 !history，逗号分隔可批量；注意：终端里已打印的旧行不会被改写，
                      只影响存储和下一次 !history 的展示）
  !sessions          List saved session files
  !clear             Start a new session (clears history)
  !exit / !quit      Exit the REPL
  Anything else → sent as a chat message
"""


# ── Streaming display ─────────────────────────────────────────────────────────

def stream_response(
    client: AgentClient,
    session: ChatSession,
    message: str,
    use_tools: bool,
    use_memory: bool,
) -> Tuple[str, list, Optional[str], Optional[str]]:
    """Send message with streaming; print tokens live.
    Returns (text, tool_calls, user_message_id, assistant_message_id)."""
    full_text = ""
    tool_calls: list = []
    in_tool_phase = False
    user_message_id: Optional[str] = None
    assistant_message_id: Optional[str] = None

    if _RICH and console:
        console.print("\n[bold green]Assistant:[/bold green]")
    else:
        print("\nAssistant:")

    try:
        for event in client.chat_stream(message, use_tools=use_tools, use_memory=use_memory):
            etype = event.get("type", "")
            data = event.get("data", "")

            if etype == "token":
                token = data if isinstance(data, str) else ""
                full_text += token
                sys.stdout.write(token)
                sys.stdout.flush()

            elif etype == "tool_call_start":
                if not in_tool_phase:
                    in_tool_phase = True
                    if _RICH and console:
                        console.print("\n[dim yellow]⚙ Tool calls:[/dim yellow]")
                    else:
                        print("\n⚙ Tool calls:")
                tool_name = data.get("tool_name", "?") if isinstance(data, dict) else str(data)
                args_summary = data.get("args_summary", "") if isinstance(data, dict) else ""
                if _RICH and console:
                    console.print(f"  [dim]→ {tool_name}[/dim] {args_summary[:80]}")
                else:
                    print(f"  → {tool_name} {args_summary[:80]}")

            elif etype == "tool_call":
                if isinstance(data, dict):
                    tool_calls.append(data)
                    _print_tool_call(data)

            elif etype == "tool_calls_done":
                if isinstance(data, list):
                    tool_calls = data
                in_tool_phase = False

            elif etype == "done":
                if isinstance(data, dict):
                    if not full_text:
                        full_text = data.get("content", "")
                    user_message_id = data.get("user_message_id")
                    assistant_message_id = data.get("assistant_message_id")
                break

            elif etype == "error":
                err = data if isinstance(data, str) else str(data)
                if _RICH and console:
                    console.print(f"\n[red]Error: {err}[/red]")
                else:
                    print(f"\nError: {err}")
                break

    except KeyboardInterrupt:
        if _RICH and console:
            console.print("\n[yellow]Interrupted.[/yellow]")
        else:
            print("\nInterrupted.")

    print()  # newline after stream ends
    return full_text, tool_calls, user_message_id, assistant_message_id


def non_stream_response(
    client: AgentClient,
    message: str,
    use_tools: bool,
    use_memory: bool,
) -> Tuple[str, list, Optional[str], Optional[str]]:
    """Send message without streaming.
    Returns (text, tool_calls, user_message_id, assistant_message_id)."""
    result = client.chat(message, use_tools=use_tools, use_memory=use_memory)
    text = result.get("response", "")
    tool_calls = result.get("tool_calls", [])
    if _RICH and console:
        console.print("\n[bold green]Assistant:[/bold green]")
        _print_md(text)
    else:
        print(f"\nAssistant:\n{text}")
    if tool_calls:
        if _RICH and console:
            console.print("[dim yellow]⚙ Tool calls:[/dim yellow]")
        else:
            print("⚙ Tool calls:")
        for tc in tool_calls:
            _print_tool_call(tc)
    return text, tool_calls, result.get("user_message_id"), result.get("assistant_message_id")


# ── REPL loop ─────────────────────────────────────────────────────────────────

def run_repl(
    client: AgentClient,
    session: ChatSession,
    use_tools: bool = True,
    use_memory: bool = True,
    stream: bool = True,
) -> None:
    _print_banner()

    # Show connection status
    h = client.health()
    status = h.get("status", "?")
    model = h.get("model", "?")
    if _RICH and console:
        color = "green" if status == "connected" else "red"
        console.print(f"  Server: [{color}]{status}[/{color}]  Model: [cyan]{model}[/cyan]")
    else:
        print(f"  Server: {status}  Model: {model}")

    session.model = model
    print()

    while True:
        try:
            if _RICH and console:
                raw = console.input("[bold blue]You:[/bold blue] ")
            else:
                raw = input("You: ")
        except (EOFError, KeyboardInterrupt):
            _print("\nGoodbye.", "dim")
            break

        line = raw.strip()
        if not line:
            continue

        # ── Commands ─────────────────────────────────────────────
        if line.startswith("!"):
            parts = line.split(maxsplit=1)
            cmd = parts[0].lower()
            arg = parts[1].strip() if len(parts) > 1 else ""

            if cmd in ("!exit", "!quit"):
                _print("Goodbye.", "dim")
                break

            elif cmd == "!help":
                if _RICH and console:
                    console.print(_HELP)
                else:
                    print(_HELP_PLAIN)

            elif cmd == "!models":
                try:
                    data = client.get_models()
                    current = data.get("current_model", "?")
                    models = data.get("available_models", [])
                    if _RICH and console:
                        console.print(f"[cyan]Current:[/cyan] {current}")
                        for m in models:
                            mark = "[green]●[/green]" if m == current else " "
                            console.print(f"  {mark} {m}")
                    else:
                        print(f"Current: {current}")
                        for m in models:
                            mark = "●" if m == current else " "
                            print(f"  {mark} {m}")
                except Exception as e:
                    _print(f"[red]Error:[/red] {e}", "red")

            elif cmd == "!model":
                if not arg:
                    _print("Usage: !model <name>", "yellow")
                else:
                    try:
                        result = client.switch_model(arg)
                        if result.get("success"):
                            session.model = result.get("current_model", arg)
                            _print(f"Switched to model: {session.model}", "green")
                        else:
                            _print(f"Failed: {result.get('message', '?')}", "red")
                    except Exception as e:
                        _print(f"Error: {e}", "red")

            elif cmd == "!personas":
                try:
                    data = client.get_personas()
                    personas = data.get("personas", [])
                    current = session.persona
                    if _RICH and console:
                        console.print(f"[cyan]Current:[/cyan] {current}")
                        for p in personas:
                            mark = "[green]●[/green]" if p["name"] == current else " "
                            console.print(f"  {mark} [bold]{p['name']}[/bold]  {p.get('title', '')}")
                    else:
                        print(f"Current: {current}")
                        for p in personas:
                            mark = "●" if p["name"] == current else " "
                            print(f"  {mark} {p['name']}  {p.get('title', '')}")
                except Exception as e:
                    _print(f"Error: {e}", "red")

            elif cmd == "!persona":
                if not arg:
                    _print("Usage: !persona <name>", "yellow")
                else:
                    try:
                        result = client.switch_persona(arg)
                        if result.get("success"):
                            session.persona = result.get("persona", arg)
                            _print(f"Switched to persona: {session.persona}", "green")
                        else:
                            _print(f"Failed: {result.get('message', '?')}", "red")
                    except Exception as e:
                        _print(f"Error: {e}", "red")

            elif cmd == "!history":
                recent = session.recent(10)
                if not recent:
                    _print("No messages yet.", "dim")
                else:
                    for i, msg in enumerate(recent, start=1):
                        role = msg["role"]
                        content = msg["content"][:200]
                        ts = msg.get("timestamp", "")[:16]
                        if _RICH and console:
                            color = "blue" if role == "user" else "green"
                            console.print(f"[{i}] [{color}]{role}[/{color}] [{ts}]: {content}")
                        else:
                            print(f"[{i}] {role} [{ts}]: {content}")

            elif cmd == "!sessions":
                files = session.list_saved()
                if not files:
                    _print("No saved sessions.", "dim")
                else:
                    for i, f in enumerate(files[:10]):
                        _print(f"  {i+1}. {f.name}")

            elif cmd == "!clear":
                session.clear()
                _print("Session cleared. New session started.", "yellow")

            elif cmd == "!retract":
                if not arg:
                    _print("Usage: !retract <编号>[,<编号>...]（编号见 !history）", "yellow")
                else:
                    recent = session.recent(10)
                    try:
                        indices = [int(x.strip()) for x in arg.split(",")]
                    except ValueError:
                        _print("编号格式错误，应为逗号分隔的数字，如: !retract 2,4", "red")
                        indices = []

                    targets = []
                    for idx in indices:
                        if idx < 1 or idx > len(recent):
                            _print(f"编号 {idx} 超出范围（当前 !history 共 {len(recent)} 条）", "red")
                            continue
                        msg = recent[idx - 1]
                        if not msg.get("id"):
                            _print(f"编号 {idx} 的消息无法撤回（旧版本数据，缺少 id）", "yellow")
                            continue
                        targets.append(msg)

                    if targets:
                        for t in targets:
                            preview = t["content"][:60]
                            _print(f"  [{t['role']}] {preview}", "dim")
                        warn = ""
                        if len(targets) > 1:
                            warn = "\n⚠️ 同时撤回多条消息可能造成对话上下文不连贯，请确认这些消息之间没有被后续内容依赖引用。"
                        confirm = input(f"确认撤回以上 {len(targets)} 条消息？此操作将从存储中永久删除，无法恢复。{warn}\n输入 y 确认: ")
                        if confirm.strip().lower() == "y":
                            target_ids = [t["id"] for t in targets]
                            try:
                                client.retract_messages(client.session_id, target_ids)
                                removed = session.retract(target_ids)
                                _print(f"已撤回 {removed} 条消息", "green")
                            except Exception as e:
                                _print(f"撤回失败: {e}", "red")
                        else:
                            _print("已取消", "dim")

            else:
                _print(f"Unknown command: {cmd}  (try !help)", "yellow")

            continue

        # ── Chat message ─────────────────────────────────────────
        session.add_user(line)
        try:
            if stream:
                text, tool_calls, user_msg_id, assistant_msg_id = stream_response(
                    client, session, line, use_tools, use_memory
                )
            else:
                text, tool_calls, user_msg_id, assistant_msg_id = non_stream_response(
                    client, line, use_tools, use_memory
                )
            session.set_last_message_id("user", user_msg_id)
            session.add_assistant(text, tool_calls or None, msg_id=assistant_msg_id)
        except requests_error() as e:
            _print(f"Request failed: {e}", "red")
        except Exception as e:
            _print(f"Error: {e}", "red")


def requests_error():
    try:
        import requests
        return requests.RequestException
    except ImportError:
        return Exception
