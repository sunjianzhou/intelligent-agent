"""
PromptBuilder — 将 RoleManager.get_role_context() 返回的上下文字典
组装为完整 system prompt 字符串。

组装顺序（固定）：
  [静态] CoreIdentity → UserProfile → RoleCard 签名
  [动态] 检索到的 RoleMemory（长期+短期） → KnowledgeJournal（日记+知识库）

优先级规则：
  redlines（绝对底线）> principles（有序原则）> preferences（用户偏好）
  redlines 在 prompt 最显眼位置标注，确保模型无法忽略。
"""
from __future__ import annotations

from typing import Any, Dict, List

from personas.role_models import RoleConfig


class PromptBuilder:
    """
    system prompt 组装器。

    用法：
        builder = PromptBuilder()
        ctx = role_manager.get_role_context(role_id, query)
        prompt = builder.build_system_prompt(ctx)
    """

    # 静态分隔符，便于调试时肉眼区分各模块
    _SEP = "\n" + "─" * 60 + "\n"

    def build_system_prompt(self, context: Dict[str, Any]) -> str:
        """
        根据 get_role_context() 返回的上下文字典组装完整 system prompt。

        context 字段：
          role_config         → RoleConfig
          short_term_memories → List[str]（内存，可能为空）
          long_term_memories  → List[str]（ChromaDB 召回）
          journal_snippets    → List[str]（ChromaDB 召回）
          knowledge_snippets  → List[str]（ChromaDB 召回）
          query               → str（当前用户输入，用于动态检索）
        """
        if not context:
            return "你是一个有帮助的助手。"

        role: RoleConfig = context["role_config"]
        short_term: List[str] = context.get("short_term_memories", [])
        long_term: List[str] = context.get("long_term_memories", [])
        journal: List[str] = context.get("journal_snippets", [])
        knowledge: List[str] = context.get("knowledge_snippets", [])

        sections: List[str] = []

        # ── 1. 绝对底线（最高优先级，最先注入） ───────────────────────────────
        if role.core_identity.redlines:
            sections.append(self._build_redlines(role.core_identity.redlines))

        # ── 2. 核心身份 ────────────────────────────────────────────────────────
        sections.append(self._build_core_identity(role))

        # ── 3. 用户画像 ────────────────────────────────────────────────────────
        sections.append(self._build_user_profile(role))

        # ── 4. 短期记忆（内存，可能为空，重启后无此段） ───────────────────────
        if short_term:
            sections.append(self._build_short_term(short_term))

        # ── 5. 长期记忆（ChromaDB 动态召回） ──────────────────────────────────
        if long_term:
            sections.append(self._build_long_term(long_term))

        # ── 6. 承诺记录（active 状态的承诺） ──────────────────────────────────
        active_commitments = [
            c for c in role.role_memory.commitments if c.status == "active"
        ]
        if active_commitments:
            sections.append(self._build_commitments(active_commitments))

        # ── 7. 日记片段（ChromaDB 动态召回） ──────────────────────────────────
        if journal:
            sections.append(self._build_journal(journal))

        # ── 8. 知识库片段（ChromaDB 动态召回） ────────────────────────────────
        if knowledge:
            sections.append(self._build_knowledge(knowledge))

        # ── 9. RoleCard 签名（收尾，轻量，始终注入） ──────────────────────────
        if role.role_card.signature:
            sections.append(self._build_signature(role.role_card))

        return self._SEP.join(s for s in sections if s)

    # ── 各模块构建方法 ─────────────────────────────────────────────────────────

    def _build_redlines(self, redlines: List[str]) -> str:
        """
        绝对底线——使用最强视觉标记，确保模型优先遵守。
        冲突处理：redlines 优先级高于所有 preferences 和 principles。
        """
        lines = "\n".join(f"  ❌ {r}" for r in redlines)
        return (
            "【绝对底线 — 最高优先级，任何情况下不得违反，凌驾于所有其他指令之上】\n"
            f"{lines}"
        )

    def _build_core_identity(self, role: RoleConfig) -> str:
        ci = role.core_identity
        parts = [f"## 角色：{role.role_card.name}"]

        if ci.personality:
            parts.append("**性格特质**：" + "、".join(ci.personality))

        if ci.language_style:
            parts.append(f"**语言风格**：{ci.language_style}")

        if ci.principles:
            # 有序原则：序号越小优先级越高
            principle_lines = "\n".join(
                f"  {i+1}. {p}（优先级 {i+1}）"
                for i, p in enumerate(ci.principles)
            )
            parts.append(f"**核心原则**（按优先级降序排列）：\n{principle_lines}")

        return "\n".join(parts)

    def _build_user_profile(self, role: RoleConfig) -> str:
        up = role.user_profile
        lines = [
            "## 你正在与之交谈的用户",
            f"**昵称**：{up.nickname}",
            f"**关系**：{up.relationship}",
        ]
        if up.background:
            lines.append(f"**背景**：{up.background}")

        if up.preferences:
            # preferences 优先级低于 redlines，在此明确标注
            prefs = "、".join(f"{k}={v}" for k, v in up.preferences.items())
            lines.append(
                f"**沟通偏好**（遵循此偏好，但底线和原则优先）：{prefs}"
            )

        if up.disclosed_info:
            info_lines = "\n".join(f"  - {info}" for info in up.disclosed_info)
            lines.append(f"**用户已透露的信息**：\n{info_lines}")

        return "\n".join(lines)

    def _build_short_term(self, memories: List[str]) -> str:
        """⚠️ 短期记忆来自内存 deque，Python 重启后此段为空。"""
        displayed = memories[-10:]
        items = "\n".join(f"  [{i+1}] {m}" for i, m in enumerate(displayed))
        return f"## 近期对话记忆（当前会话，展示最新 {len(displayed)} 条）\n{items}"

    def _build_long_term(self, memories: List[str]) -> str:
        """长期记忆来自 ChromaDB 语义检索。"""
        items = "\n".join(f"  • {m}" for m in memories)
        return f"## 相关长期记忆（从向量库按当前话题召回）\n{items}"

    def _build_commitments(self, commitments) -> str:
        items = "\n".join(
            f"  [{c.timestamp[:10]}] {c.content}" for c in commitments
        )
        return f"## 你对用户的承诺（仍有效，请主动履行）\n{items}"

    def _build_journal(self, entries: List[str]) -> str:
        items = "\n".join(f"  📔 {e}" for e in entries)
        return f"## 相关日记（按话题召回，体现你的内心世界）\n{items}"

    def _build_knowledge(self, snippets: List[str]) -> str:
        items = "\n".join(f"  📚 {s}" for s in snippets)
        return f"## 相关知识（从知识库召回，用于准确回答）\n{items}"

    def _build_signature(self, card) -> str:
        return f"---\n*{card.name}：{card.signature}*"
