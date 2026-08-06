"""
角色管理器 — 负责角色配置的持久化、记忆读写和上下文组装。

存储层说明：
  JSON 文件  → 角色配置（CoreIdentity / UserProfile / RoleCard 等静态信息）
  ChromaDB   → 长期记忆向量 / 日记条目 / 知识库文档
  内存 deque → 短期对话摘要（⚠️ 重启后丢失，不持久化）
"""
from __future__ import annotations

import json
import os
import uuid
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from typing import Any, Deque, Dict, List, Literal, Optional

import chromadb
from chromadb.utils import embedding_functions
from loguru import logger

from personas.role_models import (
    Commitment,
    JournalEntry,
    RoleConfig,
    RoleMemoryConfig,
    UserProfile,
)


# ChromaDB 嵌入函数（与 memory/long_term.py 保持一致）
_EMBEDDING_MODEL = "all-MiniLM-L6-v2"
_DEFAULT_ROLES_DIR = os.path.join(os.path.dirname(__file__), "..", "data", "roles")
_DEFAULT_CHROMA_DIR = os.path.join(os.path.dirname(__file__), "..", "chroma-data")


class RoleManager:
    """
    角色管理器：统一管理角色配置的 CRUD、记忆读写、上下文组装。

    初始化参数：
      roles_dir   — 角色 JSON 文件存储目录（每个角色一个文件）
      chroma_dir  — ChromaDB 持久化目录
    """

    def __init__(
        self,
        roles_dir: str = _DEFAULT_ROLES_DIR,
        chroma_dir: str = _DEFAULT_CHROMA_DIR,
    ) -> None:
        os.makedirs(roles_dir, exist_ok=True)
        self.roles_dir = roles_dir

        # ChromaDB 客户端（与现有 LongTermMemory 共享同一目录）
        self._chroma = chromadb.PersistentClient(path=chroma_dir)
        # 判断是否使用 chromadb 内置 embedding（Docker 环境无 sentence_transformers，
        # 与 memory/long_term.py 的降级逻辑保持一致）
        try:
            import sentence_transformers  # noqa
            self._embed_fn = embedding_functions.SentenceTransformerEmbeddingFunction(
                model_name=_EMBEDDING_MODEL
            )
        except ImportError:
            logger.info("sentence_transformers 不可用，RoleManager 使用 chromadb 内置 embedding")
            self._embed_fn = embedding_functions.DefaultEmbeddingFunction()

        # ⚠️ 运行时短期记忆缓存（重启后清空）
        # 结构：{role_id: deque[str]}，每个字符串是一条对话摘要
        self._short_term_cache: Dict[str, Deque[str]] = {}

        logger.info(f"RoleManager 初始化完成，roles_dir={roles_dir}")

    # ── 基础 CRUD ─────────────────────────────────────────────────────────────

    def load_role(self, role_id: str) -> Optional[RoleConfig]:
        """从 JSON 文件加载角色配置，不存在返回 None。"""
        path = self._role_path(role_id)
        if not os.path.exists(path):
            logger.warning(f"角色文件不存在: {path}")
            return None
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        role = RoleConfig.model_validate(data)
        # 初始化短期记忆 deque（若本次运行尚未创建）
        self._ensure_short_term(role_id, role.role_memory.short_term_size)
        logger.debug(f"已加载角色: {role_id}")
        return role

    def save_role(self, role_config: RoleConfig) -> None:
        """将角色配置持久化到 JSON 文件（覆盖写入）。"""
        role_config.updated_at = datetime.now().isoformat()
        path = self._role_path(role_config.role_id)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(role_config.model_dump(), f, ensure_ascii=False, indent=2)
        logger.info(f"角色已保存: {role_config.role_id} → {path}")

    def list_roles(self) -> List[str]:
        """返回所有已存角色的 role_id 列表。"""
        return [
            fname[:-5]  # strip ".json" suffix
            for fname in os.listdir(self.roles_dir)
            if fname.endswith(".json")
        ]

    def list_role_cards(self) -> List[Dict[str, Any]]:
        """仅读取每个角色 JSON 的 role_card 字段，避免全量 Pydantic 验证。
        返回 [{"role_id": ..., "role_card": {...}}, ...]，用于列表页展示。
        """
        cards = []
        for role_id in self.list_roles():
            path = self._role_path(role_id)
            try:
                with open(path, encoding="utf-8") as f:
                    raw = json.loads(f.read())
                cards.append({
                    "role_id": role_id,
                    "role_card": raw.get("role_card", {}),
                })
            except Exception as _e:
                logger.warning(f"读取角色卡片失败 [{role_id}]: {_e}")
        return cards

    def delete_role(self, role_id: str) -> bool:
        """删除角色配置文件及其所有 ChromaDB collections。"""
        path = self._role_path(role_id)
        deleted = False
        if os.path.exists(path):
            os.remove(path)
            deleted = True
        for suffix in ("memory", "journal", "knowledge"):
            coll_name = f"role_{role_id}_{suffix}"
            try:
                self._chroma.delete_collection(coll_name)
            except Exception:
                pass
        self._short_term_cache.pop(role_id, None)
        logger.info(f"角色已删除: {role_id}")
        return deleted

    # ── 用户画像更新 ──────────────────────────────────────────────────────────

    def update_user_profile(self, role_id: str, new_info: Dict[str, Any]) -> Optional[RoleConfig]:
        """
        更新用户画像（支持局部更新）。
        new_info 可包含 UserProfile 的任意字段，未传字段保持原值。
        """
        role = self.load_role(role_id)
        if role is None:
            return None

        current = role.user_profile.model_dump()
        # 特殊处理：disclosed_info 追加而非覆盖
        if "disclosed_info" in new_info:
            existing = set(current.get("disclosed_info", []))
            existing.update(new_info["disclosed_info"])
            new_info["disclosed_info"] = list(existing)
        # 特殊处理：preferences 合并
        if "preferences" in new_info:
            current_prefs = current.get("preferences", {})
            current_prefs.update(new_info["preferences"])
            new_info["preferences"] = current_prefs

        current.update(new_info)
        role.user_profile = UserProfile.model_validate(current)
        self.save_role(role)
        logger.info(f"已更新用户画像: {role_id}, 字段={list(new_info.keys())}")
        return role

    # ── 记忆写入 ──────────────────────────────────────────────────────────────

    def add_memory(
        self,
        role_id: str,
        content: str,
        memory_type: Literal["short_term", "long_term"] = "short_term",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        """
        写入记忆。
        memory_type="short_term" → 写入内存 deque（重启丢失）
        memory_type="long_term"  → 写入 ChromaDB role_{role_id}_memory collection
        """
        if memory_type == "short_term":
            # deque 已存在时直接写，避免为读取 short_term_size 而 load 整个 JSON
            if role_id not in self._short_term_cache:
                role = self.load_role(role_id)
                size = role.role_memory.short_term_size if role else 20
                self._ensure_short_term(role_id, size)

        if memory_type == "short_term":
            # ⚠️ 运行时内存，重启后清空
            self._short_term_cache[role_id].append(content)
            logger.debug(f"短期记忆写入: {role_id}, 当前条数={len(self._short_term_cache[role_id])}")

        else:  # long_term
            coll = self._get_collection(f"role_{role_id}_memory")
            doc_id = f"mem_{uuid.uuid4().hex[:8]}"
            meta = {
                "role_id": role_id,
                "timestamp": datetime.now().isoformat(),
                "type": "long_term_memory",
            }
            if metadata:
                meta.update(metadata)
            coll.add(documents=[content], metadatas=[meta], ids=[doc_id])
            logger.debug(f"长期记忆写入 ChromaDB: {role_id}, id={doc_id}")

    def add_commitment(self, role_id: str, content: str) -> Optional[RoleConfig]:
        """追加一条承诺记录并持久化。"""
        role = self.load_role(role_id)
        if role is None:
            return None
        role.role_memory.commitments.append(
            Commitment(content=content, timestamp=datetime.now().isoformat())
        )
        self.save_role(role)
        return role

    # ── 日记 & 知识库写入 ─────────────────────────────────────────────────────

    def add_journal_entry(self, role_id: str, content: str, date: Optional[str] = None) -> None:
        """写入日记条目到 ChromaDB，同时更新 JSON 中的近期缓存。"""
        entry = JournalEntry(
            date=date or datetime.now().strftime("%Y-%m-%d"),
            content=content,
        )
        # 写 ChromaDB
        coll = self._get_collection(f"role_{role_id}_journal")
        doc_id = f"journal_{entry.date}_{uuid.uuid4().hex[:6]}"
        coll.add(
            documents=[content],
            metadatas=[{"role_id": role_id, "date": entry.date, "type": "journal"}],
            ids=[doc_id],
        )
        # 更新 JSON 近期缓存（保留最新 10 条）
        role = self.load_role(role_id)
        if role:
            role.knowledge_journal.recent_journal_entries.append(entry)
            role.knowledge_journal.recent_journal_entries = (
                role.knowledge_journal.recent_journal_entries[-10:]
            )
            self.save_role(role)
        logger.debug(f"日记写入: {role_id}, date={entry.date}")

    def add_knowledge(
        self,
        role_id: str,
        content: str,
        source: str = "manual",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        """向知识库写入一段文档。"""
        coll = self._get_collection(f"role_{role_id}_knowledge")
        doc_id = f"knowledge_{uuid.uuid4().hex[:8]}"
        meta = {
            "role_id": role_id,
            "source": source,
            "timestamp": datetime.now().isoformat(),
            "type": "knowledge",
        }
        if metadata:
            meta.update(metadata)
        coll.add(documents=[content], metadatas=[meta], ids=[doc_id])
        logger.debug(f"知识写入 ChromaDB: {role_id}, source={source}")

    # ── 上下文组装（供 PromptBuilder 消费） ──────────────────────────────────

    def get_role_context(self, role_id: str, query: str) -> Dict[str, Any]:
        """
        返回组装 system prompt 所需的完整上下文字典。

        结构：
          role_config        → RoleConfig 对象（静态模块）
          short_term_memories→ 最近 N 条短期记忆摘要（内存，可能为空）
          long_term_memories → 按语义召回的长期记忆列表
          journal_snippets   → 按语义召回的日记片段列表
          knowledge_snippets → 按语义召回的知识库片段列表
          query              → 当前用户 query（透传给 PromptBuilder）
        """
        role = self.load_role(role_id)
        if role is None:
            return {}

        rule = role.role_memory.retrieval_rule

        # 短期记忆（内存 deque，重启后空）
        short_term = list(self._short_term_cache.get(role_id, []))

        # 长期记忆 / 日记 / 知识库：三路 ChromaDB 查询并行执行
        with ThreadPoolExecutor(max_workers=3) as _exe:
            _f_lt = _exe.submit(
                self._semantic_search,
                f"role_{role_id}_memory", query, rule.top_k, rule.similarity_threshold,
            )
            _f_j = _exe.submit(
                self._semantic_search,
                f"role_{role_id}_journal", query, 3, rule.similarity_threshold,
            )
            _f_k = _exe.submit(
                self._semantic_search,
                f"role_{role_id}_knowledge", query, 3, rule.similarity_threshold,
            )
        long_term = _f_lt.result()
        journal   = _f_j.result()
        knowledge = _f_k.result()

        return {
            "role_config": role,
            "short_term_memories": short_term,
            "long_term_memories": long_term,
            "journal_snippets": journal,
            "knowledge_snippets": knowledge,
            "query": query,
        }

    # ── 私有辅助 ──────────────────────────────────────────────────────────────

    def _role_path(self, role_id: str) -> str:
        return os.path.join(self.roles_dir, f"{role_id}.json")

    def _ensure_short_term(self, role_id: str, max_size: int) -> None:
        """确保 role_id 的短期记忆 deque 已初始化。"""
        if role_id not in self._short_term_cache:
            self._short_term_cache[role_id] = deque(maxlen=max_size)

    def _get_collection(self, name: str) -> chromadb.Collection:
        """获取或创建 ChromaDB collection（cosine 距离，与 LongTermMemory 保持一致）。"""
        return self._chroma.get_or_create_collection(
            name=name,
            embedding_function=self._embed_fn,
            metadata={"hnsw:space": "cosine"},
        )

    def _semantic_search(
        self,
        collection_name: str,
        query: str,
        top_k: int,
        threshold: float,
    ) -> List[str]:
        """语义检索并过滤低于阈值的结果，返回文本列表。"""
        try:
            coll = self._get_collection(collection_name)
            count = coll.count()
            if count == 0:
                return []
            results = coll.query(
                query_texts=[query],
                n_results=min(top_k, count),
                include=["documents", "distances"],
            )
            docs = results.get("documents", [[]])[0]
            distances = results.get("distances", [[]])[0]
            # cosine 空间：distance ∈ [0,1]，similarity = 1 - distance
            filtered = [
                doc
                for doc, dist in zip(docs, distances)
                if (1.0 - dist) >= threshold
            ]
            return filtered
        except Exception as e:
            logger.warning(f"语义检索失败 [{collection_name}]: {e}")
            return []
