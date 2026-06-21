"""Unit tests for LongTermMemory excluded_from_retrieval hard filter
(message-retraction feature, design doc section 4.5)."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from memory.long_term import LongTermMemory
from memory.base import MemoryQuery


def _make_ltm():
    # use_lightweight=True + vector_db_type="memory" 避免依赖网络下载嵌入模型或真实 ChromaDB
    return LongTermMemory(vector_db_type="memory", use_lightweight=True)


def test_retrieve_excludes_flagged_memory():
    ltm = _make_ltm()
    keep = ltm.store("用户喜欢喝咖啡", metadata={"user_id": "u1"})
    excluded = ltm.store("用户喜欢喝咖啡和茶", metadata={"user_id": "u1"})

    ltm.update(excluded.id, metadata={"excluded_from_retrieval": True})

    results = ltm.retrieve(MemoryQuery(text="咖啡", limit=10, threshold=-1.0))
    result_ids = [r.memory.id for r in results]

    assert keep.id in result_ids
    assert excluded.id not in result_ids


def test_search_excludes_flagged_memory():
    ltm = _make_ltm()
    keep = ltm.store("天气晴朗", metadata={"user_id": "u1"})
    excluded = ltm.store("天气晴朗适合出门", metadata={"user_id": "u1"})

    ltm.update(excluded.id, metadata={"excluded_from_retrieval": True})

    results = ltm.search("天气", limit=10, threshold=-1.0)
    result_ids = [r.memory.id for r in results]

    assert keep.id in result_ids
    assert excluded.id not in result_ids


def test_excluded_memory_content_still_present_not_deleted():
    """硬过滤不删除内容——验证条目仍在 self.memories 里，只是检索时被滤掉。"""
    ltm = _make_ltm()
    item = ltm.store("被撤回来源的摘要", metadata={"user_id": "u1"})
    ltm.update(item.id, metadata={"excluded_from_retrieval": True})

    assert ltm.get(item.id) is not None
    assert ltm.get(item.id).content == "被撤回来源的摘要"
