"""SystemPromptBuilder — 替换 personas/prompt_builder.py，统一驱动所有 system prompt 构建。

拼装顺序（固定铁律，防 Lost-in-Middle）：
  ① SOUL + IDENTITY  ← 铁律最前
  ② USER
  ③ MEMORY
  ④ HEARTBEAT        ← 自检铁规段
  ⑤ persona          ← 来自 role_ctx（无角色时跳过）
  ⑥ whisper          ← 非空时追加
  ⑦ tool_overlay     ← 始终最后（独立字段，解耦 soul 层与工具层）
"""
from __future__ import annotations

from typing import Any, Dict, Optional

from loguru import logger


class SystemPromptBuilder:
    """统一 system prompt 组装器。"""

    _SEP = "\n" + "─" * 60 + "\n"

    def build(
        self,
        soul: Any,
        role_ctx: Optional[Dict[str, Any]] = None,
        tool_overlay: str = "",
    ) -> str:
        d = soul.data
        if d is None:
            raise RuntimeError("soul not loaded")

        sections: list[str] = []

        # ① SOUL + IDENTITY（铁律最前，防 Lost-in-Middle）
        sections.append(self._wrap("【灵魂核心】", d.soul))
        sections.append(self._wrap("【身份】", d.identity))

        # ② USER
        sections.append(self._wrap("【用户画像】", d.user))

        # ③ MEMORY
        sections.append(self._wrap("【精选记忆】", d.memory))

        # ④ HEARTBEAT（自检铁规段）
        sections.append(self._wrap("【自检铁规】", d.heartbeat))

        # ⑤ persona（无角色时跳过）
        if role_ctx:
            sections.append(self._build_persona(role_ctx))

        # ⑥ whisper（非空时追加）
        if d.whisper.strip():
            sections.append(self._wrap("【私密档案】", d.whisper))

        # ⑦ tool_overlay（始终最后）
        if tool_overlay.strip():
            sections.append(tool_overlay.strip())

        result = self._SEP.join(s for s in sections if s)
        logger.debug("final prompt: %d chars", len(result))
        return result

    def _build_persona(self, role_ctx: Dict[str, Any]) -> str:
        """组装 persona 段。简化路径：仅含 persona_md 时直接返回字符串。"""
        if set(role_ctx.keys()) == {"persona_md"}:
            return role_ctx["persona_md"]
        # 完整路径：多角色接入（v4.7.8 决策 B·渐进式迁移）
        from personas.prompt_builder import PromptBuilder
        return PromptBuilder().build_system_prompt(role_ctx)

    def _wrap(self, header: str, content: str) -> str:
        """包装一个 prompt 段：header + 内容，内容为空时返回空字符串（被过滤）。"""
        return f"{header}\n{content.strip()}" if content.strip() else ""
