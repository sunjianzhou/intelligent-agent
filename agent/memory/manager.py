"""记忆管理器"""
from typing import Dict, Any, List, Optional, Union
from loguru import logger
from memory.base import BaseMemory, MemoryItem, MemoryQuery, MemorySearchResult
from memory.short_term import ShortTermMemory
from memory.long_term import LongTermMemory


class MemoryManager:
    """记忆管理器（整合短期和长期记忆）"""

    def __init__(self,
                 short_term_config: Dict[str, Any] = None,
                 long_term_config: Dict[str, Any] = None):
        from config.settings import settings as _settings
        short_term_config = short_term_config or {
            "name": "short_term",
            "max_size": _settings.short_term_max_size,
            "ttl_hours": _settings.short_term_ttl_hours,
        }

        long_term_config = long_term_config or {
            "name": "long_term",
            "vector_db_type": "chroma",
            "embedding_model": _settings.embedding_model,
            "persist_dir": _settings.chroma_persist_dir,
        }

        # 初始化短期记忆
        self.short_term = ShortTermMemory(**short_term_config)

        # 初始化长期记忆
        self.long_term = LongTermMemory(**long_term_config)

        # 记忆路由配置
        self.memory_routing = {
            "conversation": "short_term",  # 对话上下文
            "knowledge": "long_term",  # 知识
            "fact": "long_term",  # 事实
            "preference": "long_term",  # 用户偏好
            "task": "short_term",  # 任务信息
        }

        logger.info("记忆管理器初始化完成")

    def get_memory_store(self, memory_type: str = "auto") -> BaseMemory:
        """获取记忆存储

        Args:
            memory_type: 记忆类型，可选 "short_term", "long_term", "auto"
        """
        if memory_type == "short_term":
            return self.short_term
        elif memory_type == "long_term":
            return self.long_term
        elif memory_type == "auto":
            # 默认使用长期记忆
            return self.long_term
        else:
            raise ValueError(f"不支持的记忆类型: {memory_type}")

    def route_memory(self, category: str = "knowledge") -> BaseMemory:
        """路由记忆到合适的存储

        Args:
            category: 记忆类别
        """
        store_type = self.memory_routing.get(category, "long_term")
        return self.get_memory_store(store_type)

    def store(self, content: str,
              category: str = "knowledge",
              metadata: Dict[str, Any] = None,
              importance: float = 0.5) -> MemoryItem:
        """存储记忆（自动路由）

        Args:
            content: 记忆内容
            category: 记忆类别，决定存储位置
            metadata: 元数据
            importance: 重要性 (0.0-1.0)
        """
        # 选择存储
        memory_store = self.route_memory(category)

        # 添加类别到元数据
        if metadata is None:
            metadata = {}
        metadata["category"] = category

        # 存储记忆
        memory = memory_store.store(content, metadata, importance)

        logger.debug(f"存储记忆到 {memory_store.name}, 类别: {category}, 内容: {content[:50]}...")
        return memory

    def store_conversation(self, role: str, content: str,
                           metadata: Dict[str, Any] = None,
                           user_id: str = "default") -> MemoryItem:
        """存储对话记忆"""
        conversation_metadata = {
            "type": "conversation",
            "role": role,
            "timestamp": self._get_timestamp(),
            "user_id": user_id,
        }

        if metadata:
            conversation_metadata.update(metadata)

        return self.short_term.store(
            content=content,
            metadata=conversation_metadata,
            importance=0.3  # 对话重要性较低
        )

    def store_knowledge(self, content: str,
                        metadata: Dict[str, Any] = None,
                        importance: float = 0.7) -> MemoryItem:
        """存储知识记忆"""
        knowledge_metadata = {
            "type": "knowledge",
            "source": "conversation",
            "timestamp": self._get_timestamp()
        }

        if metadata:
            knowledge_metadata.update(metadata)

        return self.long_term.store(
            content=content,
            metadata=knowledge_metadata,
            importance=importance
        )

    def store_fact(self, fact: str, source: str = "conversation",
                   importance: float = 0.8) -> MemoryItem:
        """存储事实记忆"""
        return self.long_term.store(
            content=fact,
            metadata={
                "type": "fact",
                "source": source,
                "timestamp": self._get_timestamp()
            },
            importance=importance
        )

    def store_preference(self, preference: str, user_id: str = "default") -> MemoryItem:
        """存储用户偏好"""
        return self.long_term.store(
            content=preference,
            metadata={
                "type": "preference",
                "user_id": user_id,
                "timestamp": self._get_timestamp()
            },
            importance=0.9  # 偏好重要性高
        )

    def retrieve(self, query: Union[str, MemoryQuery],
                 memory_type: str = "auto",
                 category: str = None,
                 limit: int = None) -> List[MemorySearchResult]:
        """检索记忆

        Args:
            query: 查询文本或对象
            memory_type: 记忆类型，"short_term", "long_term", "auto", "both"
            category: 限制记忆类别
            limit: 返回结果数量限制，如果为None则使用query中的limit
        """
        if isinstance(query, str):
            query_obj = MemoryQuery(text=query, limit=limit or 5)
        else:
            query_obj = query
            if limit is not None:
                query_obj.limit = limit

        # 添加类别过滤
        if category:
            if query_obj.metadata_filter is None:
                query_obj.metadata_filter = {}
            query_obj.metadata_filter["category"] = category

        if memory_type == "both":
            # 从两种记忆中都检索
            short_term_results = self.short_term.retrieve(query_obj)
            long_term_results = self.long_term.retrieve(query_obj)

            # 合并结果
            all_results = short_term_results + long_term_results

            # 去重（基于记忆ID）
            seen_ids = set()
            unique_results = []

            for result in all_results:
                if result.memory.id not in seen_ids:
                    seen_ids.add(result.memory.id)
                    unique_results.append(result)

            # 按分数排序
            unique_results.sort(key=lambda x: x.score, reverse=True)

            # 限制数量
            return unique_results[:query_obj.limit]

        elif memory_type == "auto":
            # 默认使用长期记忆
            store = self.long_term
        else:
            store = self.get_memory_store(memory_type)

        return store.retrieve(query_obj)

    def get_recent_conversations(self, limit: int = 10) -> List[MemoryItem]:
        """获取最近的对话"""
        return self.short_term.get_recent(limit)

    def get_important_memories(self, min_importance: float = 0.7,
                               limit: int = 10) -> List[MemoryItem]:
        """获取重要的记忆"""
        # 从两种记忆中获取
        short_term_important = self.short_term.get_by_importance(min_importance, limit)
        long_term_memories = self.long_term.list(limit * 2)

        # 过滤长期记忆中的重要记忆
        long_term_important = [
            m for m in long_term_memories
            if m.importance >= min_importance
        ]

        # 合并并排序
        all_important = short_term_important + long_term_important
        all_important.sort(key=lambda m: m.importance, reverse=True)

        return all_important[:limit]

    def search_relevant_memories(self, query: str, limit: int = 5) -> List[MemorySearchResult]:
        """搜索相关记忆（智能搜索）"""
        # 从两种记忆中都搜索
        return self.retrieve(query, memory_type="both", limit=limit)

    def build_context(self,
                      query: str,
                      current_user_message: str = "",
                      recent_conversations: int = 10,
                      relevant_memories: int = 3,
                      user_id: str = "default") -> Dict[str, Any]:
        """为 query 构建去重的上下文。

        规则：
          - recent: 最近的短期对话（按 user_id 隔离，排除当前轮 user 消息及非 user/assistant 消息）
          - relevant: 仅取长期记忆，并过滤与 recent 内容重叠的项
            （避免短期对话与长期摘要、知识被重复注入）
        """
        # 1) 短期对话历史（按 user_id 过滤，"default" 或 "anonymous" 时不过滤兼容旧数据）
        recent_items_raw = self.short_term.get_recent(recent_conversations * 3)
        if user_id not in ("default", "anonymous"):
            recent_items_raw = [
                m for m in recent_items_raw
                if m.metadata.get("user_id", "default") == user_id
            ]
        recent_items = [
            m for m in recent_items_raw
            if m.metadata.get("role") in ("user", "assistant")
               and m.content != current_user_message
        ][:recent_conversations]

        # 2) 长期记忆相关检索（不再走 both，避免与 recent 冲突）
        lt_query = MemoryQuery(text=query, limit=max(relevant_memories * 2, 5))
        if user_id not in ("default", "anonymous"):
            lt_query.metadata_filter = {"user_id": user_id}
        long_term_results = self.long_term.retrieve(lt_query)

        # 3) 内容级去重：剔除与 recent 文本互相包含的长期记忆
        recent_contents = [m.content.strip() for m in recent_items if m.content]
        deduped: List[MemorySearchResult] = []
        for r in long_term_results:
            c = (r.memory.content or "").strip()
            if not c:
                continue
            if any(c == rc or (len(c) > 5 and (c in rc or rc in c)) for rc in recent_contents):
                continue
            deduped.append(r)
            if len(deduped) >= relevant_memories:
                break

        return {
            "recent_conversations": recent_items,
            "relevant_knowledge": deduped,
        }

    def get_context_for_query(self, query: str,
                              recent_conversations: int = 5,
                              relevant_memories: int = 5) -> str:
        """为查询构建上下文

        Args:
            query: 用户查询
            recent_conversations: 最近的对话数量
            relevant_memories: 相关记忆数量

        Returns:
            格式化后的上下文
        """
        context_parts = []

        # 1. 添加最近的对话
        recent_convos = self.get_recent_conversations(recent_conversations)
        if recent_convos:
            context_parts.append("最近的对话:")
            for i, memory in enumerate(recent_convos, 1):
                role = memory.metadata.get("role", "unknown")
                context_parts.append(f"{i}. [{role}] {memory.content}")

        # 2. 添加相关记忆
        relevant = self.search_relevant_memories(query, relevant_memories)
        if relevant:
            context_parts.append("\n相关记忆:")
            for i, result in enumerate(relevant, 1):
                memory = result.memory
                category = memory.metadata.get("category", "unknown")
                importance = memory.importance
                similarity = result.similarity

                context_parts.append(
                    f"{i}. [{category}] 相关性: {similarity:.2f}, "
                    f"重要性: {importance:.2f} - {memory.content}"
                )

        return "\n".join(context_parts)

    def clear_all(self) -> bool:
        """清空所有记忆"""
        self.short_term.clear()
        self.long_term.clear()
        logger.info("清空所有记忆")
        return True

    def get_stats(self) -> Dict[str, Any]:
        """获取统计信息"""
        return {
            "short_term": {
                "count": self.short_term.count(),
                "type": "short_term"
            },
            "long_term": {
                "count": self.long_term.count(),
                "type": "long_term",
                "stats": self.long_term.get_stats()
            }
        }

    def _get_timestamp(self) -> str:
        """获取时间戳"""
        from datetime import datetime
        return datetime.now().isoformat()

    def save_to_file(self, filepath: str):
        """保存记忆到文件（备份）"""
        import json

        all_memories = {
            "short_term": [
                memory.to_dict() for memory in self.short_term.list(limit=1000)
            ],
            "long_term": [
                memory.to_dict() for memory in self.long_term.list(limit=1000)
            ]
        }

        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(all_memories, f, ensure_ascii=False, indent=2)

        logger.info(f"记忆已保存到文件: {filepath}")

    def load_from_file(self, filepath: str):
        """从文件加载记忆（恢复）"""
        import json

        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                all_memories = json.load(f)

            # 清空现有记忆
            self.clear_all()

            # 加载短期记忆
            for memory_dict in all_memories.get("short_term", []):
                memory = MemoryItem(**memory_dict)
                self.short_term.memories[memory.id] = memory

            # 加载长期记忆
            for memory_dict in all_memories.get("long_term", []):
                memory = MemoryItem(**memory_dict)
                self.long_term.memories[memory.id] = memory

            logger.info(f"从文件加载记忆: {filepath}")

        except Exception as e:
            logger.error(f"加载记忆文件失败: {e}")