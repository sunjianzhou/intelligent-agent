"""短期记忆"""
import uuid
from typing import Dict, Any, List, Optional, Union
from datetime import datetime, timedelta
from collections import deque
import json
from loguru import logger
from memory.base import BaseMemory, MemoryItem, MemoryQuery, MemorySearchResult


class ShortTermMemory(BaseMemory):
    """短期记忆（基于内存的缓存）"""

    def __init__(self, name: str = "short_term", max_size: int = 100, ttl_hours: int = 24):
        """初始化短期记忆

        Args:
            name: 记忆名称
            max_size: 最大记忆数量
            ttl_hours: 记忆有效期（小时）
        """
        super().__init__(name)
        self.memory_type = "short_term"
        self.max_size = max_size
        self.ttl_hours = ttl_hours

        # 使用有序字典存储记忆
        self.memories: Dict[str, MemoryItem] = {}
        # 使用双端队列维护记忆顺序
        self.memory_order = deque()

        logger.info(f"初始化短期记忆: {name}, 最大容量: {max_size}, 有效期: {ttl_hours}小时")

    def _generate_id(self) -> str:
        """生成记忆ID"""
        return f"stm_{uuid.uuid4().hex[:8]}"

    def _cleanup_expired(self):
        """清理过期的记忆"""
        if self.ttl_hours <= 0:
            return

        cutoff_time = datetime.now() - timedelta(hours=self.ttl_hours)
        expired_ids = []

        for memory_id, memory in self.memories.items():
            if memory.created_at < cutoff_time:
                expired_ids.append(memory_id)

        for memory_id in expired_ids:
            self._remove_memory(memory_id)

        if expired_ids:
            logger.info(f"清理了 {len(expired_ids)} 个过期的短期记忆")

    def _remove_memory(self, memory_id: str):
        """移除记忆（内部方法）"""
        if memory_id in self.memories:
            del self.memories[memory_id]

        # 从顺序队列中移除
        if memory_id in self.memory_order:
            self.memory_order.remove(memory_id)

    def _ensure_capacity(self):
        """确保不超过容量限制"""
        if len(self.memories) <= self.max_size:
            return

        # 移除最早的记忆：popleft() O(1)，避免 deque.remove() 的 O(n) 线性扫描
        while len(self.memories) > self.max_size and self.memory_order:
            oldest_id = self.memory_order.popleft()
            self.memories.pop(oldest_id, None)

        logger.info(f"达到容量限制，清理到 {self.max_size} 个记忆")

    def store(self, content: str, metadata: Dict[str, Any] = None,
              importance: float = 0.5) -> MemoryItem:
        """存储短期记忆"""
        # 清理过期记忆
        self._cleanup_expired()

        # 生成记忆ID
        memory_id = self._generate_id()

        # 创建记忆项
        memory = MemoryItem(
            id=memory_id,
            content=content,
            metadata=metadata or {},
            importance=importance
        )

        # 存储记忆
        self.memories[memory_id] = memory
        self.memory_order.append(memory_id)

        # 确保容量
        self._ensure_capacity()

        logger.debug(f"存储短期记忆: {memory_id}, 内容: {content[:50]}...")
        return memory

    def retrieve(self, query: Union[str, MemoryQuery], limit: int = 5) -> List[MemorySearchResult]:
        """检索记忆（基于文本匹配）"""
        if isinstance(query, str):
            query_text = query.lower()
        else:
            query_text = query.text.lower()
            limit = query.limit

        results = []

        for memory in self.memories.values():
            # 简单文本匹配
            if query_text in memory.content.lower():
                result = MemorySearchResult(
                    memory=memory,
                    similarity=1.0,
                    score=self.calculate_memory_score(memory, 1.0)
                )
                results.append(result)

        # 按分数排序
        results.sort(key=lambda x: x.score, reverse=True)

        # 更新访问计数
        for result in results[:limit]:
            result.memory.update_access()

        return results[:limit]

    def search(self, query: str, limit: int = 5, threshold: float = 0.7) -> List[MemorySearchResult]:
        """搜索记忆（文本搜索）"""
        return self.retrieve(query, limit)

    def get(self, memory_id: str) -> Optional[MemoryItem]:
        """获取特定记忆"""
        memory = self.memories.get(memory_id)
        if memory:
            memory.update_access()
        return memory

    def update(self, memory_id: str, content: str = None,
               metadata: Dict[str, Any] = None) -> Optional[MemoryItem]:
        """更新记忆"""
        if memory_id not in self.memories:
            return None

        memory = self.memories[memory_id]

        if content is not None:
            memory.content = content

        if metadata is not None:
            memory.metadata.update(metadata)

        memory.updated_at = datetime.now()

        # 更新访问顺序
        if memory_id in self.memory_order:
            self.memory_order.remove(memory_id)
            self.memory_order.append(memory_id)

        logger.debug(f"更新短期记忆: {memory_id}")
        return memory

    def delete(self, memory_id: str) -> bool:
        """删除记忆"""
        if memory_id in self.memories:
            self._remove_memory(memory_id)
            logger.debug(f"删除短期记忆: {memory_id}")
            return True
        return False

    def delete_by_ids(self, message_ids: list) -> int:
        """按 metadata['message_id'] 精确匹配删除，返回实际删除条数。

        用于消息撤回功能：撤回时按对话 JSON 里记录的 message_id 级联清理
        对应的短期记忆条目，避免该内容继续作为上下文喂给下一轮 LLM。
        """
        if not message_ids:
            return 0
        ids_set = set(message_ids)
        targets = [
            mid for mid, m in self.memories.items()
            if m.metadata.get("message_id") is not None
            and m.metadata.get("message_id") in ids_set
        ]
        for mid in targets:
            self._remove_memory(mid)
        return len(targets)

    def list(self, limit: int = 100, offset: int = 0) -> List[MemoryItem]:
        """列出所有记忆（按访问时间倒序）"""
        # 先按最后访问时间排序
        memories = list(self.memories.values())
        memories.sort(key=lambda m: m.last_accessed, reverse=True)

        return memories[offset:offset + limit]

    def clear(self) -> bool:
        """清空所有记忆"""
        self.memories.clear()
        self.memory_order.clear()
        logger.info("清空所有短期记忆")
        return True

    def count(self) -> int:
        """获取记忆数量"""
        return len(self.memories)

    def get_recent(self, limit: int = 10) -> List[MemoryItem]:
        """获取最近的记忆"""
        recent_memories = []

        # 从最近到最旧
        for memory_id in reversed(self.memory_order):
            if memory_id in self.memories:
                recent_memories.append(self.memories[memory_id])

            if len(recent_memories) >= limit:
                break

        return recent_memories

    def get_by_importance(self, min_importance: float = 0.7, limit: int = 10) -> List[MemoryItem]:
        """获取重要性高的记忆"""
        important_memories = []

        for memory in self.memories.values():
            if memory.importance >= min_importance:
                important_memories.append(memory)

        # 按重要性排序
        important_memories.sort(key=lambda m: m.importance, reverse=True)

        return important_memories[:limit]