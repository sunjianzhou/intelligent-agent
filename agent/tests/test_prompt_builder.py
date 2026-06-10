"""Tests for PromptBuilder — all 9 sections + edge cases."""
import sys
import os
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from personas.prompt_builder import PromptBuilder
from personas.role_models import (
    RoleConfig, CoreIdentity, UserProfile, RoleMemoryConfig,
    RoleCard, KnowledgeJournalConfig, Commitment,
)


# ── Fixtures ──────────────────────────────────────────────────────────────────

def _minimal_role(name="TestBot", role_id="r001") -> RoleConfig:
    return RoleConfig(
        role_id=role_id,
        role_card=RoleCard(name=name, signature=""),
    )


def _full_role() -> RoleConfig:
    return RoleConfig(
        role_id="full",
        core_identity=CoreIdentity(
            personality=["温柔", "理性"],
            principles=["诚实第一", "尊重用户"],
            redlines=["不讨论违法内容", "不扮演真实人物"],
            language_style="活泼亲切",
        ),
        user_profile=UserProfile(
            nickname="小明",
            background="软件工程师",
            preferences={"tone": "casual"},
            relationship="好友",
            disclosed_info=["住在北京", "养了一只猫"],
        ),
        role_memory=RoleMemoryConfig(
            commitments=[
                Commitment(content="记住用户生日", status="active"),
                Commitment(content="已兑现的承诺", status="fulfilled"),
            ]
        ),
        role_card=RoleCard(name="Luna", signature="永远陪在你身边"),
    )


def _make_ctx(role, short_term=None, long_term=None, journal=None, knowledge=None):
    return {
        "role_config": role,
        "short_term_memories": short_term or [],
        "long_term_memories": long_term or [],
        "journal_snippets": journal or [],
        "knowledge_snippets": knowledge or [],
        "query": "test",
    }


# ── 边界 ──────────────────────────────────────────────────────────────────────

def test_empty_context_returns_fallback():
    pb = PromptBuilder()
    result = pb.build_system_prompt({})
    assert "助手" in result


def test_none_context_via_empty_dict():
    pb = PromptBuilder()
    result = pb.build_system_prompt({})
    assert isinstance(result, str) and len(result) > 0


# ── Section 1: 绝对底线 ───────────────────────────────────────────────────────

def test_redlines_appear_first():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    # 底线段应在核心身份段之前
    idx_redline = prompt.find("绝对底线")
    idx_identity = prompt.find("## 角色：")
    assert idx_redline < idx_identity


def test_redlines_all_present():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "不讨论违法内容" in prompt
    assert "不扮演真实人物" in prompt


def test_no_redlines_section_absent():
    pb = PromptBuilder()
    role = _minimal_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "绝对底线" not in prompt


# ── Section 2: 核心身份 ───────────────────────────────────────────────────────

def test_core_identity_role_name():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "Luna" in prompt


def test_core_identity_personality():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "温柔" in prompt
    assert "理性" in prompt


def test_core_identity_principles():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "诚实第一" in prompt
    assert "尊重用户" in prompt


def test_core_identity_language_style():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "活泼亲切" in prompt


# ── Section 3: 用户画像 ───────────────────────────────────────────────────────

def test_user_profile_nickname():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "小明" in prompt


def test_user_profile_disclosed_info():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "住在北京" in prompt
    assert "养了一只猫" in prompt


def test_user_profile_preferences():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "casual" in prompt


# ── Section 4: 短期记忆 ───────────────────────────────────────────────────────

def test_short_term_absent_when_empty():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role(), short_term=[])
    prompt = pb.build_system_prompt(ctx)
    assert "近期对话记忆" not in prompt


def test_short_term_present():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role(), short_term=["用户提到了养猫"])
    prompt = pb.build_system_prompt(ctx)
    assert "近期对话记忆" in prompt
    assert "用户提到了养猫" in prompt


def test_short_term_capped_at_10():
    pb = PromptBuilder()
    mems = [f"记忆{i}" for i in range(15)]
    ctx = _make_ctx(_minimal_role(), short_term=mems)
    prompt = pb.build_system_prompt(ctx)
    assert "记忆14" in prompt    # 最后一条
    assert "记忆0" not in prompt  # 超出 10 条的最早条被截断


# ── Section 5: 长期记忆 ───────────────────────────────────────────────────────

def test_long_term_present():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role(), long_term=["用户喜欢喝咖啡"])
    prompt = pb.build_system_prompt(ctx)
    assert "相关长期记忆" in prompt
    assert "用户喜欢喝咖啡" in prompt


def test_long_term_absent_when_empty():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role(), long_term=[])
    prompt = pb.build_system_prompt(ctx)
    assert "相关长期记忆" not in prompt


# ── Section 6: 承诺 ───────────────────────────────────────────────────────────

def test_active_commitment_present():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "记住用户生日" in prompt


def test_fulfilled_commitment_absent():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "已兑现的承诺" not in prompt


# ── Section 7: 日记 ───────────────────────────────────────────────────────────

def test_journal_present():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role(), journal=["今天和用户聊了很多"])
    prompt = pb.build_system_prompt(ctx)
    assert "相关日记" in prompt
    assert "今天和用户聊了很多" in prompt


def test_journal_absent_when_empty():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role())
    prompt = pb.build_system_prompt(ctx)
    assert "相关日记" not in prompt


# ── Section 8: 知识库 ─────────────────────────────────────────────────────────

def test_knowledge_present():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role(), knowledge=["Python 是解释型语言"])
    prompt = pb.build_system_prompt(ctx)
    assert "相关知识" in prompt
    assert "Python 是解释型语言" in prompt


def test_knowledge_absent_when_empty():
    pb = PromptBuilder()
    ctx = _make_ctx(_minimal_role())
    prompt = pb.build_system_prompt(ctx)
    assert "相关知识" not in prompt


# ── Section 9: 签名 ───────────────────────────────────────────────────────────

def test_signature_present():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "永远陪在你身边" in prompt


def test_signature_last():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    # 签名应出现在绝对底线之后
    idx_sig = prompt.rfind("永远陪在你身边")
    idx_red = prompt.find("绝对底线")
    assert idx_sig > idx_red


def test_signature_absent_when_empty():
    pb = PromptBuilder()
    role = _minimal_role()
    ctx = _make_ctx(role)
    prompt = pb.build_system_prompt(ctx)
    assert "---\n*" not in prompt


# ── 综合：所有 section 顺序 ───────────────────────────────────────────────────

def test_section_order_redlines_before_identity():
    pb = PromptBuilder()
    role = _full_role()
    ctx = _make_ctx(role, short_term=["s1"], long_term=["l1"], journal=["j1"], knowledge=["k1"])
    prompt = pb.build_system_prompt(ctx)
    positions = {
        "redline": prompt.find("绝对底线"),
        "identity": prompt.find("## 角色："),
        "user_profile": prompt.find("## 你正在与之交谈"),
        "short_term": prompt.find("近期对话记忆"),
        "long_term": prompt.find("相关长期记忆"),
        "commitment": prompt.find("对用户的承诺"),
        "journal": prompt.find("相关日记"),
        "knowledge": prompt.find("相关知识"),
        "signature": prompt.find("永远陪在你身边"),
    }
    ordered = sorted(positions.items(), key=lambda x: x[1])
    names = [k for k, v in ordered if v >= 0]
    assert names[0] == "redline"
    assert names[-1] == "signature"
