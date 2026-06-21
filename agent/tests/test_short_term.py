"""Unit tests for ShortTermMemory.delete_by_ids() (message-retraction feature)."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from memory.short_term import ShortTermMemory


def test_delete_by_ids_removes_matching_entries():
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    m1 = stm.store("用户消息A", metadata={"role": "user", "message_id": "mid-1"})
    m2 = stm.store("助手消息A", metadata={"role": "assistant", "message_id": "mid-2"})
    m3 = stm.store("不相关消息", metadata={"role": "user", "message_id": "mid-3"})

    removed = stm.delete_by_ids(["mid-1", "mid-2"])

    assert removed == 2
    assert stm.get(m1.id) is None
    assert stm.get(m2.id) is None
    assert stm.get(m3.id) is not None


def test_delete_by_ids_ignores_unknown_ids():
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    stm.store("消息", metadata={"role": "user", "message_id": "mid-1"})

    removed = stm.delete_by_ids(["mid-does-not-exist"])

    assert removed == 0
    assert stm.count() == 1


def test_delete_by_ids_empty_list_returns_zero():
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    stm.store("消息", metadata={"role": "user", "message_id": "mid-1"})

    assert stm.delete_by_ids([]) == 0
    assert stm.count() == 1


def test_delete_by_ids_ignores_entries_without_message_id():
    """旧数据没有 message_id 字段，metadata.get() 返回 None，不应被意外匹配。"""
    stm = ShortTermMemory(max_size=100, ttl_hours=24)
    stm.store("旧版本消息（无 message_id）", metadata={"role": "user"})

    removed = stm.delete_by_ids([None])  # 防御性场景：调用方传入了 None

    assert removed == 0
    assert stm.count() == 1
