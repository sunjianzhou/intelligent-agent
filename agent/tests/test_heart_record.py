"""Tests for HeartRecordTool — 5 cases: append/list/delete + category grouping + backup."""
import os
import sys
import pytest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


@pytest.fixture
def heart_tool(tmp_path, monkeypatch):
    """创建 HeartRecordTool 实例，HEART_MD_PATH 指向临时目录下的 heart.md。"""
    from tools.builtin_tools import heart_record as hr_module

    tmp_heart = tmp_path / "heart.md"
    tmp_heart.write_text(
        "# 心证铁卷\n\n"
        "## 主人心证\n"
        "<!-- 用户主动标记的永久记忆 -->\n\n"
        "## 主人教诲\n"
        "<!-- 用户对 Agent 的长期行为指令 -->\n\n"
        "## 智能体对主人的承诺\n"
        "<!-- Agent 对用户的承诺 -->\n\n"
        "## 主人对智能体的承诺\n"
        "<!-- 用户对 Agent 的承诺 -->\n",
        encoding="utf-8",
    )

    monkeypatch.setattr(hr_module, "HEART_MD_PATH", tmp_heart)
    # 重新 import 以使 monkeypatch 生效（模块级常量已加载，需要直接 patch 引用）
    from tools.builtin_tools.heart_record import HeartRecordTool

    tool = HeartRecordTool()
    return tool, tmp_heart


# ---------------------------------------------------------------------------
# 1. append
# ---------------------------------------------------------------------------

def test_append_adds_entry_to_section(heart_tool):
    tool, tmp_heart = heart_tool
    result = tool.execute(action="append", content="用户喜欢短回复", category="主人心证")
    assert result["ok"] is True
    assert result["section"] == "主人心证"

    text = tmp_heart.read_text(encoding="utf-8")
    assert "用户喜欢短回复" in text
    # 确认条目前有日期
    assert "- [20" in text  # 日期格式 YYYY-MM-DD
    assert "主人心证" in text


def test_append_rejects_invalid_category(heart_tool):
    tool, _ = heart_tool
    result = tool.execute(action="append", content="某内容", category="不存在的分区")
    assert "error" in result


def test_append_rejects_empty_content(heart_tool):
    tool, _ = heart_tool
    result = tool.execute(action="append", content="", category="主人心证")
    assert "error" in result


# ---------------------------------------------------------------------------
# 2. list
# ---------------------------------------------------------------------------

def test_list_returns_entries(heart_tool):
    tool, _ = heart_tool
    # 先追加两条
    tool.execute(action="append", content="第一条心证", category="主人心证")
    tool.execute(action="append", content="第二条心证", category="主人教诲")

    result = tool.execute(action="list")
    assert result["total"] == 2
    assert len(result["entries"]) == 2
    contents = [e["content"] for e in result["entries"]]
    assert "第一条心证" in contents
    assert "第二条心证" in contents


def test_list_filter_by_category(heart_tool):
    tool, _ = heart_tool
    tool.execute(action="append", content="心证A", category="主人心证")
    tool.execute(action="append", content="教诲B", category="主人教诲")

    result = tool.execute(action="list", category="主人心证")
    assert result["total"] == 1
    assert result["entries"][0]["content"] == "心证A"


# ---------------------------------------------------------------------------
# 3. delete
# ---------------------------------------------------------------------------

def test_delete_removes_entry(heart_tool):
    tool, _ = heart_tool
    tool.execute(action="append", content="待删除内容", category="主人心证")

    # 获取 id
    lst = tool.execute(action="list")
    assert lst["total"] == 1
    entry_id = lst["entries"][0]["id"]

    # 删除
    result = tool.execute(action="delete", id=str(entry_id))
    assert result["ok"] is True

    # 确认已删除
    lst2 = tool.execute(action="list")
    assert lst2["total"] == 0


def test_delete_nonexistent_id(heart_tool):
    tool, _ = heart_tool
    result = tool.execute(action="delete", id="99999")
    assert "error" in result


# ---------------------------------------------------------------------------
# 4. category grouping
# ---------------------------------------------------------------------------

def test_category_grouping(heart_tool):
    """不同分区的条目应在各自 ## 标题下归类。"""
    tool, tmp_heart = heart_tool
    tool.execute(action="append", content="心证条目1", category="主人心证")
    tool.execute(action="append", content="教诲条目1", category="主人教诲")
    tool.execute(action="append", content="心证条目2", category="主人心证")

    text = tmp_heart.read_text(encoding="utf-8")

    # 心证条目应在「主人心证」标题附近
    idx_section = text.index("## 主人心证")
    idx_entry1 = text.index("心证条目1")
    idx_entry2 = text.index("心证条目2")
    idx_jiaohui = text.index("主人教诲")

    # 两个心证条目在分区标题之后
    assert idx_section < idx_entry1
    assert idx_section < idx_entry2
    # 教诲条目在教诲分区内
    assert idx_jiaohui < text.index("教诲条目1")


# ---------------------------------------------------------------------------
# 5. backup
# ---------------------------------------------------------------------------

def test_append_creates_backup(heart_tool):
    """写入前应创建 .bak.1 备份。"""
    tool, tmp_heart = heart_tool
    # 先写一条，产生初始内容
    tool.execute(action="append", content="原始内容", category="主人心证")

    # 确认有内容后再追加一条，检查是否产生备份
    tool.execute(action="append", content="新内容", category="主人心证")

    bak1 = tmp_heart.with_name("heart.md.bak.1")
    # 轮转备份在写入前发生，所以 bak.1 应该存在
    assert bak1.exists(), f"期望备份文件 {bak1} 存在"
    bak_content = bak1.read_text(encoding="utf-8")
    assert "原始内容" in bak_content
