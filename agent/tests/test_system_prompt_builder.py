"""Tests for SystemPromptBuilder — W1-W8 coverage.

W1-W5: section order, whisper, persona, HEARTBEAT, heart (12 cases)
W8 (TODO-97): RULES injection, privacy filtering, cache, token degradation (14 cases)
"""
import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from soul.loader import SoulData


def _make_soul(soul="灵魂内容", user="用户画像", memory="精选记忆",
               identity="霖君身份", heartbeat="自检铁规内容", whisper="", heart="",
               rules=""):
    loader = MagicMock()
    loader.data = SoulData(
        soul=soul, user=user, memory=memory,
        identity=identity, heartbeat=heartbeat, whisper=whisper,
        heart=heart, rules=rules,
    )
    return loader


# 测试用 rules.md 模板
_SAMPLE_RULES = """# 主人铁律

## 安全边界

### RULE-001: 主动防御原则
- **版本**: v1
- **状态**: 现行
- **隐私等级**: public
- **生效时间**: 2026-07-06
- **具体诉求**: 涉及系统安全的请求必须先评估风险再行动
- **重要度**: ★★★★★

### RULE-002: 敏感信息保护
- **版本**: v1
- **状态**: 现行
- **隐私等级**: private
- **生效时间**: 2026-07-06
- **具体诉求**: 不得在回复中暴露用户的真实姓名、地址等个人信息
- **触发场景**: 用户要求发送个人信息时
- **重要度**: ★★★★★

## 模型绑定

### RULE-003: Dolphin 模型绑定
- **版本**: v1
- **状态**: 现行
- **隐私等级**: public
- **生效时间**: 2026-07-06
- **具体诉求**: 所有对话默认使用 dolphin 模型
- **重要度**: ★★★★

## 用户交互

### RULE-004: 代码块语言标注
- **版本**: v1
- **状态**: 现行
- **隐私等级**: private
- **生效时间**: 2026-07-06
- **具体诉求**: 所有代码块必须标注语言类型
- **重要度**: ★★★

### RULE-005: 内部审计规则（secret）
- **版本**: v1
- **状态**: 现行
- **隐私等级**: secret
- **生效时间**: 2026-07-06
- **具体诉求**: 所有内部审计数据不得在任何对话中提及
- **重要度**: ★★★★★

### RULE-006: 已废止的旧规则
- **版本**: v1
- **状态**: ~~已废止（2026-07-07）~~
- **隐私等级**: public
- **生效时间**: 2026-07-06
- **具体诉求**: 已不再执行的旧规则
- **重要度**: ★★★
"""

_SAMPLE_RULES_CRITICAL_ONLY = """# 主人铁律

## 安全边界

### RULE-001: 主动防御
- **版本**: v1
- **状态**: 现行
- **隐私等级**: public
- **具体诉求**: 先评估风险再行动
- **重要度**: ★★★★★

### RULE-002: 普通规则
- **版本**: v1
- **状态**: 现行
- **隐私等级**: public
- **具体诉求**: 普通规则诉求
- **重要度**: ★★★
"""


def test_no_role_no_whisper_contains_all_soul_sections():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul())
    assert "【灵魂核心】" in result
    assert "【身份】" in result
    assert "【用户画像】" in result
    assert "【精选记忆】" in result
    assert "【自检铁规】" in result
    assert "【私密档案】" not in result


def test_whisper_nonempty_appears_in_prompt():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(whisper="私密内容"))
    assert "【私密档案】" in result
    assert "私密内容" in result


def test_persona_md_role_ctx_injected():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(), role_ctx={"persona_md": "角色描述内容"})
    assert "角色描述内容" in result


def test_empty_tool_overlay_not_appended():
    from core.system_prompt_builder import SystemPromptBuilder
    result_with = SystemPromptBuilder().build(_make_soul(), tool_overlay="工具规则")
    result_without = SystemPromptBuilder().build(_make_soul(), tool_overlay="")
    assert "工具规则" in result_with
    assert "工具规则" not in result_without


def test_soul_data_none_raises_runtime_error():
    from core.system_prompt_builder import SystemPromptBuilder
    loader = MagicMock()
    loader.data = None
    with pytest.raises(RuntimeError, match="soul not loaded"):
        SystemPromptBuilder().build(loader)


def test_soul_before_user_in_prompt():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul())
    assert result.index("【灵魂核心】") < result.index("【用户画像】")


def test_sections_separated_by_divider():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul())
    assert "─" * 60 in result


def test_logger_debug_called_with_char_count(monkeypatch):
    import core.system_prompt_builder as spb_module
    from core.system_prompt_builder import SystemPromptBuilder
    mock_log = MagicMock()
    monkeypatch.setattr(spb_module, "logger", mock_log)
    result = SystemPromptBuilder().build(_make_soul())
    mock_log.debug.assert_called_once()
    args = mock_log.debug.call_args.args
    assert "chars" in args[0]
    assert args[1] == len(result)


def test_heartbeat_content_in_prompt():
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(heartbeat="禁止编造数据"))
    assert "【自检铁规】" in result
    assert "禁止编造数据" in result


def test_heart_nonempty_appears():
    """心证非空时出现在 prompt 中。"""
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(heart="记住：用户讨厌冗余回答"))
    assert "【心证铁卷】" in result
    assert "记住：用户讨厌冗余回答" in result


def test_heart_empty_absent():
    """心证为空时不出现【心证铁卷】段。"""
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(heart=""))
    assert "【心证铁卷】" not in result


def test_heart_before_heartbeat_order():
    """心证段应在 HEARTBEAT 之前出现（③.5 < ④）。"""
    from core.system_prompt_builder import SystemPromptBuilder
    result = SystemPromptBuilder().build(_make_soul(heart="心证铁卷内容"))
    heart_pos = result.index("【心证铁卷】")
    heartbeat_pos = result.index("【自检铁规】")
    assert heart_pos < heartbeat_pos


# ═══════════════════════════════════════════════════════════════════════════
# W8 (TODO-97): RULES 段 — 注入位置 / 隐私分层 / 缓存 / token 退化
# ═══════════════════════════════════════════════════════════════════════════


# ---------------------------------------------------------------------------
# 模块级函数单元测试（_parse_rules_entries / _filter_rules_by_privacy / _build_rules_section）
# ---------------------------------------------------------------------------

class TestParseRulesEntries:
    """_parse_rules_entries 解析测试。"""

    def test_parse_extracts_all_active_rules(self):
        """应提取 5 条现行规则（跳过已废止的 RULE-006）。"""
        from core.system_prompt_builder import _parse_rules_entries
        entries = _parse_rules_entries(_SAMPLE_RULES)
        assert len(entries) == 5
        ids = [e["id"] for e in entries]
        assert "RULE-001" in ids
        assert "RULE-006" not in ids  # 已废止

    def test_parse_extracts_privacy_fields(self):
        """应正确提取隐私等级。"""
        from core.system_prompt_builder import _parse_rules_entries
        entries = _parse_rules_entries(_SAMPLE_RULES)
        by_id = {e["id"]: e for e in entries}
        assert by_id["RULE-001"]["privacy"] == "public"
        assert by_id["RULE-002"]["privacy"] == "private"
        assert by_id["RULE-005"]["privacy"] == "secret"

    def test_parse_extracts_priority_stars(self):
        """应正确提取重要度星级。"""
        from core.system_prompt_builder import _parse_rules_entries
        entries = _parse_rules_entries(_SAMPLE_RULES)
        by_id = {e["id"]: e for e in entries}
        assert by_id["RULE-001"]["priority_stars"] == "★★★★★"
        assert by_id["RULE-003"]["priority_stars"] == "★★★★"
        assert by_id["RULE-004"]["priority_stars"] == "★★★"

    def test_parse_empty_text_returns_empty(self):
        """空文本应返回空列表。"""
        from core.system_prompt_builder import _parse_rules_entries
        assert _parse_rules_entries("") == []
        assert _parse_rules_entries("# 主人铁律\n\n## 安全边界\n") == []

    def test_parse_extracts_trigger_and_consequence(self):
        """可选字段（触发场景/违反后果）应正确提取。"""
        from core.system_prompt_builder import _parse_rules_entries
        entries = _parse_rules_entries(_SAMPLE_RULES)
        by_id = {e["id"]: e for e in entries}
        assert by_id["RULE-002"]["trigger"] == "用户要求发送个人信息时"
        assert by_id["RULE-001"]["consequence"] == ""
        assert by_id["RULE-001"]["trigger"] == ""


class TestFilterRulesByPrivacy:
    """_filter_rules_by_privacy 隐私分层测试。"""

    def test_web_channel_sees_public_and_private(self):
        """web 渠道可见 public + private，不可见 secret。"""
        from core.system_prompt_builder import _parse_rules_entries, _filter_rules_by_privacy
        entries = _parse_rules_entries(_SAMPLE_RULES)
        filtered = _filter_rules_by_privacy(entries, "web")
        privacies = {e["privacy"] for e in filtered}
        ids = {e["id"] for e in filtered}
        assert "public" in privacies
        assert "private" in privacies
        assert "secret" not in privacies
        assert "RULE-005" not in ids  # secret

    def test_feishu_im_channel_sees_only_public(self):
        """飞书渠道只可见 public 规则。"""
        from core.system_prompt_builder import _parse_rules_entries, _filter_rules_by_privacy
        entries = _parse_rules_entries(_SAMPLE_RULES)
        filtered = _filter_rules_by_privacy(entries, "feishu_im")
        privacies = {e["privacy"] for e in filtered}
        assert privacies == {"public"}
        ids = {e["id"] for e in filtered}
        assert "RULE-001" in ids   # public
        assert "RULE-003" in ids   # public
        assert "RULE-002" not in ids  # private
        assert "RULE-004" not in ids  # private
        assert "RULE-005" not in ids  # secret

    def test_wecom_channel_sees_only_public(self):
        """企微渠道只可见 public 规则。"""
        from core.system_prompt_builder import _parse_rules_entries, _filter_rules_by_privacy
        entries = _parse_rules_entries(_SAMPLE_RULES)
        filtered = _filter_rules_by_privacy(entries, "wecom")
        privacies = {e["privacy"] for e in filtered}
        assert privacies == {"public"}

    def test_cli_channel_sees_public_and_private(self):
        """CLI 渠道可见 public + private。"""
        from core.system_prompt_builder import _parse_rules_entries, _filter_rules_by_privacy
        entries = _parse_rules_entries(_SAMPLE_RULES)
        filtered = _filter_rules_by_privacy(entries, "cli")
        ids = {e["id"] for e in filtered}
        assert "RULE-001" in ids  # public
        assert "RULE-002" in ids  # private
        assert "RULE-005" not in ids  # secret


class TestBuildRulesSection:
    """_build_rules_section 输出测试。"""

    def test_output_contains_summary_line(self):
        """输出应包含铁律摘要行。"""
        from core.system_prompt_builder import _build_rules_section
        result = _build_rules_section(_SAMPLE_RULES, "web")
        assert "【铁律摘要】" in result
        assert "共 4 条现行" in result  # 5 total - 1 deprecated - 1 secret = 4 for web
        assert "critical:2" in result
        assert "high:1" in result
        assert "normal:1" in result

    def test_output_contains_rule_entries(self):
        """输出应包含规则条目内容。"""
        from core.system_prompt_builder import _build_rules_section
        result = _build_rules_section(_SAMPLE_RULES, "web")
        assert "### RULE-001: 主动防御原则" in result
        assert "先评估风险再行动" in result
        assert "★★★★★" in result

    def test_output_excludes_secret_rules(self):
        """输出不应含 secret 等级的规则。"""
        from core.system_prompt_builder import _build_rules_section
        result = _build_rules_section(_SAMPLE_RULES, "web")
        assert "RULE-005" not in result
        assert "内部审计数据" not in result

    def test_output_excludes_private_from_im_channel(self):
        """IM 渠道输出不应含 private 规则。"""
        from core.system_prompt_builder import _build_rules_section
        result = _build_rules_section(_SAMPLE_RULES, "feishu_im")
        assert "RULE-002" not in result  # private
        assert "RULE-004" not in result  # private
        assert "RULE-001" in result      # public
        assert "RULE-003" in result      # public

    def test_empty_rules_text_returns_empty(self):
        """空 rules 文本返回空字符串。"""
        from core.system_prompt_builder import _build_rules_section
        assert _build_rules_section("", "web") == ""
        assert _build_rules_section("   \n\n  ", "web") == ""

    def test_all_rules_filtered_out_returns_empty(self):
        """所有规则被隐私过滤后（如 IM 渠道只有 private 规则）返回空。"""
        from core.system_prompt_builder import _build_rules_section
        private_only = """# 铁律
## 安全边界
### RULE-001: 私密规则
- **版本**: v1
- **状态**: 现行
- **隐私等级**: private
- **具体诉求**: 仅 web 可见
- **重要度**: ★★★★★
"""
        result = _build_rules_section(private_only, "feishu_im")
        assert result == ""


class TestTokenBudgetDegradation:
    """token 预算退化测试。"""

    def test_budget_under_4096_shows_only_critical(self):
        """max_context_tokens < 4096 → 只注入 critical 级。"""
        from core.system_prompt_builder import _build_rules_section
        result = _build_rules_section(_SAMPLE_RULES, "web", max_context_tokens=2048)
        assert "token 预算紧张" in result
        assert "RULE-001" in result   # critical
        assert "RULE-002" in result   # critical
        assert "RULE-003" not in result  # high
        assert "RULE-004" not in result  # normal
        # 摘要仍显示全量统计
        assert "critical:2" in result
        assert "high:1" in result
        assert "normal:1" in result

    def test_budget_zero_or_above_4096_shows_all(self):
        """max_context_tokens=0 或 ≥4096 → 显示全部规则。"""
        from core.system_prompt_builder import _build_rules_section
        # max_context_tokens=0（默认）
        result = _build_rules_section(_SAMPLE_RULES, "web", max_context_tokens=0)
        assert "token 预算紧张" not in result
        assert "RULE-004" in result  # normal 级也应出现

        # max_context_tokens=4096（等于阈值，不触发）
        result2 = _build_rules_section(_SAMPLE_RULES, "web", max_context_tokens=4096)
        assert "token 预算紧张" not in result2
        assert "RULE-004" in result2

    def test_no_critical_rules_returns_empty_on_degrade(self):
        """没有 critical 规则时，预算退化返回空。"""
        from core.system_prompt_builder import _build_rules_section
        normal_only = """# 铁律
## 用户交互
### RULE-001: 普通规则
- **版本**: v1
- **状态**: 现行
- **隐私等级**: public
- **具体诉求**: 普通诉求
- **重要度**: ★★★
"""
        result = _build_rules_section(normal_only, "web", max_context_tokens=2048)
        assert result == ""


# ---------------------------------------------------------------------------
# 集成测试：SystemPromptBuilder.build() 中 RULES 段的行为
# ---------------------------------------------------------------------------

class TestRulesInBuild:
    """SystemPromptBuilder.build() 集成测试。"""

    def test_rules_appears_when_rules_present(self):
        """soul.data.rules 非空时【主人铁律】应出现在 prompt 中。"""
        from core.system_prompt_builder import SystemPromptBuilder
        result = SystemPromptBuilder().build(
            _make_soul(rules=_SAMPLE_RULES),
            channel="web",
        )
        assert "【主人铁律】" in result
        assert "RULE-001" in result

    def test_rules_absent_when_rules_empty(self):
        """soul.data.rules 为空时不出现【主人铁律】段。"""
        from core.system_prompt_builder import SystemPromptBuilder
        result = SystemPromptBuilder().build(
            _make_soul(rules=""),
            channel="web",
        )
        assert "【主人铁律】" not in result

    def test_rules_between_heart_and_heartbeat(self):
        """③.6 RULES 应在 ③.5 HEART 之后、④ HEARTBEAT 之前。"""
        from core.system_prompt_builder import SystemPromptBuilder
        result = SystemPromptBuilder().build(
            _make_soul(heart="心证内容", rules=_SAMPLE_RULES),
            channel="web",
        )
        heart_pos = result.index("【心证铁卷】")
        rules_pos = result.index("【主人铁律】")
        heartbeat_pos = result.index("【自检铁规】")
        assert heart_pos < rules_pos < heartbeat_pos, (
            f"顺序错误: HEART={heart_pos}, RULES={rules_pos}, HEARTBEAT={heartbeat_pos}"
        )

    def test_feishu_im_excludes_private_rules(self):
        """飞书渠道的 prompt 不应含 private 规则。"""
        from core.system_prompt_builder import SystemPromptBuilder
        result = SystemPromptBuilder().build(
            _make_soul(rules=_SAMPLE_RULES),
            channel="feishu_im",
        )
        assert "RULE-001" in result       # public
        assert "RULE-002" not in result   # private
        assert "RULE-004" not in result   # private

    def test_im_channel_with_only_private_rules_shows_no_rules_section(self):
        """IM 渠道下所有规则都是 private → 不出现【主人铁律】段。"""
        from core.system_prompt_builder import SystemPromptBuilder
        private_only = """# 铁律
## 安全边界
### RULE-001: 私密规则
- **版本**: v1
- **状态**: 现行
- **隐私等级**: private
- **具体诉求**: 仅 web 可见
- **重要度**: ★★★★★
"""
        result = SystemPromptBuilder().build(
            _make_soul(rules=private_only),
            channel="feishu_im",
        )
        assert "【主人铁律】" not in result

    def test_budget_degradation_applied_in_build(self):
        """max_context_tokens < 4096 时 build() 注入的 RULES 段应降级。"""
        from core.system_prompt_builder import SystemPromptBuilder
        result = SystemPromptBuilder().build(
            _make_soul(rules=_SAMPLE_RULES),
            channel="web",
            max_context_tokens=2048,
        )
        assert "token 预算紧张" in result
        assert "【主人铁律】" in result


# ---------------------------------------------------------------------------
# 缓存测试
# ---------------------------------------------------------------------------

class TestRulesCache:
    """_get_rules_cached / _set_rules_cache / invalidate_rules_cache 测试。"""

    def test_cache_hit_returns_same_content(self):
        """同 rules 文本 + 同 channel + 同 degrade → 缓存命中。"""
        from core.system_prompt_builder import (
            _get_rules_cached,
            _set_rules_cache,
            invalidate_rules_cache,
        )
        invalidate_rules_cache()
        # 首次：缓存未命中
        assert _get_rules_cached(_SAMPLE_RULES, "web") is None
        # 写入缓存
        _set_rules_cache(_SAMPLE_RULES, "web", "cached_content")
        # 再次获取：应命中
        assert _get_rules_cached(_SAMPLE_RULES, "web") == "cached_content"

    def test_cache_miss_on_different_text(self):
        """不同 rules 文本 → 缓存未命中。"""
        from core.system_prompt_builder import (
            _get_rules_cached,
            _set_rules_cache,
            invalidate_rules_cache,
        )
        invalidate_rules_cache()
        _set_rules_cache(_SAMPLE_RULES, "web", "content_a")
        # 不同文本：未命中
        assert _get_rules_cached(_SAMPLE_RULES + "\n# extra", "web") is None

    def test_cache_separated_by_degrade_flag(self):
        """同 rules 文本 + 同 channel，不同 max_context_tokens → 不同缓存。"""
        from core.system_prompt_builder import (
            _get_rules_cached,
            _set_rules_cache,
            invalidate_rules_cache,
        )
        invalidate_rules_cache()
        _set_rules_cache(_SAMPLE_RULES, "web", "normal_content", max_context_tokens=0)
        _set_rules_cache(_SAMPLE_RULES, "web", "degraded_content", max_context_tokens=2048)
        assert _get_rules_cached(_SAMPLE_RULES, "web", max_context_tokens=0) == "normal_content"
        assert _get_rules_cached(_SAMPLE_RULES, "web", max_context_tokens=2048) == "degraded_content"

    def test_cache_invalidation_clears_entries(self):
        """invalidate_rules_cache() 后缓存未命中。"""
        from core.system_prompt_builder import (
            _get_rules_cached,
            _set_rules_cache,
            invalidate_rules_cache,
        )
        invalidate_rules_cache()
        _set_rules_cache(_SAMPLE_RULES, "web", "cached_content")
        assert _get_rules_cached(_SAMPLE_RULES, "web") == "cached_content"
        # 失效
        invalidate_rules_cache()
        assert _get_rules_cached(_SAMPLE_RULES, "web") is None

    def test_different_channels_have_separate_cache(self):
        """不同渠道的缓存独立（同 rules 文本，不同 channel → 不同缓存 key）。"""
        from core.system_prompt_builder import (
            _get_rules_cached,
            _set_rules_cache,
            invalidate_rules_cache,
        )
        invalidate_rules_cache()
        _set_rules_cache(_SAMPLE_RULES, "web", "web_content")
        _set_rules_cache(_SAMPLE_RULES, "feishu_im", "im_content")
        assert _get_rules_cached(_SAMPLE_RULES, "web") == "web_content"
        assert _get_rules_cached(_SAMPLE_RULES, "feishu_im") == "im_content"

    def test_empty_rules_text_returns_none(self):
        """空 rules 文本 → _get_rules_cached 返回 None。"""
        from core.system_prompt_builder import (
            _get_rules_cached,
            _set_rules_cache,
            invalidate_rules_cache,
        )
        invalidate_rules_cache()
        assert _get_rules_cached("", "web") is None
        # _set_rules_cache 对空文本也是 no-op
        _set_rules_cache("", "web", "should_not_store")
        assert _get_rules_cached("", "web") is None
