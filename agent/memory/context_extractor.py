"""Project context extractor — extracts task-relevant decisions/state from conversation."""
import json
from typing import Dict, List, Any, Optional
from loguru import logger


class ContextExtractor:
    """Extracts project context nuggets from conversation every EXTRACT_EVERY turns.

    Analogous to MemoryDistiller but focuses on task decisions and state
    rather than personal facts. Writes to a per-project ChromaDB collection.
    """

    EXTRACT_EVERY = 8

    def __init__(self, interval: int = None, dedup_threshold: float = 0.85):
        self.interval        = interval or self.EXTRACT_EVERY
        self.dedup_threshold = dedup_threshold
        self._turn_counts: Dict[str, int] = {}

    def _key(self, user_id: str, project_id: str) -> str:
        return f"{user_id}:{project_id}"

    def record_turn(self, user_id: str, project_id: str) -> bool:
        """Increment turn counter. Returns True when extraction should run."""
        k = self._key(user_id, project_id)
        self._turn_counts[k] = self._turn_counts.get(k, 0) + 1
        return self._turn_counts[k] >= self.interval

    def reset_count(self, user_id: str, project_id: str) -> None:
        self._turn_counts[self._key(user_id, project_id)] = 0

    async def extract(
        self,
        project_id: str,
        user_id: str,
        short_term_items: List[Any],
        call_model_fn,
        chroma_client,
        embedding_model,
        persist_dir: str = "./chroma_data",
    ) -> int:
        """Extract context nuggets and store in per-project ChromaDB collection.

        Returns the number of new nuggets stored.
        """
        self.reset_count(user_id, project_id)

        # Filter to this user's messages
        user_items = [
            m for m in short_term_items
            if m.metadata.get("user_id") == user_id
            and m.metadata.get("role") in ("user", "assistant")
        ]
        if len(user_items) < 2:
            return 0

        conv_text = "\n".join(
            f"{'用户' if m.metadata.get('role') == 'user' else '助手'}: {m.content[:300]}"
            for m in user_items[-(self.interval * 2):]
        )

        prompt = (
            "请从以下对话中提取任务相关的关键决策、约束条件和中间结果。"
            "以JSON格式输出：{\"nuggets\": [\"决策或状态描述1\", \"决策或状态描述2\", ...]}"
            "每条描述控制在100字以内，最多提取5条。只输出JSON。\n\n"
            f"对话内容：\n{conv_text}"
        )

        try:
            raw = await call_model_fn([
                {"role": "system", "content": "你是信息提取助手，只输出JSON。"},
                {"role": "user",   "content": prompt},
            ])
            clean = raw.strip().replace("```json", "").replace("```", "").strip()
            parsed = json.loads(clean)
            nuggets: List[str] = parsed.get("nuggets", [])
        except Exception as e:
            logger.warning(f"ContextExtractor JSON 解析失败 (project={project_id}): {e}")
            return 0

        if not nuggets:
            return 0

        # Get or create per-project collection
        collection_name = f"project_{project_id.replace('-', '_')}_context"
        try:
            try:
                collection = chroma_client.get_collection(name=collection_name)
            except Exception:
                collection = chroma_client.create_collection(
                    name=collection_name,
                    metadata={"hnsw:space": "cosine"},
                )
        except Exception as e:
            logger.warning(f"ContextExtractor 集合初始化失败 (project={project_id}): {e}")
            return 0

        stored = 0
        for nugget in nuggets:
            if not nugget or len(nugget.strip()) < 5:
                continue
            try:
                # Deduplication: check similarity against existing nuggets
                existing = collection.query(
                    query_texts=[nugget],
                    n_results=1,
                    include=["distances"],
                )
                if existing and existing.get("distances") and existing["distances"][0]:
                    # ChromaDB cosine distance: 0=identical, 2=opposite
                    # similarity = 1 - distance/2 for normalised cosine
                    dist = existing["distances"][0][0]
                    similarity = 1.0 - dist / 2.0
                    if similarity >= self.dedup_threshold:
                        logger.debug(f"跳过重复 nugget (sim={similarity:.2f}): {nugget[:40]}")
                        continue
            except Exception:
                pass  # On error skip dedup, just store

            try:
                import uuid as _uuid
                nugget_id = f"nugget_{project_id[:8]}_{_uuid.uuid4().hex[:8]}"
                from datetime import datetime
                collection.add(
                    ids=[nugget_id],
                    documents=[nugget],
                    metadatas=[{
                        "type":       "context_nugget",
                        "project_id": project_id,
                        "user_id":    user_id,
                        "timestamp":  datetime.now().isoformat(),
                    }],
                )
                stored += 1
            except Exception as e:
                logger.warning(f"存储 nugget 失败: {e}")

        if stored:
            logger.info(f"ContextExtractor 提取 {stored} 条上下文 (project={project_id})")
        return stored
