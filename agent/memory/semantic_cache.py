"""L2 语义响应缓存：向量相似度命中缓存，复用已有 ChromaDB 基础设施。"""
import json
import time
import uuid
from typing import Optional, Tuple, List
from loguru import logger


class SemanticCache:
    """基于向量相似度的响应缓存。

    对纯知识类问题（无工具调用）缓存 (question, answer) 对。
    新问题到来时，先编码向量，在 ChromaDB collection 中检索，
    余弦相似度 >= threshold 时命中并直接返回历史回答。
    """

    def __init__(
        self,
        embedding_model,
        persist_dir: str = "./chroma-data",
        threshold: float = 0.92,
        ttl_secs: int = 86400,      # 默认 24h
        max_entries: int = 2000,
    ):
        self.embedding_model = embedding_model
        self.threshold = threshold
        self.ttl_secs = ttl_secs
        self.max_entries = max_entries
        self._collection = None
        self._init_collection(persist_dir)

    def _init_collection(self, persist_dir: str) -> None:
        self._persist_dir = persist_dir   # 保存供后续恢复使用
        try:
            import chromadb, shutil, os
            client = chromadb.PersistentClient(path=persist_dir)
            try:
                self._collection = client.get_or_create_collection(
                    name="response_cache",
                    metadata={"hnsw:space": "cosine"},
                )
            except Exception as e:
                logger.warning(f"L2 语义缓存 collection 初始化异常，尝试重建: {e}")
                self._collection = None

            # 健康检查：count() 失败说明 HNSW index 损坏，需要重建
            if self._collection is not None:
                try:
                    count = self._collection.count()
                    logger.info(f"L2 语义缓存已初始化，collection: response_cache，当前条目数: {count}")
                    return   # 健康，直接返回
                except Exception as e:
                    logger.warning(f"L2 语义缓存 count() 检测到损坏，重建: {e}")

            # 文件级重建：删除 SQLite 记录 + UUID 目录
            self._collection = None
            try:
                import sqlite3
                db = os.path.join(persist_dir, "chroma.sqlite3")
                conn = sqlite3.connect(db)
                uids = [r[0] for r in conn.execute(
                    "SELECT id FROM collections WHERE name='response_cache'"
                ).fetchall()]
                conn.execute("DELETE FROM collections WHERE name='response_cache'")
                conn.commit(); conn.close()
                for uid in uids:
                    d = os.path.join(persist_dir, uid)
                    if os.path.exists(d):
                        shutil.rmtree(d)
                # 重建
                client2 = chromadb.PersistentClient(path=persist_dir)
                self._collection = client2.create_collection(
                    name="response_cache",
                    metadata={"hnsw:space": "cosine"},
                )
                logger.info(f"L2 语义缓存已重建，collection: response_cache，当前条目数: 0")
            except Exception as e2:
                logger.warning(f"L2 语义缓存重建失败（将跳过缓存）: {e2}")
                self._collection = None
        except Exception as e:
            logger.warning(f"L2 语义缓存初始化失败（将跳过）: {e}")
            self._collection = None

    # ── 公共接口 ──────────────────────────────────────────────

    def get(self, question: str, model: Optional[str] = None) -> Optional[str]:
        """查询语义缓存。命中且未过期时返回历史回答，否则返回 None。

        Args:
            question: 用户问题文本
            model: 当前使用的模型名称。提供时只返回同模型生成的缓存，防止跨模型污染。
        """
        if self._collection is None:
            return None
        try:
            vec = self._encode(question)
            if vec is None:
                return None
            # ChromaDB HNSW bug: n_results > filtered-subset count → TypeError
            try:
                if self._collection.count() == 0:
                    return None
            except Exception:
                return None
            query_kwargs: dict = {
                "query_embeddings": [vec],
                "n_results": 1,
                "include": ["metadatas", "documents", "distances"],
            }
            if model:
                query_kwargs["where"] = {"model": {"$eq": model}}
            try:
                results = self._collection.query(**query_kwargs)
            except TypeError:
                return None
            if not results["ids"] or not results["ids"][0]:
                return None

            distance = results["distances"][0][0]  # cosine distance: 0=identical
            similarity = 1.0 - distance             # convert to similarity
            if similarity < self.threshold:
                # 与 threshold 差距 <0.05 时打 INFO 级"近似未命中"日志：
                # 用于评估调低 threshold（TODO-12 #9）能否在不引入语义偏差的前提下提升命中率
                if self.threshold - similarity < 0.05:
                    logger.info(
                        f"[L2-cache] 近似未命中 similarity={similarity:.3f} "
                        f"(threshold={self.threshold}): {question[:40]}"
                    )
                return None

            meta = results["metadatas"][0][0]
            expire_ts = float(meta.get("expire_ts", 0))
            if time.time() > expire_ts:
                # 过期，异步删除（非阻塞）
                try:
                    self._collection.delete(ids=results["ids"][0])
                except Exception:
                    pass
                return None

            answer = meta.get("answer", "")
            logger.debug(
                f"[L2-cache] 命中 similarity={similarity:.3f}: {question[:40]}"
            )
            return answer
        except Exception as e:
            logger.warning(f"L2 语义缓存查询失败: {e}")
            return None

    def put(self, question: str, answer: str, model: Optional[str] = None) -> None:
        """将 (question, answer) 写入语义缓存。

        Args:
            question: 用户问题文本
            answer: 模型回答
            model: 当前使用的模型名称，写入 metadata 供 get() 过滤。
        """
        if self._collection is None or not answer:
            return
        try:
            # 类型保护：ChromaDB metadata 只支持 str/int/float/bool
            answer_str = answer if isinstance(answer, str) else str(answer)
            vec = self._encode(question)
            if vec is None:
                return
            entry_id = str(uuid.uuid4())
            meta: dict = {
                "answer": answer_str[:4000],    # ChromaDB metadata 有长度限制
                "expire_ts": str(time.time() + self.ttl_secs),
                "created_at": str(time.time()),
            }
            if model:
                meta["model"] = str(model)
            self._collection.upsert(
                ids=[entry_id],
                embeddings=[vec],
                documents=[str(question)[:2000]],
                metadatas=[meta],
            )
            self._maybe_evict()
        except Exception as e:
            logger.warning(f"L2 语义缓存写入失败: {e}")
            logger.debug(f"L2 cache write traceback", exc_info=True)

    def invalidate_expired(self) -> int:
        """删除所有已过期条目，返回删除数量（可在定期清理任务中调用）。"""
        if self._collection is None:
            return 0
        try:
            all_items = self._collection.get(include=["metadatas"])
            now = time.time()
            expired_ids = [
                mid for mid, meta in zip(all_items["ids"], all_items["metadatas"])
                if float(meta.get("expire_ts", 0)) < now
            ]
            if expired_ids:
                self._collection.delete(ids=expired_ids)
                logger.info(f"L2 语义缓存：清除 {len(expired_ids)} 条过期记录")
            return len(expired_ids)
        except Exception as e:
            logger.warning(f"L2 语义缓存清理失败: {e}")
            return 0

    # ── 内部工具 ──────────────────────────────────────────────

    def _encode(self, text: str) -> Optional[List[float]]:
        try:
            raw = self.embedding_model.encode(text)
            if isinstance(raw, list) and raw:
                vec = raw[0] if isinstance(raw[0], list) else raw
                return [float(x) for x in vec]
            return None
        except Exception as e:
            logger.debug(f"L2 缓存编码失败: {e}")
            return None

    def _maybe_evict(self) -> None:
        """若条目数超过 max_entries，删除最旧的 10%。"""
        try:
            count = self._collection.count()
            if count > self.max_entries:
                evict_n = max(1, self.max_entries // 10)
                all_ids = self._collection.get(include=[])["ids"]
                self._collection.delete(ids=all_ids[:evict_n])
                logger.debug(f"L2 语义缓存淘汰 {evict_n} 条旧记录")
        except Exception:
            pass
