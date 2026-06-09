"""Tests for MemoryDistiller (9.3 — auto memory distillation)."""
import sys
import os
import pytest
import asyncio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from memory.distiller import MemoryDistiller


# ── Fixtures ──────────────────────────────────────────────────────────────────

class FakeLongTermMemory:
    """Minimal stub so tests don't need ChromaDB running."""
    def __init__(self):
        self.stored = []

    def store(self, content, metadata=None, importance=0.5):
        self.stored.append({"content": content, "metadata": metadata or {}, "importance": importance})

    def retrieve(self, query):
        return []  # no duplicates by default


class DuplicateLongTermMemory(FakeLongTermMemory):
    """Returns similarity=0.95 for every query — simulates full duplication."""
    def retrieve(self, query):
        class FakeResult:
            similarity = 0.95
        return [FakeResult()]


class FakeMemoryItem:
    def __init__(self, role: str, content: str, user_id: str = "u1"):
        self.content = content
        self.metadata = {"role": role, "user_id": user_id}


async def _llm_good(messages):
    """Stub LLM that returns valid JSON with two facts."""
    return '{"facts": ["用户叫张三", "用户是Python工程师"]}'


async def _llm_empty(messages):
    return '{"facts": []}'


async def _llm_bad(messages):
    return "这不是JSON"


# ── Turn counter ──────────────────────────────────────────────────────────────

def test_record_turn_returns_false_before_threshold():
    d = MemoryDistiller(interval=3)
    assert d.record_turn("u1") is False
    assert d.record_turn("u1") is False


def test_record_turn_returns_true_at_threshold():
    d = MemoryDistiller(interval=3)
    d.record_turn("u1")
    d.record_turn("u1")
    assert d.record_turn("u1") is True


def test_reset_count_clears():
    d = MemoryDistiller(interval=2)
    d.record_turn("u1")
    d.reset_count("u1")
    assert d._turn_counts.get("u1", 0) == 0


def test_turn_counts_are_per_user():
    d = MemoryDistiller(interval=5)
    d.record_turn("alice")
    d.record_turn("alice")
    assert d._turn_counts.get("bob", 0) == 0


# ── Distill logic ─────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_distill_stores_new_facts():
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [
        FakeMemoryItem("user", "我叫张三"),
        FakeMemoryItem("assistant", "你好张三"),
        FakeMemoryItem("user", "我是Python工程师"),
        FakeMemoryItem("assistant", "很高兴认识你"),
    ]
    stored = await d.distill("u1", items, _llm_good, ltm)
    assert stored == 2
    assert len(ltm.stored) == 2
    assert ltm.stored[0]["metadata"]["type"] == "fact"
    assert ltm.stored[0]["metadata"]["user_id"] == "u1"


@pytest.mark.asyncio
async def test_distill_skips_other_users_messages():
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [
        FakeMemoryItem("user", "其他用户的消息", user_id="other"),
        FakeMemoryItem("assistant", "其他用户的助手回复", user_id="other"),
    ]
    stored = await d.distill("u1", items, _llm_good, ltm)
    assert stored == 0  # no items for u1


@pytest.mark.asyncio
async def test_distill_empty_facts():
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [FakeMemoryItem("user", "天气真好"), FakeMemoryItem("assistant", "是的")]
    stored = await d.distill("u1", items, _llm_empty, ltm)
    assert stored == 0
    assert len(ltm.stored) == 0


@pytest.mark.asyncio
async def test_distill_bad_json_returns_zero():
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [FakeMemoryItem("user", "test"), FakeMemoryItem("assistant", "test")]
    stored = await d.distill("u1", items, _llm_bad, ltm)
    assert stored == 0


@pytest.mark.asyncio
async def test_distill_dedup_skips_similar_facts():
    d = MemoryDistiller(interval=2, dedup_threshold=0.85)
    ltm = DuplicateLongTermMemory()
    items = [FakeMemoryItem("user", "我叫张三"), FakeMemoryItem("assistant", "好")]
    stored = await d.distill("u1", items, _llm_good, ltm)
    assert stored == 0
    assert len(ltm.stored) == 0


@pytest.mark.asyncio
async def test_distill_resets_count():
    d = MemoryDistiller(interval=2)
    d._turn_counts["u1"] = 5
    ltm = FakeLongTermMemory()
    items = [FakeMemoryItem("user", "test"), FakeMemoryItem("assistant", "test")]
    await d.distill("u1", items, _llm_empty, ltm)
    assert d._turn_counts.get("u1", 0) == 0
