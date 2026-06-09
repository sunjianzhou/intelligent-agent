"""Incremental per-user memory distillation.

Extracts lasting facts from the most-recent N conversation turns and stores
them into ChromaDB long-term memory.  Designed to run as a background
asyncio task after each assistant response so the agent grows smarter over
time without blocking the streaming path.
"""
from __future__ import annotations

import json
import re
from typing import Callable, Awaitable, Dict, Optional

from loguru import logger


class MemoryDistiller:
    """Tracks per-user turn counts and runs incremental fact extraction."""

    def __init__(self, interval: int = 5, dedup_threshold: float = 0.85):
        self.interval = interval                  # distill every N turns
        self.dedup_threshold = dedup_threshold    # skip if similarity >= threshold
        self._turn_counts: Dict[str, int] = {}

    # ──────────────────────────────────────────────────────────────
    # Turn counter
    # ──────────────────────────────────────────────────────────────

    def record_turn(self, user_id: str) -> bool:
        """Increment turn counter for user. Returns True when threshold reached."""
        n = self._turn_counts.get(user_id, 0) + 1
        self._turn_counts[user_id] = n
        return n >= self.interval

    def reset_count(self, user_id: str) -> None:
        self._turn_counts[user_id] = 0

    # ──────────────────────────────────────────────────────────────
    # Prompt
    # ──────────────────────────────────────────────────────────────

    @staticmethod
    def _build_prompt(conv_text: str) -> str:
        return (
            "从以下对话片段中提取值得长期记忆的事实。\n"
            "只提取：\n"
            "- 用户的姓名、职业、地点、身份\n"
            "- 用户明确表达的偏好（喜欢/不喜欢某物）\n"
            "- 用户正在进行的项目或目标\n"
            "- 用户具备的专业技能或领域知识\n"
            "- 用户明确陈述的个人事实\n\n"
            "不要提取：日常闲聊、助手的知识点、模糊推断性内容。\n\n"
            f"对话片段：\n{conv_text}\n\n"
            '以JSON格式输出，格式：{"facts": ["事实1", "事实2"]}\n'
            '没有值得记忆的内容则输出：{"facts": []}\n'
            "只输出JSON，不要任何其他内容。"
        )

    # ──────────────────────────────────────────────────────────────
    # Main distillation
    # ──────────────────────────────────────────────────────────────

    async def distill(
        self,
        user_id: str,
        short_term_items: list,
        call_model_fn: Callable[..., Awaitable[str]],
        long_term_memory,
    ) -> int:
        """Extract facts from recent conversation and store in long-term memory.

        Args:
            user_id:          The user whose turns to process.
            short_term_items: All MemoryItem objects from ShortTermMemory.list().
            call_model_fn:    Async callable (messages list) -> str (the LLM).
            long_term_memory: LongTermMemory instance.

        Returns:
            Number of new facts stored.
        """
        # Filter to this user's conversation turns only
        user_items = [
            m for m in short_term_items
            if m.metadata.get("user_id") == user_id
            and m.metadata.get("role") in ("user", "assistant")
        ]
        if not user_items:
            return 0

        # Take only the last interval*2 messages (= interval turns)
        window = user_items[-(self.interval * 2):]
        conv_text = "\n".join(
            f"{'用户' if m.metadata.get('role') == 'user' else '助手'}: {m.content[:200]}"
            for m in window
        )

        # Call LLM
        try:
            raw = await call_model_fn([
                {"role": "system", "content": "你是一个信息提取助手，只输出JSON格式数据。"},
                {"role": "user", "content": self._build_prompt(conv_text)},
            ])
            clean = re.sub(r"```(?:json)?", "", raw).replace("```", "").strip()
            data = json.loads(clean)
            facts = [f for f in data.get("facts", []) if isinstance(f, str) and f.strip()]
        except Exception as exc:
            logger.warning(f"记忆提炼解析失败 (user={user_id}): {exc}")
            return 0

        # Store with dedup check
        stored = 0
        for fact in facts:
            if self._is_duplicate(fact, long_term_memory, user_id):
                logger.debug(f"跳过重复事实: {fact[:60]}")
                continue
            long_term_memory.store(
                content=fact,
                metadata={
                    "type": "fact",
                    "source": "distillation",
                    "user_id": user_id,
                },
                importance=0.75,
            )
            stored += 1

        if stored > 0:
            logger.info(f"记忆提炼: user={user_id} 新增 {stored} 条事实")

        self.reset_count(user_id)
        return stored

    def _is_duplicate(self, fact: str, long_term_memory, user_id: str) -> bool:
        """Return True if a sufficiently similar fact already exists."""
        try:
            from memory.base import MemoryQuery
            q = MemoryQuery(text=fact, limit=1, metadata_filter={"user_id": user_id})
            results = long_term_memory.retrieve(q)
            if results and results[0].similarity >= self.dedup_threshold:
                return True
        except Exception:
            pass
        return False
