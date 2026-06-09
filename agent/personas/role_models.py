"""
角色配置体系 — Pydantic v2 数据模型

持久化边界说明：
  ✅ 持久化到 JSON：CoreIdentity / UserProfile / RoleMemoryConfig（不含 deque）
                    RoleCard / KnowledgeJournalConfig（元数据）/ Commitment
  ⚠️  运行时内存（重启丢失）：ShortTermMemory deque，由 RoleManager._short_term_cache 维护
  ✅ 持久化到 ChromaDB：长期记忆向量 / 日记条目 / 知识库文档
"""
from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, field_validator


# ── 模块一：角色内核 ──────────────────────────────────────────────────────────

class CoreIdentity(BaseModel):
    personality: List[str] = Field(default_factory=list, description="性格标签，如['温柔','理性']")
    principles: List[str] = Field(
        default_factory=list,
        description="核心原则（有序列表，index 越小优先级越高）"
    )
    redlines: List[str] = Field(
        default_factory=list,
        description="绝对底线——任何情况下不可违反，优先级凌驾于所有其他规则之上"
    )
    language_style: str = Field(default="", description="语言风格描述，如'活泼亲切，偶用颜文字'")

    @field_validator("redlines")
    @classmethod
    def strip_empty_redlines(cls, v: List[str]) -> List[str]:
        return [r.strip() for r in v if r.strip()]


# ── 模块二：用户画像 ──────────────────────────────────────────────────────────

class UserProfile(BaseModel):
    nickname: str = Field(default="用户", description="用户昵称")
    background: str = Field(default="", description="用户背景简述，如职业、兴趣")
    preferences: Dict[str, str] = Field(
        default_factory=lambda: {"tone": "casual", "detail": "moderate"},
        description="沟通偏好键值对，如 {'tone':'formal','detail':'brief'}"
    )
    relationship: str = Field(default="朋友", description="与角色的关系设定，如'闺蜜''导师'")
    disclosed_info: List[str] = Field(
        default_factory=list,
        description="用户主动透露的信息片段，如['住在上海','养了一只猫']"
    )


# ── 模块三：角色记忆（可序列化部分） ─────────────────────────────────────────

class Commitment(BaseModel):
    """角色对用户的承诺记录"""
    content: str = Field(..., description="承诺内容")
    timestamp: str = Field(default_factory=lambda: datetime.now().isoformat())
    status: str = Field(default="active", description="active | fulfilled | cancelled")

    @field_validator("status")
    @classmethod
    def validate_status(cls, v: str) -> str:
        if v not in {"active", "fulfilled", "cancelled"}:
            raise ValueError("status 必须是 active / fulfilled / cancelled 之一")
        return v


class RetrievalRule(BaseModel):
    """长期记忆检索规则"""
    similarity_threshold: float = Field(default=0.7, ge=0.0, le=1.0, description="语义相似度阈值")
    top_k: int = Field(default=5, ge=1, le=20, description="最大召回条数")
    recency_weight: float = Field(
        default=0.3, ge=0.0, le=1.0,
        description="时间近似性权重，越高越倾向召回近期记忆"
    )


class RoleMemoryConfig(BaseModel):
    """
    角色记忆配置（可序列化部分）。
    ⚠️  short_term deque 是纯运行时状态，重启后清空，
        由 RoleManager._short_term_cache[role_id] 维护，不在此模型内。
    """
    commitments: List[Commitment] = Field(default_factory=list)
    retrieval_rule: RetrievalRule = Field(default_factory=RetrievalRule)
    short_term_size: int = Field(default=20, ge=1, le=200, description="短期记忆 deque 最大长度")


# ── 模块四：角色名片 ──────────────────────────────────────────────────────────

class RoleCard(BaseModel):
    name: str = Field(..., min_length=1, description="角色名称")
    avatar_url: str = Field(default="", description="头像 URL 或 data:image/... base64 字符串")
    signature: str = Field(default="", description="一句话签名")
    tags: List[str] = Field(default_factory=list, description="标签，如['AI','陪伴','知识']")


# ── 模块五：知识与日记 ────────────────────────────────────────────────────────

class JournalEntry(BaseModel):
    """角色日记条目（完整数据存 ChromaDB，此处仅作为传输/缓存结构）"""
    date: str = Field(default_factory=lambda: datetime.now().strftime("%Y-%m-%d"))
    content: str = Field(..., description="日记正文")


class KnowledgeJournalConfig(BaseModel):
    """
    知识与日记配置。
    ✅ 完整日记内容和知识文档持久化在 ChromaDB collection:
       - role_{role_id}_journal
       - role_{role_id}_knowledge
    此处仅缓存最近日记条目供快速注入 prompt，避免每次请求都查 DB。
    """
    recent_journal_entries: List[JournalEntry] = Field(
        default_factory=list,
        description="最近日记缓存（仅最新 N 条，完整数据在 ChromaDB）"
    )
    retrieval_strategy: str = Field(
        default="semantic_similarity",
        description="检索策略：semantic_similarity | keyword | hybrid"
    )
    knowledge_sources: List[str] = Field(
        default_factory=list,
        description="已导入的知识来源标签"
    )


# ── 根模型 ────────────────────────────────────────────────────────────────────

class RoleConfig(BaseModel):
    """完整角色配置（根模型，可完整序列化为 JSON）"""
    role_id: str = Field(..., description="角色唯一标识符，如 'assistant_01'")
    core_identity: CoreIdentity = Field(default_factory=CoreIdentity)
    user_profile: UserProfile = Field(default_factory=UserProfile)
    role_memory: RoleMemoryConfig = Field(default_factory=RoleMemoryConfig)
    role_card: RoleCard
    knowledge_journal: KnowledgeJournalConfig = Field(default_factory=KnowledgeJournalConfig)
    created_at: str = Field(default_factory=lambda: datetime.now().isoformat())
    updated_at: str = Field(default_factory=lambda: datetime.now().isoformat())
