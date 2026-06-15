"""SoulLoader — 从 soul/ 目录加载灵魂文件，构造 SoulData。"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from loguru import logger


@dataclass
class SoulData:
    soul: str
    user: str
    memory: str
    identity: str
    heartbeat: str  # HEARTBEAT.md 内容——推送前自检铁规延伸，注入 system prompt 自检段
    whisper: str    # whisper.md 内容（可为空字符串）


class SoulLoader:
    """从 soul/ 目录加载灵魂文件。

    soul_dir 默认路径：agent/soul/loader.py → agent/soul/ → agent/ → 项目根 → soul/
    不依赖 CWD，由文件位置锚定。
    """

    _DEFAULT_SOUL_DIR = Path(__file__).parent.parent.parent / "soul"

    REQUIRED = ["SOUL", "USER", "MEMORY", "IDENTITY", "HEARTBEAT"]
    OPTIONAL = ["whisper"]

    def __init__(self, soul_dir: Optional[str] = None) -> None:
        self._soul_dir = Path(soul_dir) if soul_dir else self._DEFAULT_SOUL_DIR
        self._data: Optional[SoulData] = None
        self.load()

    def load(self) -> SoulData:
        """加载（或热重载）全部灵魂文件。必选文件缺失时抛 FileNotFoundError。"""
        self._data = None  # 失败时状态明确
        parts: dict[str, str] = {}

        for name in self.REQUIRED:
            path = self._soul_dir / f"{name}.md"
            if not path.exists():
                raise FileNotFoundError(f"必选灵魂文件缺失: {path}")
            parts[name.lower()] = path.read_text(encoding="utf-8")

        for name in self.OPTIONAL:
            opt_path = self._soul_dir / f"{name}.md"
            parts[name.lower()] = opt_path.read_text(encoding="utf-8") if opt_path.exists() else ""

        self._data = SoulData(
            soul=parts["soul"],
            user=parts["user"],
            memory=parts["memory"],
            identity=parts["identity"],
            heartbeat=parts["heartbeat"],
            whisper=parts["whisper"],
        )
        logger.info("灵魂加载成功")
        return self._data

    @property
    def data(self) -> Optional[SoulData]:
        return self._data

    def reload(self) -> SoulData:
        """热重载：运行时调用，无需重启服务。"""
        return self.load()
