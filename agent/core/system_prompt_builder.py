"""SystemPromptBuilder — 替换 personas/prompt_builder.py，统一驱动所有 system prompt 构建。

拼装顺序（固定铁律，防 Lost-in-Middle）：
  ① SOUL + IDENTITY  ← 铁律最前
  ② USER
  ③ MEMORY
  ③.5 HEART（心证铁卷）← 用户显式标记的永久记忆，优先级高于自动蒸馏
  ③.6 RULES（主人铁律）← 不可违反的永久规则，含隐私分层 + token 退化
  ④ HEARTBEAT        ← 自检铁规段
  ⑤ persona          ← 来自 role_ctx（无角色时跳过）
  ⑥ whisper          ← 非空时追加
  ⑦ tool_overlay     ← 始终最后（独立字段，解耦 soul 层与工具层）
"""
from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

from loguru import logger


# ---------------------------------------------------------------------------
# 模块级 rules 缓存（按 rules 内容 hash + channel 做 key）
# ---------------------------------------------------------------------------

_RULES_CACHE: Dict[str, str] = {}

# 隐私分层：每个渠道允许注入的隐私等级
_RULES_PRIVACY_CHANNEL_MAP: Dict[str, set] = {
    "web": {"public", "private"},
    "cli": {"public", "private"},
    "feishu_im": {"public"},
    "wecom": {"public"},
}
_RULES_PRIVACY_DEFAULT: set = {"public", "private"}

# token 预算退化阈值
_RULES_TOKEN_DEGRADE_THRESHOLD = 4096


def invalidate_rules_cache() -> None:
    """清空 rules 缓存（heart_record 写入 rules.md 后调用）。"""
    _RULES_CACHE.clear()
    logger.debug("rules cache invalidated")


def _get_rules_cached(rules_text: str, channel: str,
                     max_context_tokens: int = 0) -> Optional[str]:
    """按 rules 内容 hash + channel + degrade 标志返回缓存。"""
    if not rules_text.strip():
        return None
    content_hash = hashlib.sha256(rules_text.encode("utf-8")).hexdigest()[:16]
    degrade = "1" if (max_context_tokens and max_context_tokens < _RULES_TOKEN_DEGRADE_THRESHOLD) else "0"
    key = f"{content_hash}_{channel}_d{degrade}"
    return _RULES_CACHE.get(key)


def _set_rules_cache(rules_text: str, channel: str, text: str,
                     max_context_tokens: int = 0) -> None:
    """将 rules section 文本存入缓存（key 含 degrade 标志）。"""
    if not rules_text.strip():
        return
    content_hash = hashlib.sha256(rules_text.encode("utf-8")).hexdigest()[:16]
    degrade = "1" if (max_context_tokens and max_context_tokens < _RULES_TOKEN_DEGRADE_THRESHOLD) else "0"
    _RULES_CACHE[f"{content_hash}_{channel}_d{degrade}"] = text


# ---------------------------------------------------------------------------
# rules.md 解析与构建
# ---------------------------------------------------------------------------

_RULE_BLOCK_RE = re.compile(
    r"### (RULE-\d+): (.+?)\n(.*?)(?=\n### RULE-|\n## |\Z)", re.DOTALL
)

# 字段匹配模式 —— 均需处理 markdown **加粗** 标记：
# 原始文本为 `- **字段名**: 值`，在 block 中为 `- **字段名**: 值`
_RX_PRIVACY = re.compile(r"隐私等级\**\s*[：:]\s*(\w+)")
_RX_STATUS = re.compile(r"状态\**\s*[：:]\s*(.+?)(?:\n|$)")
_RX_STARS = re.compile(r"重要度\**\s*[：:]\s*(★+)")
_RX_REQUIREMENT = re.compile(r"具体诉求\**\s*[：:]\s*(.+?)(?:\n-|\n\n|\n###|\Z)", re.DOTALL)
_RX_TRIGGER = re.compile(r"触发场景\**\s*[：:]\s*(.+?)(?:\n-|\n\n|\n###|\Z)", re.DOTALL)
_RX_CONSEQUENCE = re.compile(r"违反后果\**\s*[：:]\s*(.+?)(?:\n-|\n\n|\n###|\Z)", re.DOTALL)


def _parse_rules_entries(rules_text: str) -> List[Dict[str, str]]:
    """解析 rules.md，返回现行规则的结构化列表。"""
    entries: List[Dict[str, str]] = []
    for m in _RULE_BLOCK_RE.finditer(rules_text):
        rule_id = m.group(1)
        title = m.group(2)
        block = m.group(3)

        # 隐私等级
        priv_match = _RX_PRIVACY.search(block)
        privacy = priv_match.group(1) if priv_match else "private"

        # 状态（跳过已废止）
        status_match = _RX_STATUS.search(block)
        status = status_match.group(1).strip() if status_match else "现行"
        if "已废止" in status:
            continue

        # 重要度星级
        stars_match = _RX_STARS.search(block)
        priority_stars = stars_match.group(1) if stars_match else "★★★"

        # 具体诉求
        req_match = _RX_REQUIREMENT.search(block)
        requirement = req_match.group(1).strip() if req_match else ""

        # 触发场景（可选）
        trigger_match = _RX_TRIGGER.search(block)
        trigger = trigger_match.group(1).strip() if trigger_match else ""

        # 违反后果（可选）
        conseq_match = _RX_CONSEQUENCE.search(block)
        consequence = conseq_match.group(1).strip() if conseq_match else ""

        entries.append({
            "id": rule_id,
            "title": title,
            "privacy": privacy,
            "priority_stars": priority_stars,
            "requirement": requirement,
            "trigger": trigger,
            "consequence": consequence,
        })

    return entries


def _filter_rules_by_privacy(
    entries: List[Dict[str, str]], channel: str
) -> List[Dict[str, str]]:
    """按渠道过滤：web/CLI 可看 public+private，IM 渠道只能看 public。"""
    allowed = _RULES_PRIVACY_CHANNEL_MAP.get(channel, _RULES_PRIVACY_DEFAULT)
    return [e for e in entries if e["privacy"] in allowed]


def _build_rules_section(
    rules_text: str, channel: str, max_context_tokens: int = 0
) -> str:
    """构建【主人铁律】段：隐私过滤 + token 预算退化 + 摘要行。

    Args:
        rules_text: rules.md 原始文本
        channel: 当前渠道（web / cli / feishu_im / wecom）
        max_context_tokens: 模型上下文 token 预算（0 = 不限制）

    Returns:
        格式化后的铁律段文本，无可注入规则时返回空字符串。
    """
    if not rules_text.strip():
        return ""

    entries = _parse_rules_entries(rules_text)
    if not entries:
        return ""

    # 隐私分层过滤
    entries = _filter_rules_by_privacy(entries, channel)
    if not entries:
        return ""

    # 按优先级分组统计
    critical = [e for e in entries if e["priority_stars"] == "★★★★★"]
    high = [e for e in entries if e["priority_stars"] == "★★★★"]
    normal = [e for e in entries if e["priority_stars"] == "★★★"]

    total_active = len(entries)

    # token 预算退化：预算紧张时只注入 critical 级别
    degrade = max_context_tokens and 0 < max_context_tokens < _RULES_TOKEN_DEGRADE_THRESHOLD
    if degrade:
        entries = critical
        if not entries:
            return ""

    # 摘要行
    if degrade:
        summary = (
            f"【铁律摘要 — token 预算紧张，仅显示 critical 级】"
            f"共 {total_active} 条现行（critical:{len(critical)} "
            f"high:{len(high)} normal:{len(normal)}）"
        )
    else:
        summary = (
            f"【铁律摘要】共 {total_active} 条现行"
            f"（critical:{len(critical)} high:{len(high)} normal:{len(normal)}）"
        )

    # 构建条目列表
    body_lines: List[str] = [summary, ""]
    for entry in entries:
        body_lines.append(f"### {entry['id']}: {entry['title']}")
        body_lines.append(f"- **具体诉求**: {entry['requirement']}")
        if entry["trigger"]:
            body_lines.append(f"- **触发场景**: {entry['trigger']}")
        if entry["consequence"]:
            body_lines.append(f"- **违反后果**: {entry['consequence']}")
        body_lines.append(f"- **重要度**: {entry['priority_stars']}")
        body_lines.append("")

    return "\n".join(body_lines)


# ---------------------------------------------------------------------------
# SystemPromptBuilder
# ---------------------------------------------------------------------------


class SystemPromptBuilder:
    """统一 system prompt 组装器。"""

    _SEP = "\n" + "─" * 60 + "\n"

    # 不注入 whisper（私密档案）段的渠道：消息会离开本机，发往受平台内容政策约束
    # 的第三方 IM，必须保持克制风格。新增渠道时在此追加。
    _WHISPER_EXCLUDED_CHANNELS = {"feishu_im", "wecom"}

    # 不注入 heart（心证铁卷）段的渠道：与 whisper 相同的约束——心证内容属于用户
    # 私密信息，不应发送到外部 IM 平台。
    _HEART_EXCLUDED_CHANNELS = {"feishu_im", "wecom"}

    def build(
        self,
        soul: Any,
        role_ctx: Optional[Dict[str, Any]] = None,
        tool_overlay: str = "",
        channel: str = "web",
        max_context_tokens: int = 0,
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

        # ③.5 HEART（心证铁卷）——用户显式标记 > LLM 自动蒸馏，非空且渠道未被排除时追加
        if d.heart.strip() and channel not in self._HEART_EXCLUDED_CHANNELS:
            sections.append(self._wrap("【心证铁卷】", d.heart))

        # ③.6 RULES（主人铁律）——不可违反的永久规则，含隐私分层 + token 退化
        if d.rules.strip():
            rules_section = _get_rules_cached(d.rules, channel, max_context_tokens)
            if rules_section is None:
                rules_section = _build_rules_section(
                    d.rules, channel, max_context_tokens
                )
                _set_rules_cache(d.rules, channel, rules_section, max_context_tokens)
            if rules_section:
                sections.append(self._wrap("【主人铁律】", rules_section))

        # ④ HEARTBEAT（自检铁规段）
        sections.append(self._wrap("【自检铁规】", d.heartbeat))

        # ⑤ persona（无角色时跳过）
        if role_ctx:
            sections.append(self._build_persona(role_ctx))

        # ⑥ whisper（非空且当前渠道未被排除时追加）
        if d.whisper.strip() and channel not in self._WHISPER_EXCLUDED_CHANNELS:
            sections.append(self._wrap("【私密档案】", d.whisper))

        # ⑦ tool_overlay（始终最后）
        if tool_overlay.strip():
            sections.append(tool_overlay.strip())

        result = self._SEP.join(s for s in sections if s)
        if not result:
            result = "你是一个有帮助的AI助手，请用中文回答。"
            logger.warning("system prompt assembled to empty string, using fallback")
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
