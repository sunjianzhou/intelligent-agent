"""命令积累：追加写入 memory/<topic>/commands.md，按日期分组。"""
from datetime import datetime
from pathlib import Path

_BASE = Path(__file__).parent.parent / "data" / "memory"

_TOPIC_DIR = {
    "k8s": "k8s-learning",
    "k8s_review": "k8s-learning",
    "llm": "llm-learning",
    "agent": "agent-design",
}


def _path(topic: str) -> Path:
    dirname = _TOPIC_DIR.get(topic, f"{topic}-learning")
    return _BASE / dirname / "commands.md"


def append(topic: str, command: str, description: str) -> None:
    p = _path(topic)
    p.parent.mkdir(parents=True, exist_ok=True)
    today = datetime.now().strftime("%Y-%m-%d")
    header = f"## {today}"
    entry = f"- `{command}`: {description}\n"

    if p.exists():
        content = p.read_text(encoding="utf-8")
        if header in content:
            # Insert entry right after the existing date header line
            idx = content.index(header) + len(header)
            content = content[:idx] + "\n" + entry + content[idx:]
        else:
            content = content.rstrip("\n") + f"\n\n{header}\n{entry}"
    else:
        content = f"{header}\n{entry}"

    p.write_text(content, encoding="utf-8")
