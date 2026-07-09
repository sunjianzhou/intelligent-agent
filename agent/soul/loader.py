"""SoulLoader — 从 soul/ 目录加载灵魂文件，构造 SoulData。

v1.1（2026-07-09）：新增文件大小监控 + 告警
  - SoulData 新增 total_chars / file_sizes 字段（可观测性）
  - max_file_size 参数（默认 50KB），超过时 WARNING 但不阻断
  - total_chars 超过 max_context_chars 时 WARNING（token 预算风险提示）
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Optional

from loguru import logger


@dataclass(frozen=True)
class SoulData:
    soul: str
    user: str
    memory: str
    identity: str
    heartbeat: str  # HEARTBEAT.md 内容——推送前自检铁规延伸，注入 system prompt 自检段
    whisper: str    # whisper.md 内容（可为空字符串）
    heart: str      # heart.md 心证铁卷——用户显式标记的永久记忆（可为空字符串）
    rules: str = "" # rules.md 主人铁律——21 条不可违反的永久规则（可为空字符串）
    # ── 可观测性（v1.1）──────────────────────────────────────
    total_chars: int = 0        # 所有已加载文件的字符总数
    file_sizes: Dict[str, int] = field(default_factory=dict)  # {文件名: 字符数}


class SoulLoader:
    """从 soul/ 目录加载灵魂文件。

    soul_dir 默认路径：agent/soul/loader.py → agent/soul/ → agent/ → 项目根 → soul/
    不依赖 CWD，由文件位置锚定。

    v1.1 新增：
      - max_file_size：单个文件最大字节数（默认 50KB），超限时 WARNING 但不阻断
      - 文件大小追踪：SoulData.total_chars + file_sizes 供可观测性使用
      - 内容从不硬截断——文件大小限制仅用于告警，不做静默裁剪
    """

    _DEFAULT_SOUL_DIR = Path(__file__).parent.parent.parent / "soul"

    # 单个文件告警阈值（字节），超过时 logger.warning
    _DEFAULT_MAX_FILE_SIZE = 50_000  # 50KB

    # 总内容告警阈值（字符数），超过时提示 token 预算风险
    # 中文约 1 字符 ≈ 0.5-1 token，max_context_tokens 默认 7000
    # 阈值设 14_000 字符 ≈ 7000-14000 tokens（留余地给 conversation messages）
    _DEFAULT_MAX_TOTAL_CHARS = 14_000

    REQUIRED = ["SOUL", "USER", "MEMORY", "IDENTITY", "HEARTBEAT"]
    OPTIONAL = ["whisper", "heart", "rules"]

    def __init__(
        self,
        soul_dir: Optional[str] = None,
        max_file_size: int = _DEFAULT_MAX_FILE_SIZE,
        max_total_chars: int = _DEFAULT_MAX_TOTAL_CHARS,
    ) -> None:
        self._soul_dir = Path(soul_dir) if soul_dir else self._DEFAULT_SOUL_DIR
        self._max_file_size = max_file_size
        self._max_total_chars = max_total_chars
        self._data: Optional[SoulData] = None
        self.load()

    def load(self) -> SoulData:
        """加载（或热重载）全部灵魂文件。必选文件缺失时抛 FileNotFoundError。"""
        parts: dict[str, str] = {}
        file_sizes: dict[str, int] = {}
        oversized: list[str] = []

        for name in self.REQUIRED:
            path = self._soul_dir / f"{name}.md"
            if not path.exists():
                self._data = None  # 明确失败状态
                raise FileNotFoundError(f"必选灵魂文件缺失: {path}")
            content = path.read_text(encoding="utf-8")
            char_count = len(content)
            file_sizes[name.lower()] = char_count
            if char_count > self._max_file_size:
                oversized.append(f"{name}.md ({char_count} chars > {self._max_file_size} limit)")
            parts[name.lower()] = content

        for name in self.OPTIONAL:
            opt_path = self._soul_dir / f"{name}.md"
            if opt_path.exists():
                content = opt_path.read_text(encoding="utf-8")
                char_count = len(content)
                file_sizes[name.lower()] = char_count
                if char_count > self._max_file_size:
                    oversized.append(f"{name}.md ({char_count} chars > {self._max_file_size} limit)")
                parts[name.lower()] = content
            else:
                parts[name.lower()] = ""
                file_sizes[name.lower()] = 0

        total_chars = sum(file_sizes.values())

        # ── 大小告警（不阻断，仅日志）────────────────────────
        if oversized:
            logger.warning(
                f"灵魂文件超大（>{self._max_file_size} chars）: {', '.join(oversized)}. "
                f"建议精简内容或调高 max_file_size 参数消除此告警。"
            )

        if total_chars > self._max_total_chars:
            logger.warning(
                f"灵魂层总内容 {total_chars} chars 超过告警阈值 {self._max_total_chars} chars. "
                f"当前 max_context_tokens 默认 7000，中文内容约 {total_chars // 2}~{total_chars} tokens，"
                f"可能挤压 conversation messages 空间，导致 HEARTBEAT/persona/whisper/tool_overlay 被硬截断。"
                f"建议：①增大 OLLAMA_NUM_CTX（当前 4096→8192+）②精简 SOUL/USER/MEMORY ③提高 max_context_tokens"
            )

        if total_chars > 0:
            logger.info(
                f"灵魂加载成功: {total_chars} chars total "
                f"({', '.join(f'{k}={v}' for k, v in sorted(file_sizes.items()) if v > 0)})"
            )
        else:
            logger.info("灵魂加载成功（所有文件均为空）")

        new_data = SoulData(
            soul=parts["soul"],
            user=parts["user"],
            memory=parts["memory"],
            identity=parts["identity"],
            heartbeat=parts["heartbeat"],
            whisper=parts["whisper"],
            heart=parts.get("heart", ""),
            rules=parts.get("rules", ""),
            total_chars=total_chars,
            file_sizes=file_sizes,
        )
        self._data = new_data  # 原子赋值，最小化并发窗口
        return self._data

    @property
    def data(self) -> Optional[SoulData]:
        return self._data

    def reload(self) -> SoulData:
        """热重载：运行时调用，无需重启服务。"""
        return self.load()
