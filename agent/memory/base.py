"""记忆基类"""
from abc import ABC, abstractmethod
from typing import Dict, Any, List, Optional, Union
from pydantic import BaseModel, Field
from datetime import datetime
from loguru import logger
import json


class MemoryItem(BaseModel):
    """记忆项"""

    id: str
    content: str
    metadata: Dict[str, Any] = Field(default_factory=dict)
    embedding: Optional[List[float]] = None
    created_at: datetime = Field(default_factory=datetime.now)
    updated_at: datetime = Field(default_factory=datetime.now)
    importance: float = Field(default=0.5, ge=0.0, le=1.0)  # 记忆重要性
    access_count: int = 0
    last_accessed: datetime = Field(default_factory=datetime.now)

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典"""
        return {
            "id": self.id,
            "content": self.content,
            "metadata": self.metadata,
            "embedding": self.embedding,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
            "importance": self.importance,
            "access_count": self.access_count,
            "last_accessed": self.last_accessed.isoformat()
        }

    def update_access(self):
        """更新访问信息"""
        self.access_count += 1
        self.last_accessed = datetime.now()


class MemoryQuery(BaseModel):
    """记忆查询"""

    text: str
    limit: int = 5
    threshold: float = 0.6  # 相似度阈值
    metadata_filter: Optional[Dict[str, Any]] = None


class MemorySearchResult(BaseModel):
    """记忆搜索结果"""

    memory: MemoryItem
    similarity: float
    score: float  # 综合分数（相似度 + 重要性 + 新鲜度）


class BaseMemory(ABC):
    """记忆基类"""

    def __init__(self, name: str):
        self.name = name
        self.memory_type = "base"

    @abstractmethod
    def store(self, content: str, metadata: Dict[str, Any] = None,
              importance: float = 0.5) -> MemoryItem:
        """存储记忆"""
        pass

    @abstractmethod
    def retrieve(self, query: Union[str, MemoryQuery], limit: int = 5) -> List[MemorySearchResult]:
        """检索记忆"""
        pass

    @abstractmethod
    def search(self, query: str, limit: int = 5, threshold: float = 0.7) -> List[MemorySearchResult]:
        """搜索记忆"""
        pass

    @abstractmethod
    def get(self, memory_id: str) -> Optional[MemoryItem]:
        """获取记忆"""
        pass

    @abstractmethod
    def update(self, memory_id: str, content: str = None,
               metadata: Dict[str, Any] = None) -> Optional[MemoryItem]:
        """更新记忆"""
        pass

    @abstractmethod
    def delete(self, memory_id: str) -> bool:
        """删除记忆"""
        pass

    @abstractmethod
    def list(self, limit: int = 100, offset: int = 0) -> List[MemoryItem]:
        """列出记忆"""
        pass

    @abstractmethod
    def clear(self) -> bool:
        """清空记忆"""
        pass

    @abstractmethod
    def count(self) -> int:
        """获取记忆数量"""
        pass

    def calculate_memory_score(self, memory: MemoryItem, similarity: float) -> float:
        """计算记忆综合分数

        综合分数 = 相似度 * 0.5 + 重要性 * 0.3 + 新鲜度 * 0.2
        新鲜度 = 1 / (1 + 距离现在的小时数/24)
        """
        # 计算相似度部分
        similarity_score = similarity

        # 计算重要性部分
        importance_score = memory.importance

        # 计算新鲜度
        hours_since_created = (datetime.now() - memory.created_at).total_seconds() / 3600
        recency_score = 1.0 / (1.0 + hours_since_created / 24.0)

        # 计算加权分数
        score = (
                similarity_score * 0.5 +
                importance_score * 0.3 +
                recency_score * 0.2
        )

        return score


class EmbeddingModel:
    """嵌入模型封装"""

    # 类级缓存，避免重复加载
    _model_cache = {}

    def __init__(self, model_name: str = "all-MiniLM-L6-v2"):
        """初始化嵌入模型

        Args:
            model_name: 模型名称
        """
        self.model_name = model_name
        self.model = None
        self.dimension = 384  # 默认维度

    def load_model(self):
        """加载模型（带缓存）"""
        # 检查缓存
        if self.model_name in EmbeddingModel._model_cache:
            self.model = EmbeddingModel._model_cache[self.model_name]
            logger.info(f"从缓存加载嵌入模型: {self.model_name}")
        else:
            try:
                from sentence_transformers import SentenceTransformer

                logger.info(f"加载嵌入模型: {self.model_name}")
                self.model = SentenceTransformer(self.model_name)

                # 保存到缓存
                EmbeddingModel._model_cache[self.model_name] = self.model

                # 获取模型维度
                if "mini" in self.model_name.lower() and "l6" in self.model_name.lower():
                    self.dimension = 384
                elif "mini" in self.model_name.lower() and "l12" in self.model_name.lower():
                    self.dimension = 384
                elif "mpnet" in self.model_name.lower():
                    self.dimension = 768
                else:
                    # 尝试推断维度
                    test_embedding = self.model.encode(["test"])
                    self.dimension = len(test_embedding[0])

                logger.info(f"嵌入模型维度: {self.dimension}")

            except ImportError:
                logger.error("请安装 sentence-transformers: pip install sentence-transformers")
                raise

    def encode(self, texts: Union[str, List[str]]) -> List[List[float]]:
        """编码文本为向量"""
        if self.model is None:
            self.load_model()

        if isinstance(texts, str):
            texts = [texts]

        embeddings = self.model.encode(texts, show_progress_bar=False)
        return embeddings.tolist()

    def similarity(self, embedding1: List[float], embedding2: List[float]) -> float:
        """计算余弦相似度"""
        import numpy as np

        vec1 = np.array(embedding1)
        vec2 = np.array(embedding2)

        # 归一化
        norm1 = np.linalg.norm(vec1)
        norm2 = np.linalg.norm(vec2)

        if norm1 == 0 or norm2 == 0:
            return 0.0

        return float(np.dot(vec1, vec2) / (norm1 * norm2))