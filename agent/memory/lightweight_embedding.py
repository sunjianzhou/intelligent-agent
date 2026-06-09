"""轻量级嵌入模型（用于开发和测试）"""
import numpy as np
from typing import List, Union
from loguru import logger


class LightweightEmbeddingModel:
    """轻量级嵌入模型（用于测试，不依赖外部模型）"""

    def __init__(self, dimension: int = 384, model_name: str = "lightweight"):
        self.dimension = dimension
        self.model_name = model_name
        self.model = self  # 添加model属性，指向自己
        logger.info(f"使用轻量级嵌入模型，维度: {dimension}")

    def load_model(self):
        """模拟加载模型"""
        logger.info("轻量级嵌入模型已准备就绪")
        return self

    def encode(self, texts: Union[str, List[str]]) -> List[List[float]]:
        """生成模拟嵌入向量

        使用简单的hash方法生成确定性但随机的向量
        """
        if isinstance(texts, str):
            texts = [texts]

        embeddings = []
        for text in texts:
            # 使用简单的hash生成确定性向量
            import hashlib
            seed = int(hashlib.md5(text.encode()).hexdigest(), 16) % (2**32)
            np.random.seed(seed)

            # 生成随机向量并归一化
            vector = np.random.randn(self.dimension).astype(np.float32)
            norm = np.linalg.norm(vector)
            if norm > 0:
                vector = vector / norm

            embeddings.append(vector.tolist())

        return embeddings

    def similarity(self, embedding1: List[float], embedding2: List[float]) -> float:
        """计算余弦相似度"""
        vec1 = np.array(embedding1)
        vec2 = np.array(embedding2)

        norm1 = np.linalg.norm(vec1)
        norm2 = np.linalg.norm(vec2)

        if norm1 == 0 or norm2 == 0:
            return 0.0

        return float(np.dot(vec1, vec2) / (norm1 * norm2))