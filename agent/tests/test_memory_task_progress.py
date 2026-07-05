"""Tests for TODO-95: 跨 session 记忆继承增强（LTM 任务进度感知）。

覆盖：
- 蒸馏时识别任务进度关键词 → 自动打 task_progress 标签
- LongTermMemory.retrieve() type_filter 过滤
"""
import sys
import os
import pytest
import tempfile
import shutil

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from memory.distiller import MemoryDistiller, _detect_task_progress


# ── Fixtures ──────────────────────────────────────────────────────────────────

class FakeLongTermMemory:
    """Minimal stub so tests don't need ChromaDB running."""
    def __init__(self):
        self.stored = []

    def store(self, content, metadata=None, importance=0.5):
        self.stored.append({
            "content": content, "metadata": metadata or {}, "importance": importance,
        })

    def retrieve(self, query):
        return []  # no duplicates by default


class FakeMemoryItem:
    def __init__(self, role: str, content: str, user_id: str = "u1",
                 message_id: str = None):
        self.content = content
        self.metadata = {"role": role, "user_id": user_id}
        if message_id:
            self.metadata["message_id"] = message_id


async def _llm_good(messages):
    return '{"facts": ["用户正在开发智能代理项目", "任务3已完成"]}'


# ── 关键词检测 ──────────────────────────────────────────────────────────────

@pytest.mark.parametrize("keyword", [
    "[TASK_DONE]",
    "[TASK_BLOCKED]",
    "progress_state",
    "scheduler",
])
def test_detect_task_progress_keyword(keyword):
    """每个关键词都应被识别为任务进度上下文。"""
    items = [
        FakeMemoryItem("assistant", f"好的，{keyword} 任务已标记完成"),
        FakeMemoryItem("user", "继续下一个"),
    ]
    assert _detect_task_progress(items) is True


def test_detect_task_progress_no_keyword():
    """普通闲聊不应被识别为任务进度。"""
    items = [
        FakeMemoryItem("user", "今天天气怎么样"),
        FakeMemoryItem("assistant", "今天天气不错"),
    ]
    assert _detect_task_progress(items) is False


def test_detect_task_progress_empty():
    """空消息列表不应报错。"""
    assert _detect_task_progress([]) is False


def test_detect_task_progress_none_content():
    """content 为 None 的消息不应报错。"""
    class NoneContentItem:
        content = None
        metadata = {"role": "user"}
    assert _detect_task_progress([NoneContentItem()]) is False


# ── 蒸馏标签 ────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_distill_tags_task_progress_with_keyword():
    """消息窗口含进度关键词时，蒸馏事实应标记为 task_progress。"""
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [
        FakeMemoryItem("user", "继续项目开发"),
        FakeMemoryItem("assistant", "好的，[TASK_DONE:task-001] 任务已完成"),
        FakeMemoryItem("user", "下一个任务是什么"),
        FakeMemoryItem("assistant", "根据 scheduler 的调度，下一项是代码审查"),
    ]
    stored = await d.distill("u1", items, _llm_good, ltm)
    assert stored == 2
    for entry in ltm.stored:
        assert entry["metadata"]["type"] == "task_progress", (
            f"期望 type=task_progress，实际: {entry['metadata']['type']}"
        )
        assert entry["metadata"]["user_id"] == "u1"


@pytest.mark.asyncio
async def test_distill_tags_fact_without_keyword():
    """消息窗口不含进度关键词时，蒸馏事实应标记为 fact（默认）。"""
    d = MemoryDistiller(interval=2)
    ltm = FakeLongTermMemory()
    items = [
        FakeMemoryItem("user", "我喜欢喝咖啡"),
        FakeMemoryItem("assistant", "好的我记住了"),
        FakeMemoryItem("user", "我叫霖君"),
        FakeMemoryItem("assistant", "霖君你好"),
    ]
    stored = await d.distill("u1", items, _llm_good, ltm)
    assert stored == 2
    for entry in ltm.stored:
        assert entry["metadata"]["type"] == "fact", (
            f"期望 type=fact，实际: {entry['metadata']['type']}"
        )


# ── LTM type_filter 检索 ────────────────────────────────────────────────────

@pytest.fixture
def ltm_memory():
    """使用内存向量数据库的 LongTermMemory，避免 ChromaDB 依赖。"""
    from memory.long_term import LongTermMemory
    tmpdir = tempfile.mkdtemp()
    mem = LongTermMemory(
        name="test_task_progress",
        vector_db_type="memory",
        persist_dir=tmpdir,
    )
    yield mem
    shutil.rmtree(tmpdir, ignore_errors=True)


def test_retrieve_type_filter_filters_correctly(ltm_memory):
    """type_filter="task_progress" 应只返回该类型的记忆。"""
    # 存储 2 条 task_progress + 2 条 fact
    ltm_memory.store("项目进度：智能代理核心模块已完成，测试通过率95%", metadata={
        "type": "task_progress", "user_id": "u1",
    })
    ltm_memory.store("项目进度：前端任务管理面板重构进行中", metadata={
        "type": "task_progress", "user_id": "u1",
    })
    ltm_memory.store("用户喜欢喝咖啡", metadata={
        "type": "fact", "user_id": "u1",
    })
    ltm_memory.store("用户叫霖君，是Python工程师", metadata={
        "type": "fact", "user_id": "u1",
    })

    # 不带 filter → 用 task_progress 内容查询，应命中自身
    all_results = ltm_memory.retrieve("智能代理核心模块测试通过率", limit=10)
    assert len(all_results) >= 1  # 至少命中一条 task_progress 记忆

    # 带 type_filter → 只返回 task_progress
    tp_results = ltm_memory.retrieve(
        "智能代理核心模块", limit=10, type_filter="task_progress",
    )
    assert len(tp_results) >= 1, f"期望 >=1 条 task_progress，实际 {len(tp_results)}"
    for r in tp_results:
        assert r.memory.metadata.get("type") == "task_progress", (
            f"type_filter 应只返回 task_progress，实际: "
            f"{r.memory.metadata.get('type')}"
        )

    # 带 type_filter → fact
    fact_results = ltm_memory.retrieve("喜欢喝咖啡", limit=10, type_filter="fact")
    assert len(fact_results) >= 1, f"期望 >=1 条 fact，实际 {len(fact_results)}"
    for r in fact_results:
        assert r.memory.metadata.get("type") == "fact"
