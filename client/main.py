#!/usr/bin/env python3
"""
Intelligent Agent CLI Client
Usage:
  python main.py                          # interactive REPL
  python main.py "your question"          # single-shot
  python main.py "question" --no-stream   # single-shot without streaming
  python main.py --model dolphin3:8b      # start with a specific model
  python main.py --no-tools               # disable tool use
  python main.py --url http://host:8000   # custom server URL
"""
import argparse
import sys
from pathlib import Path

# ── Load config ───────────────────────────────────────────────────────────────
_CONFIG_FILE = Path(__file__).parent / "config.yaml"

def _load_config() -> dict:
    try:
        import yaml
        if _CONFIG_FILE.exists():
            with open(_CONFIG_FILE, encoding="utf-8") as f:
                return yaml.safe_load(f) or {}
    except ImportError:
        pass
    except Exception as e:
        print(f"[warn] Failed to load config.yaml: {e}", file=sys.stderr)
    return {}


def _resolve_data_dir(cfg: dict) -> str:
    raw = cfg.get("data", {}).get("dir", "./datas")
    p = Path(raw)
    if not p.is_absolute():
        p = Path(__file__).parent / raw
    return str(p)


# ── CLI entry point ───────────────────────────────────────────────────────────
def main() -> None:
    cfg = _load_config()
    srv = cfg.get("server", {})
    chat_cfg = cfg.get("chat", {})
    data_cfg = cfg.get("data", {})

    parser = argparse.ArgumentParser(
        description="Intelligent Agent CLI Client",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("message", nargs="?", help="Message to send (omit for REPL mode)")
    parser.add_argument("--url",      default=srv.get("url", "http://localhost:8000"),
                        help="Python agent URL (default: %(default)s)")
    parser.add_argument("--secret",   default=srv.get("jwt_secret",
                        "local-dev-only-change-in-production-must-be-32chars"),
                        help="JWT secret for auth")
    parser.add_argument("--user",     default=srv.get("user_id", "cli-user"),
                        help="User ID embedded in JWT (default: %(default)s)")
    parser.add_argument("--timeout",  type=int, default=srv.get("timeout", 300),
                        help="Request timeout in seconds (default: %(default)s)")
    parser.add_argument("--model",    default=None,
                        help="Switch to this model before chatting")
    parser.add_argument("--persona",  default=None,
                        help="Switch to this persona before chatting")
    parser.add_argument("--no-tools", action="store_true",
                        help="Disable tool use")
    parser.add_argument("--no-memory", action="store_true",
                        help="Disable memory context")
    parser.add_argument("--stream",   action="store_true",
                        default=chat_cfg.get("stream", True),
                        help="Force streaming mode (default: on)")
    parser.add_argument("--no-stream", action="store_true",
                        help="Disable streaming (wait for full response)")
    parser.add_argument("--no-save",  action="store_true",
                        help="Do not save session to datas/")
    parser.add_argument("--load",     default=None, metavar="FILE",
                        help="Load a previous session JSON file")
    args = parser.parse_args()

    use_tools  = not args.no_tools
    use_memory = not args.no_memory
    stream     = not args.no_stream
    save       = not args.no_save and data_cfg.get("save_sessions", True)
    data_dir   = _resolve_data_dir(cfg)

    # Build client
    from api import AgentClient
    client = AgentClient(
        url=args.url,
        jwt_secret=args.secret,
        user_id=args.user,
        timeout=args.timeout,
    )

    # Build session
    from session import ChatSession
    if args.load:
        try:
            session = ChatSession.load(args.load, data_dir=data_dir)
            print(f"Loaded session: {args.load} ({len(session.messages)} messages)")
        except Exception as e:
            print(f"Failed to load session: {e}", file=sys.stderr)
            session = ChatSession(data_dir=data_dir, save=save)
    else:
        session = ChatSession(data_dir=data_dir, save=save)

    # Pre-switch model / persona if requested
    if args.model:
        try:
            result = client.switch_model(args.model)
            if result.get("success"):
                session.model = result.get("current_model", args.model)
                print(f"Model: {session.model}")
            else:
                print(f"Model switch failed: {result.get('message', '?')}", file=sys.stderr)
        except Exception as e:
            print(f"Model switch error: {e}", file=sys.stderr)

    if args.persona:
        try:
            result = client.switch_persona(args.persona)
            if result.get("success"):
                session.persona = args.persona
                print(f"Persona: {session.persona}")
            else:
                print(f"Persona switch failed: {result.get('message', '?')}", file=sys.stderr)
        except Exception as e:
            print(f"Persona switch error: {e}", file=sys.stderr)

    # ── Single-shot mode ───────────────────────────────────────────────────
    if args.message:
        session.add_user(args.message)
        try:
            if stream:
                from repl import stream_response
                text, tool_calls, user_msg_id, assistant_msg_id = stream_response(
                    client, session, args.message, use_tools, use_memory
                )
            else:
                from repl import non_stream_response
                text, tool_calls, user_msg_id, assistant_msg_id = non_stream_response(
                    client, args.message, use_tools, use_memory
                )
            session.set_last_message_id("user", user_msg_id)
            session.add_assistant(text, tool_calls or None, msg_id=assistant_msg_id)
        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)
        return

    # ── Interactive REPL ──────────────────────────────────────────────────
    from repl import run_repl
    run_repl(
        client=client,
        session=session,
        use_tools=use_tools,
        use_memory=use_memory,
        stream=stream,
    )


if __name__ == "__main__":
    main()
