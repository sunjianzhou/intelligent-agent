"""长期记忆（基于向量数据库）"""
import uuid
import json
from typing import Dict, Any, List, Optional, Union
from datetime import datetime
from loguru import logger
from memory.base import BaseMemory, MemoryItem, MemoryQuery, MemorySearchResult, EmbeddingModel

class _ChromaEmbeddingAdapter:
    """把 chromadb 内置 embedding function 适配成 EmbeddingModel 接口"""
    def __init__(self, ef):
        self._ef = ef
        self.dimension = 384

    def encode(self, text: str):
        result = self._ef([text])
        return result  # 返回 list[list[float]]

    def similarity(self, a, b):
        import math
        dot = sum(x * y for x, y in zip(a, b))
        norm_a = math.sqrt(sum(x * x for x in a))
        norm_b = math.sqrt(sum(x * x for x in b))
        if norm_a == 0 or norm_b == 0:
            return 0.0
        return dot / (norm_a * norm_b)


class LongTermMemory(BaseMemory):
    """长期记忆（基于向量数据库）"""

    def __init__(self, name: str = "long_term",
                 vector_db_type: str = "chroma",
                 embedding_model: str = "all-MiniLM-L6-v2",
                 persist_dir: str = "./chroma_data",
                 use_lightweight: bool = False):
        """初始化长期记忆

        Args:
            name: 记忆名称
            vector_db_type: 向量数据库类型，支持 "chroma" 或 "memory"
            embedding_model: 嵌入模型名称
            persist_dir: 持久化目录
        """
        super().__init__(name)
        self.memory_type = "long_term"
        self.vector_db_type = vector_db_type
        self.persist_dir = persist_dir
        self.use_lightweight = use_lightweight  # 新增

        if use_lightweight:
            # 使用轻量级模型
            from memory.lightweight_embedding import LightweightEmbeddingModel
            self.embedding_model = LightweightEmbeddingModel(dimension=384)
            self.dimension = 384
        else:
            # 使用真实嵌入模型
            self.embedding_model = EmbeddingModel(embedding_model)
            self.dimension = self.embedding_model.dimension

        # 向量数据库
        self.vector_db = None
        self.collection = None

        # 内存存储（用于快速查找）
        self.memories: Dict[str, MemoryItem] = {}

        # 初始化数据库
        self._init_vector_db()

        logger.info(f"初始化长期记忆: {name}, 向量数据库: {vector_db_type}, 嵌入模型: {embedding_model}")

    def _generate_id(self) -> str:
        """生成记忆ID"""
        return f"ltm_{uuid.uuid4().hex}"

    def _init_vector_db(self):
        """初始化向量数据库"""
        if self.vector_db_type == "chroma":
            self._init_chroma_db()
        elif self.vector_db_type == "memory":
            self._init_memory_db()
        else:
            raise ValueError(f"不支持的向量数据库类型: {self.vector_db_type}")

    def _init_chroma_db(self):
        """初始化ChromaDB"""
        try:
            import chromadb
            from chromadb.config import Settings

            self.vector_db = chromadb.PersistentClient(
                path=self.persist_dir,
                settings=Settings(anonymized_telemetry=False)
            )

            # 判断是否使用 chromadb 内置 embedding（Docker 环境用，避免 torch 依赖）
            use_chroma_embedding = False
            try:
                import sentence_transformers  # noqa
            except ImportError:
                use_chroma_embedding = True
                logger.info("sentence_transformers 不可用，使用 chromadb 内置 embedding")

            if use_chroma_embedding:
                from chromadb.utils import embedding_functions
                ef = embedding_functions.DefaultEmbeddingFunction()
                # 覆盖 embedding_model，使其接口兼容
                self.embedding_model = _ChromaEmbeddingAdapter(ef)
                self.dimension = 384

            try:
                self.collection = self.vector_db.get_collection(
                    name=self.name,
                    embedding_function=self.embedding_model._ef if use_chroma_embedding else None
                )
                logger.info(f"加载现有集合: {self.name}")
            except Exception:
                self.collection = self.vector_db.create_collection(
                    name=self.name,
                    metadata={"hnsw:space": "cosine"},
                    embedding_function=self.embedding_model._ef if use_chroma_embedding else None
                )
                logger.info(f"创建新集合: {self.name}")

            self._load_memories_from_db()

        except ImportError:
            logger.error("请安装 chromadb: pip install chromadb")
            raise
        except Exception as e:
            logger.error(f"初始化ChromaDB失败: {e}")
            raise

    def _init_memory_db(self):
        """初始化内存向量数据库（用于测试）"""
        logger.warning("使用内存向量数据库，数据不会持久化")
        self.collection = {
            "ids": [],
            "embeddings": [],
            "metadatas": [],
            "documents": []
        }

    def _load_memories_from_db(self):
        """延迟加载模式：启动时只记录总数，不再全量拉取到内存。

        ChromaDB 是 source of truth；retrieve() 通过查询结果直接重建 MemoryItem，
        避免 10k+ 条记忆时的启动卡顿（原方案全量加载 O(n) 内存 + 时间）。

        count() 连续失败可能是瞬时性 I/O / 文件锁争用，也可能是索引真损坏——
        无法在不破坏数据的前提下区分两者，因此绝不能"先删后建"。这里改为：
        重试 → 仍失败则先导出全量数据备份到磁盘 → 确认备份成功后才重建集合，
        全程在 ERROR 级别记录"数据丢失风险"，确保事故可追溯、可人工恢复。
        """
        import time

        if self.vector_db_type == "memory":
            return
        if self.collection is None:
            return

        last_err = None
        for attempt in range(3):
            try:
                count = self.collection.count()
                logger.info(f"长期记忆 ChromaDB 已连接，共 {count} 条（延迟加载模式）")
                return
            except Exception as e:
                last_err = e
                if attempt < 2:
                    time.sleep(0.5 * (attempt + 1))

        logger.error(
            f"长期记忆 collection.count() 连续 3 次失败（最后一次错误: {last_err}），"
            f"疑似索引损坏，进入安全重建流程（重建前会先备份现有数据）"
        )

        backup_path = self._backup_collection_before_rebuild()
        if backup_path is None:
            logger.error(
                f"⚠️ 数据丢失风险：备份未产出文件（collection 可能本就为空，或导出本身失败），"
                f"为避免长期记忆功能完全不可用，仍将继续尝试重建 '{self.name}'"
            )

        try:
            self.vector_db.delete_collection(self.name)
            self.collection = self.vector_db.create_collection(
                name=self.name,
                metadata={"hnsw:space": "cosine"},
            )
            if backup_path:
                logger.error(
                    f"⚠️ 长期记忆 collection '{self.name}' 已重建并清空为 0 条；"
                    f"重建前的数据已备份到 {backup_path}，可人工恢复"
                )
            else:
                logger.error(
                    f"⚠️ 长期记忆 collection '{self.name}' 已重建并清空为 0 条，且未能生成备份"
                )
        except Exception as e2:
            logger.error(f"长期记忆重建失败: {e2}，记忆功能将降级为只读")

    def _backup_collection_before_rebuild(self) -> Optional[str]:
        """在销毁性重建前导出 collection 全部数据到 JSON 文件（尽力而为）。

        返回备份文件路径；若 collection 为空或导出本身失败（索引损坏到连 get() 都报错），返回 None。
        """
        import os

        try:
            data = self.collection.get(include=["documents", "metadatas", "embeddings"])
            ids = data.get("ids") or []
            if not ids:
                logger.info(f"长期记忆 collection '{self.name}' 重建前导出为空，无需备份")
                return None

            backup_dir = os.path.join(self.persist_dir, "_corruption_backups")
            os.makedirs(backup_dir, exist_ok=True)
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            backup_path = os.path.join(backup_dir, f"{self.name}_{timestamp}.json")

            embeddings = data.get("embeddings")
            payload = {
                "name": self.name,
                "backed_up_at": datetime.now().isoformat(),
                "count": len(ids),
                "ids": ids,
                "documents": data.get("documents") or [],
                "metadatas": data.get("metadatas") or [],
                "embeddings": [list(e) for e in embeddings] if embeddings is not None else [],
            }
            with open(backup_path, "w", encoding="utf-8") as f:
                json.dump(payload, f, ensure_ascii=False)

            logger.warning(f"长期记忆重建前已备份 {len(ids)} 条数据到 {backup_path}")
            return backup_path
        except Exception as e:
            logger.error(f"长期记忆重建前备份失败（{e}）：原数据一旦被重建覆盖将不可恢复")
            return None

    def restore_from_backup(self, backup_path: str) -> int:
        """从 _backup_collection_before_rebuild 产出的 JSON 文件恢复数据到当前 collection。

        用于人工排障时把意外重建前导出的数据写回；返回成功恢复的条数。
        """
        if self.vector_db_type != "chroma" or self.collection is None:
            raise RuntimeError("仅支持向已连接的 ChromaDB collection 恢复数据")

        with open(backup_path, "r", encoding="utf-8") as f:
            payload = json.load(f)

        ids = payload.get("ids") or []
        if not ids:
            return 0

        documents = payload.get("documents") or []
        metadatas = payload.get("metadatas") or []
        embeddings = payload.get("embeddings") or []

        kwargs = {"ids": ids, "documents": documents, "metadatas": metadatas}
        if embeddings and len(embeddings) == len(ids):
            kwargs["embeddings"] = embeddings
        self.collection.add(**kwargs)

        logger.info(f"已从备份 {backup_path} 恢复 {len(ids)} 条长期记忆到 '{self.name}'")
        return len(ids)

    @staticmethod
    def _meta_to_memory_item(memory_id: str, content: str,
                              metadata_dict: Dict[str, Any]) -> "MemoryItem":
        """从 ChromaDB 元数据重建 MemoryItem（供 retrieve() 使用）。"""
        custom_metadata = metadata_dict.get("custom_metadata", {})
        if isinstance(custom_metadata, str):
            try:
                custom_metadata = json.loads(custom_metadata)
            except Exception:
                custom_metadata = {}

        memory = MemoryItem(
            id=memory_id,
            content=content,
            metadata=custom_metadata,
            importance=float(metadata_dict.get("importance", 0.5)),
        )
        for field in ("created_at", "updated_at", "last_accessed"):
            val = metadata_dict.get(field)
            if val:
                try:
                    setattr(memory, field, datetime.fromisoformat(val))
                except Exception:
                    pass
        access = metadata_dict.get("access_count")
        if access is not None:
            try:
                memory.access_count = int(access)
            except Exception:
                pass
        return memory

    def _save_to_vector_db(self, memory: MemoryItem, embedding: List[float] = None):
        """保存到向量数据库"""
        if self.vector_db_type == "memory":
            # 内存存储
            self.collection["ids"].append(memory.id)
            self.collection["documents"].append(memory.content)
            self.collection["metadatas"].append({
                "created_at": memory.created_at.isoformat(),
                "updated_at": memory.updated_at.isoformat(),
                "importance": str(memory.importance),
                "access_count": str(memory.access_count),
                "last_accessed": memory.last_accessed.isoformat(),
                "custom_metadata": memory.metadata
            })
            if embedding:
                self.collection["embeddings"].append(embedding)
            return

        # ChromaDB存储（user_id 提升为顶层字段以支持 where 过滤）
        metadata = {
            "created_at": memory.created_at.isoformat(),
            "updated_at": memory.updated_at.isoformat(),
            "importance": str(memory.importance),
            "access_count": str(memory.access_count),
            "last_accessed": memory.last_accessed.isoformat(),
            "custom_metadata": json.dumps(memory.metadata, ensure_ascii=False),
            "user_id": str(memory.metadata.get("user_id", "default")),
        }

        if embedding:
            # 添加或更新
            self.collection.upsert(
                ids=[memory.id],
                embeddings=[embedding],
                metadatas=[metadata],
                documents=[memory.content]
            )
        else:
            # 只更新元数据
            self.collection.update(
                ids=[memory.id],
                metadatas=[metadata]
            )

    def _delete_from_vector_db(self, memory_id: str):
        """从向量数据库删除"""
        if self.vector_db_type == "memory":
            if memory_id in self.collection["ids"]:
                idx = self.collection["ids"].index(memory_id)
                self.collection["ids"].pop(idx)
                self.collection["documents"].pop(idx)
                if self.collection["metadatas"]:
                    self.collection["metadatas"].pop(idx)
                if self.collection["embeddings"]:
                    self.collection["embeddings"].pop(idx)
            return

        # ChromaDB删除
        self.collection.delete(ids=[memory_id])

    def store(self, content: str, metadata: Dict[str, Any] = None,
              importance: float = 0.5) -> MemoryItem:
        """存储长期记忆"""
        # 生成记忆ID
        memory_id = self._generate_id()

        # 创建记忆项
        memory = MemoryItem(
            id=memory_id,
            content=content,
            metadata=metadata or {},
            importance=importance
        )

        # 延迟加载嵌入模型
        if hasattr(self.embedding_model, 'model') and self.embedding_model.model is None:
            self.embedding_model.load_model()
        elif not hasattr(self.embedding_model, 'model'):
            # 对于轻量级模型，直接使用
            pass

        # 生成嵌入向量
        embedding = self.embedding_model.encode(content)[0]
        memory.embedding = embedding

        # 存储到内存
        self.memories[memory_id] = memory

        # 存储到向量数据库
        self._save_to_vector_db(memory, embedding)

        logger.debug(f"存储长期记忆: {memory_id}, 内容: {content[:50]}...")
        return memory

    def store_batch(self, items: List[Dict[str, Any]], batch_size: int = 32) -> List[MemoryItem]:
        """批量存储长期记忆（批量编码 + 一次 ChromaDB upsert，性能优于逐条调用 store()）。

        Args:
            items: [{"content": str, "metadata": dict, "importance": float}, ...]
            batch_size: embedding 编码时的批大小
        Returns:
            已存储的 MemoryItem 列表
        """
        if not items:
            return []

        contents = [it["content"] for it in items]

        # 批量编码（sentence-transformers 支持 list 输入）
        try:
            raw = self.embedding_model.encode(contents)   # shape: (n, dim)
            embeddings = [list(vec) for vec in raw]
        except Exception as e:
            logger.warning(f"批量编码失败，降级为逐条编码: {e}")
            embeddings = [list(self.embedding_model.encode(c)[0]) for c in contents]

        memories: List[MemoryItem] = []
        ids, emb_list, meta_list, doc_list = [], [], [], []

        for it, emb in zip(items, embeddings):
            mid = self._generate_id()
            mem = MemoryItem(
                id=mid,
                content=it["content"],
                metadata=it.get("metadata") or {},
                importance=it.get("importance", 0.5),
            )
            mem.embedding = emb
            self.memories[mid] = mem
            memories.append(mem)

            ids.append(mid)
            emb_list.append(emb)
            meta_list.append({
                "created_at": mem.created_at.isoformat(),
                "updated_at": mem.updated_at.isoformat(),
                "importance": str(mem.importance),
                "access_count": str(mem.access_count),
                "last_accessed": mem.last_accessed.isoformat(),
                "custom_metadata": json.dumps(mem.metadata, ensure_ascii=False),
                "user_id": str(mem.metadata.get("user_id", "default")),
            })
            doc_list.append(mem.content)

        # 一次批量 upsert
        if self.vector_db_type != "memory" and self.collection is not None:
            try:
                self.collection.upsert(
                    ids=ids,
                    embeddings=emb_list,
                    metadatas=meta_list,
                    documents=doc_list,
                )
                logger.info(f"批量存储长期记忆 {len(memories)} 条")
            except Exception as e:
                logger.error(f"ChromaDB 批量 upsert 失败: {e}")

        return memories

    def retrieve(self, query: Union[str, MemoryQuery], limit: int = 5) -> List[MemorySearchResult]:
        """检索记忆（基于向量相似度）"""
        if isinstance(query, str):
            query_obj = MemoryQuery(text=query, limit=limit)
        else:
            query_obj = query

        # 生成查询向量
        query_embedding = self.embedding_model.encode(query_obj.text)[0]

        # 在向量数据库中搜索
        if self.vector_db_type == "memory":
            results = self._search_in_memory(query_embedding, query_obj)
        else:
            results = self._search_in_chroma(query_embedding, query_obj)

        # 转换为搜索结果，同时收集需要更新访问记录的 memory
        search_results = []
        updated_memories: List[MemoryItem] = []

        for result_tuple in results:
            # 兼容 in-memory 模式（2元组）和 ChromaDB 延迟加载模式（4元组）
            if len(result_tuple) == 4:
                memory_id, similarity, doc, meta = result_tuple
                # 先从热缓存取；取不到则从 ChromaDB 元数据重建
                memory = self.memories.get(memory_id)
                if memory is None:
                    memory = self._meta_to_memory_item(memory_id, doc, meta)
                    self.memories[memory_id] = memory  # 回填热缓存
            else:
                memory_id, similarity = result_tuple
                memory = self.memories.get(memory_id)

            if memory:
                memory.update_access()            # 只更新内存对象，暂不写 DB
                updated_memories.append(memory)

                score = self.calculate_memory_score(memory, similarity)
                search_results.append(MemorySearchResult(
                    memory=memory,
                    similarity=similarity,
                    score=score
                ))

        # 一次批量 upsert 更新所有访问记录（替代逐条 _save_to_vector_db）
        self._batch_update_access_records(updated_memories)

        # 按分数排序
        search_results.sort(key=lambda x: x.score, reverse=True)
        return search_results[:query_obj.limit]

    def _batch_update_access_records(self, memories: List[MemoryItem]) -> None:
        """把多个 MemoryItem 的访问记录一次性批量写回 ChromaDB。"""
        if not memories or self.vector_db_type == "memory" or self.collection is None:
            return
        ids, meta_list, doc_list = [], [], []
        for mem in memories:
            ids.append(mem.id)
            meta_list.append({
                "created_at": mem.created_at.isoformat(),
                "updated_at": mem.updated_at.isoformat(),
                "importance": str(mem.importance),
                "access_count": str(mem.access_count),
                "last_accessed": mem.last_accessed.isoformat(),
                "custom_metadata": json.dumps(mem.metadata, ensure_ascii=False),
            })
            doc_list.append(mem.content)
        try:
            self.collection.upsert(ids=ids, metadatas=meta_list, documents=doc_list)
        except Exception as e:
            logger.warning(f"批量更新访问记录失败（不影响检索结果）: {e}")

    def _search_in_memory(self, query_embedding: List[float],
                          query: MemoryQuery) -> List[tuple]:
        """在内存中搜索"""
        results = []

        for memory_id, memory in self.memories.items():
            if memory.embedding:
                similarity = self.embedding_model.similarity(query_embedding, memory.embedding)

                # 应用阈值
                if similarity >= query.threshold:
                    # 应用元数据过滤
                    if query.metadata_filter:
                        if not self._matches_metadata_filter(memory.metadata, query.metadata_filter):
                            continue

                    results.append((memory_id, similarity))

        # 按相似度排序
        results.sort(key=lambda x: x[1], reverse=True)
        return results[:query.limit]

    def _search_in_chroma(self, query_embedding: List[float],
                          query: MemoryQuery) -> List[tuple]:
        """在ChromaDB中搜索"""
        try:
            # 构建查询
            n_results = min(query.limit * 2, 100)  # 获取更多结果用于过滤

            where_filter = None
            if query.metadata_filter:
                # user_id 已提升为 ChromaDB 顶层字段；其他 key 按原来逻辑处理
                conditions = []
                for key, value in query.metadata_filter.items():
                    conditions.append({key: {"$eq": str(value)}})
                where_filter = conditions[0] if len(conditions) == 1 else {"$and": conditions}

            # ChromaDB data-format bug：seq_id stored as INT in old SQLite schema,
            # current version expects BLOB → count() / query() raise TypeError.
            # Wrap count() separately; if it fails the collection is unreadable.
            try:
                count = self.collection.count()
            except TypeError:
                logger.debug("ChromaDB collection unreadable (schema mismatch), skipping search")
                return []
            if count == 0:
                return []
            n_results = min(n_results, count)

            search_results = None
            for _attempt_n in [n_results, max(1, n_results // 2), 1]:
                try:
                    search_results = self.collection.query(
                        query_embeddings=[query_embedding],
                        n_results=_attempt_n,
                        where=where_filter,
                        include=["metadatas", "documents", "distances"]
                    )
                    break
                except TypeError:
                    if _attempt_n == 1:
                        return []
                    continue
            if search_results is None:
                return []

            if not search_results["ids"]:
                return []

            # 处理结果：返回 (memory_id, similarity, document, metadata) 元组
            results = []
            ids       = search_results["ids"][0]
            distances = search_results["distances"][0]
            documents = search_results.get("documents", [[]])[0]
            metadatas = search_results.get("metadatas", [[]])[0]

            for i, memory_id in enumerate(ids):
                distance   = distances[i]
                similarity = 1.0 - distance

                if similarity >= query.threshold:
                    doc  = documents[i] if i < len(documents) else ""
                    meta = metadatas[i] if i < len(metadatas) else {}
                    results.append((memory_id, similarity, doc, meta))

            return results

        except Exception as e:
            logger.error(f"向量搜索失败: {e}")
            return []

    def _matches_metadata_filter(self, metadata: Dict[str, Any],
                                 filter_dict: Dict[str, Any]) -> bool:
        """检查元数据是否匹配过滤器"""
        for key, value in filter_dict.items():
            if key not in metadata or metadata[key] != value:
                return False
        return True

    def search(self, query: str, limit: int = 5, threshold: float = 0.7) -> List[MemorySearchResult]:
        """搜索记忆（向量搜索）"""
        return self.retrieve(MemoryQuery(text=query, limit=limit, threshold=threshold))

    def get(self, memory_id: str) -> Optional[MemoryItem]:
        """获取特定记忆"""
        memory = self.memories.get(memory_id)
        if memory:
            memory.update_access()
            self._save_to_vector_db(memory)  # 更新访问记录
        return memory

    def update(self, memory_id: str, content: str = None,
               metadata: Dict[str, Any] = None) -> Optional[MemoryItem]:
        """更新记忆"""
        if memory_id not in self.memories:
            return None

        memory = self.memories[memory_id]
        update_embedding = False

        if content is not None and content != memory.content:
            memory.content = content
            update_embedding = True

        if metadata is not None:
            memory.metadata.update(metadata)

        memory.updated_at = datetime.now()

        # 如果需要更新嵌入向量
        if update_embedding:
            memory.embedding = self.embedding_model.encode(content)[0]

        # 更新向量数据库
        self._save_to_vector_db(memory, memory.embedding if update_embedding else None)

        logger.debug(f"更新长期记忆: {memory_id}")
        return memory

    def delete(self, memory_id: str) -> bool:
        """删除记忆"""
        if memory_id in self.memories:
            # 从内存删除
            del self.memories[memory_id]

            # 从向量数据库删除
            self._delete_from_vector_db(memory_id)

            logger.debug(f"删除长期记忆: {memory_id}")
            return True

        return False

    def list(self, limit: int = 100, offset: int = 0) -> List[MemoryItem]:
        """列出所有记忆（按创建时间倒序）"""
        memories = list(self.memories.values())
        memories.sort(key=lambda m: m.created_at, reverse=True)

        return memories[offset:offset + limit]

    def clear(self) -> bool:
        """清空所有记忆"""
        self.memories.clear()

        if self.vector_db_type == "chroma":
            self.vector_db.delete_collection(self.name)
            self.collection = self.vector_db.create_collection(
                name=self.name,
                metadata={"hnsw:space": "cosine"}
            )
        elif self.vector_db_type == "memory":
            self.collection = {
                "ids": [],
                "embeddings": [],
                "metadatas": [],
                "documents": []
            }

        logger.info("清空所有长期记忆")
        return True

    def count(self) -> int:
        """获取记忆数量"""
        return len(self.memories)

    def get_stats(self) -> Dict[str, Any]:
        """获取统计信息"""
        if not self.memories:
            return {
                "total_memories": 0,
                "average_importance": 0,
                "average_access_count": 0,
                "oldest_memory": None,
                "newest_memory": None,
            }

        total_importance = total_access = 0
        oldest = newest = None
        for m in self.memories.values():
            total_importance += m.importance
            total_access += m.access_count
            if oldest is None or m.created_at < oldest:
                oldest = m.created_at
            if newest is None or m.created_at > newest:
                newest = m.created_at
        n = len(self.memories)
        return {
            "total_memories": n,
            "average_importance": total_importance / n,
            "average_access_count": total_access / n,
            "oldest_memory": oldest,
            "newest_memory": newest,
        }