"""Tests for TODO-94 进度恢复协议 — 4 cases:
  1. 检测未完成任务（步骤 < 总数 + 24h 内更新）
  2. 已完成任务不触发（步骤 >= 总数）
  3. 无文件时返回空列表
  4. 过期任务不触发（超过 24h）
  外加 parse/boundary tests。
"""
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ═══════════════════════════════════════════════════════════════════════════
# helpers
# ═══════════════════════════════════════════════════════════════════════════

def make_progress_file(work_dir: Path, filename: str, task_name: str,
                       current_step: int, total_steps: int,
                       last_updated: datetime, next_step: str = "",
                       notes: str = "") -> Path:
    """创建一个标准的 progress_state*.md 文件。"""
    content = f"""# 任务进度
- 任务名：{task_name}
- 当前步骤：{current_step} / {total_steps}
- 最后更新：{last_updated.strftime('%Y-%m-%dT%H:%M:%S')}
- 下一步：{next_step}
- 备注：{notes}
"""
    f = work_dir / filename
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(content, encoding="utf-8")
    return f


# ═══════════════════════════════════════════════════════════════════════════
# Tests
# ═══════════════════════════════════════════════════════════════════════════

class TestParseProgressFile:
    """parse_progress_file 单元测试。"""

    def test_parse_valid_incomplete(self, tmp_path):
        """正常未完成任务应被正确解析。"""
        from core.progress_recovery import parse_progress_file

        now = datetime.now()
        f = make_progress_file(
            tmp_path, "progress_state_task1.md",
            task_name="重构 agent 模块",
            current_step=3, total_steps=10,
            last_updated=now,
            next_step="拆分 tool_dispatcher",
            notes="优先级高",
        )

        result = parse_progress_file(f)
        assert result is not None
        assert result["task_name"] == "重构 agent 模块"
        assert result["current_step"] == 3
        assert result["total_steps"] == 10
        assert result["next_step"] == "拆分 tool_dispatcher"
        assert result["notes"] == "优先级高"
        assert result["file_path"] == str(f)

    def test_parse_completed_task(self, tmp_path):
        """已完成任务也能正确解析（步骤 == 总数）。"""
        from core.progress_recovery import parse_progress_file

        now = datetime.now()
        f = make_progress_file(
            tmp_path, "progress_state_done.md",
            task_name="已完成任务",
            current_step=5, total_steps=5,
            last_updated=now,
        )

        result = parse_progress_file(f)
        assert result is not None
        assert result["current_step"] == 5
        assert result["total_steps"] == 5

    def test_parse_missing_file(self, tmp_path):
        """不存在的文件返回 None。"""
        from core.progress_recovery import parse_progress_file

        result = parse_progress_file(tmp_path / "nonexistent.md")
        assert result is None

    def test_parse_invalid_format(self, tmp_path):
        """格式不正确的文件返回 None（缺少任务名或步骤为 0）。"""
        from core.progress_recovery import parse_progress_file

        f = tmp_path / "bad_progress.md"
        f.write_text("这不是一个进度文件", encoding="utf-8")

        result = parse_progress_file(f)
        assert result is None

    def test_parse_missing_task_name(self, tmp_path):
        """缺少任务名返回 None。"""
        from core.progress_recovery import parse_progress_file

        f = tmp_path / "no_name.md"
        f.write_text(
            "# 任务进度\n"
            "- 当前步骤：3 / 10\n"
            "- 最后更新：2026-07-04T12:00:00\n",
            encoding="utf-8",
        )
        result = parse_progress_file(f)
        assert result is None

    def test_parse_no_total_steps(self, tmp_path):
        """步骤为 0/0 返回 None。"""
        from core.progress_recovery import parse_progress_file

        f = tmp_path / "no_steps.md"
        f.write_text(
            "# 任务进度\n"
            "- 任务名：某任务\n"
            "- 当前步骤：0 / 0\n"
            "- 最后更新：2026-07-04T12:00:00\n",
            encoding="utf-8",
        )
        result = parse_progress_file(f)
        assert result is None


class TestFindIncompleteTasks:
    """find_incomplete_tasks 集成测试。"""

    def test_incomplete_within_24h_is_found(self, tmp_path):
        """24h 内更新且未完成的任务应被检测到。"""
        from core.progress_recovery import find_incomplete_tasks

        now = datetime.now()
        make_progress_file(
            tmp_path, "progress_state_wip.md",
            task_name="未完成的工作",
            current_step=2, total_steps=8,
            last_updated=now - timedelta(hours=3),
            next_step="继续写测试",
        )

        tasks = find_incomplete_tasks(work_dir=tmp_path)
        assert len(tasks) == 1
        assert tasks[0]["task_name"] == "未完成的工作"

    def test_completed_task_not_returned(self, tmp_path):
        """步骤数已达到总数的任务不应返回。"""
        from core.progress_recovery import find_incomplete_tasks

        now = datetime.now()
        make_progress_file(
            tmp_path, "progress_state_done.md",
            task_name="已完成工作",
            current_step=10, total_steps=10,
            last_updated=now,
        )

        tasks = find_incomplete_tasks(work_dir=tmp_path)
        assert len(tasks) == 0

    def test_no_progress_files_returns_empty(self, tmp_path):
        """目录中无 progress 文件时返回空列表。"""
        from core.progress_recovery import find_incomplete_tasks

        tasks = find_incomplete_tasks(work_dir=tmp_path)
        assert tasks == []

    def test_expired_task_not_returned(self, tmp_path):
        """超过 24h 未更新的任务不应返回。"""
        from core.progress_recovery import find_incomplete_tasks

        now = datetime.now()
        make_progress_file(
            tmp_path, "progress_state_old.md",
            task_name="过期任务",
            current_step=1, total_steps=5,
            last_updated=now - timedelta(hours=30),
        )

        tasks = find_incomplete_tasks(work_dir=tmp_path)
        assert len(tasks) == 0

    def test_missing_last_updated_skipped(self, tmp_path):
        """没有最后更新时间的任务被跳过。"""
        from core.progress_recovery import find_incomplete_tasks

        f = tmp_path / "progress_state_nots.md"
        f.write_text(
            "# 任务进度\n"
            "- 任务名：没有时间戳的任务\n"
            "- 当前步骤：2 / 7\n",
            encoding="utf-8",
        )

        tasks = find_incomplete_tasks(work_dir=tmp_path)
        assert len(tasks) == 0

    def test_multiple_incomplete_sorted_by_date(self, tmp_path):
        """多个未完成任务按更新时间倒序排列。"""
        from core.progress_recovery import find_incomplete_tasks

        now = datetime.now()
        make_progress_file(
            tmp_path, "progress_state_newer.md",
            task_name="较新的任务",
            current_step=1, total_steps=5,
            last_updated=now - timedelta(hours=1),
        )
        make_progress_file(
            tmp_path, "progress_state_older.md",
            task_name="较旧的任务",
            current_step=2, total_steps=6,
            last_updated=now - timedelta(hours=5),
        )

        tasks = find_incomplete_tasks(work_dir=tmp_path)
        assert len(tasks) == 2
        assert tasks[0]["task_name"] == "较新的任务"
        assert tasks[1]["task_name"] == "较旧的任务"

    def test_non_matching_files_ignored(self, tmp_path):
        """不匹配 progress_state*.md 模式的文件被忽略。"""
        from core.progress_recovery import find_incomplete_tasks

        now = datetime.now()
        # 创建不匹配的文件
        (tmp_path / "README.md").write_text("not a progress file")
        (tmp_path / "other_file.txt").write_text("also not")

        # 创建匹配的进度文件
        make_progress_file(
            tmp_path, "progress_state_valid.md",
            task_name="有效任务",
            current_step=1, total_steps=3,
            last_updated=now,
        )

        tasks = find_incomplete_tasks(work_dir=tmp_path)
        assert len(tasks) == 1

    def test_nonexistent_dir_returns_empty(self, tmp_path):
        """不存在的目录返回空列表。"""
        from core.progress_recovery import find_incomplete_tasks

        tasks = find_incomplete_tasks(work_dir=tmp_path / "nonexistent")
        assert tasks == []


class TestBuildRecoveryContext:
    """build_recovery_context 输出测试。"""

    def test_build_with_single_task(self):
        """单个未完成任务应生成完整的恢复上下文。"""
        from core.progress_recovery import build_recovery_context

        now = datetime.now()
        tasks = [{
            "task_name": "重构 agent",
            "current_step": 3,
            "total_steps": 10,
            "last_updated": now,
            "next_step": "拆分模块",
            "notes": "P0 优先级",
        }]

        context = build_recovery_context(tasks)
        assert "[PROGRESS RECOVERY]" in context
        assert "重构 agent" in context
        assert "3/10" in context
        assert "拆分模块" in context
        assert "P0 优先级" in context
        assert "从中断点继续" in context

    def test_build_with_empty_tasks(self):
        """空任务列表返回空字符串。"""
        from core.progress_recovery import build_recovery_context

        context = build_recovery_context([])
        assert context == ""

    def test_build_max_two_tasks(self):
        """最多展示 2 个任务。"""
        from core.progress_recovery import build_recovery_context

        now = datetime.now()
        tasks = [
            {
                "task_name": f"任务 {i}",
                "current_step": 1,
                "total_steps": 5,
                "last_updated": now,
                "next_step": f"步骤 {i}",
                "notes": "",
            }
            for i in range(1, 5)
        ]

        context = build_recovery_context(tasks)
        assert "任务 1" in context
        assert "任务 2" in context
        assert "任务 3" not in context  # 第 3 个以后不展示
        assert "任务 4" not in context

    def test_build_without_next_step_and_notes(self):
        """没有下一步和备注时也能正常构建。"""
        from core.progress_recovery import build_recovery_context

        now = datetime.now()
        tasks = [{
            "task_name": "简单任务",
            "current_step": 1,
            "total_steps": 3,
            "last_updated": now,
            "next_step": "",
            "notes": "",
        }]

        context = build_recovery_context(tasks)
        assert "[PROGRESS RECOVERY]" in context
        assert "简单任务" in context
        # 没有下一步就不应有该行
        assert "下一步：" not in context
        assert "备注：" not in context
