"""Unit tests for ContextExtractor."""
import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from memory.context_extractor import ContextExtractor


class FakeMemoryItem:
    def __init__(self, role, content, user_id="u1"):
        self.content  = content
        self.metadata = {"role": role, "user_id": user_id}


class StubCollection:
    """In-memory ChromaDB collection stub."""

    def __init__(self):
        self._docs: list[dict] = []

    def query(self, query_texts, n_results, include):
        if not self._docs:
            return {"distances": [[]], "documents": [[]]}
        return {"distances": [[2.0]], "documents": [[self._docs[0]["document"]]]}

    def add(self, ids, documents, metadatas):
        self._docs.append({"id": ids[0], "document": documents[0], "meta": metadatas[0]})

    def get_collection(self, name):
        return self

    def create_collection(self, name, metadata=None):
        return self


async def _fake_model(messages):
    return json.dumps({"nuggets": ["决策A：使用 ChromaDB", "约束B：仅支持中文"]})


@pytest.mark.asyncio
async def test_extract_stores_nuggets():
    extractor  = ContextExtractor(interval=2, dedup_threshold=0.85)
    stub_coll  = StubCollection()
    chroma_mock = MagicMock()
    chroma_mock.get_collection.side_effect = Exception("not found")
    chroma_mock.create_collection.return_value = stub_coll

    items = [
        FakeMemoryItem("user",      "我需要一个使用 ChromaDB 的记忆系统"),
        FakeMemoryItem("assistant", "好的，我会使用 ChromaDB 实现记忆持久化"),
    ]

    stored = await extractor.extract(
        project_id   = "proj-001",
        user_id      = "u1",
        short_term_items = items,
        call_model_fn    = _fake_model,
        chroma_client    = chroma_mock,
        embedding_model  = None,
        persist_dir      = "/tmp",
    )
    assert stored == 2
    assert len(stub_coll._docs) == 2


@pytest.mark.asyncio
async def test_deduplication_skips_similar():
    extractor = ContextExtractor(interval=2, dedup_threshold=0.85)
    stub_coll = StubCollection()
    # Pre-populate with a near-identical nugget (distance 0.1 → similarity 0.95)
    stub_coll._docs.append({"id": "existing", "document": "决策A：使用 ChromaDB", "meta": {}})

    def close_query(query_texts, n_results, include):
        # Cosine distance 0.1 → similarity = 1 - 0.1/2 = 0.95 (above threshold)
        return {"distances": [[0.1]], "documents": [["决策A：使用 ChromaDB"]]}

    stub_coll.query = close_query

    chroma_mock = MagicMock()
    chroma_mock.get_collection.return_value = stub_coll

    items = [
        FakeMemoryItem("user",      "继续使用 ChromaDB"),
        FakeMemoryItem("assistant", "好的"),
    ]

    stored = await extractor.extract(
        project_id   = "proj-001",
        user_id      = "u1",
        short_term_items = items,
        call_model_fn    = _fake_model,
        chroma_client    = chroma_mock,
        embedding_model  = None,
        persist_dir      = "/tmp",
    )
    # Both nuggets are near-duplicates → 0 stored
    assert stored == 0


def test_turn_counter_composite_key():
    extractor = ContextExtractor(interval=3)
    extractor.record_turn("alice", "proj-A")
    extractor.record_turn("alice", "proj-A")
    result_a = extractor.record_turn("alice", "proj-A")   # 3rd turn → True

    result_b = extractor.record_turn("alice", "proj-B")   # different project, 1st turn → False

    assert result_a is True
    assert result_b is False


def test_reset_count():
    extractor = ContextExtractor(interval=2)
    extractor.record_turn("u1", "p1")
    extractor.record_turn("u1", "p1")  # hits threshold
    extractor.reset_count("u1", "p1")
    # After reset, next single turn should NOT trigger
    assert extractor.record_turn("u1", "p1") is False


@pytest.mark.asyncio
async def test_extract_skips_short_content():
    extractor = ContextExtractor(interval=2, dedup_threshold=0.85)

    async def bad_model(messages):
        return json.dumps({"nuggets": ["任务已全部完成", ""]})  # "" is too short (<5 chars)

    stub_coll  = StubCollection()
    chroma_mock = MagicMock()
    chroma_mock.get_collection.side_effect = Exception("not found")
    chroma_mock.create_collection.return_value = stub_coll

    items = [FakeMemoryItem("user", "a", "u1"), FakeMemoryItem("assistant", "b", "u1")]
    stored = await extractor.extract(
        project_id="p", user_id="u1", short_term_items=items,
        call_model_fn=bad_model, chroma_client=chroma_mock,
        embedding_model=None, persist_dir="/tmp",
    )
    assert stored == 1  # only "任务已全部完成" (len=7≥5) is stored; "" is filtered
