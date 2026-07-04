"""Tests for TODO-93 失职自查钩子 — 3 cases:
  1. heart_record 写入后验证（append 后读回确认、delete 后读回确认）
  2. feishu_client 推送前后验证（内容验证、message_id 重试）
  3. scheduler 任务执行后 file 写入验证
"""
import os
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ═══════════════════════════════════════════════════════════════════════════
# 1. heart_record 写入后验证
# ═══════════════════════════════════════════════════════════════════════════

class TestHeartRecordWriteVerify:
    """验证 append/delete 后读回确认机制。"""

    @pytest.fixture
    def heart_tool(self, tmp_path, monkeypatch):
        """创建 HeartRecordTool，HEART_MD_PATH 指向临时目录。"""
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
        from tools.builtin_tools.heart_record import HeartRecordTool
        return HeartRecordTool(), tmp_heart

    def test_append_verify_content_present(self, heart_tool):
        """append 后文件内容应包含新条目（verify 通过时不报错）。"""
        tool, tmp_heart = heart_tool
        result = tool.execute(
            action="append", content="这是一条验证测试心证",
            category="主人心证"
        )
        assert result["ok"] is True

        # 文件确实包含新内容
        text = tmp_heart.read_text(encoding="utf-8")
        assert "这是一条验证测试心证" in text

    def test_delete_verify_content_absent(self, heart_tool):
        """delete 后文件内容应不含已删除条目。"""
        tool, tmp_heart = heart_tool
        tool.execute(action="append", content="待删心证内容", category="主人心证")

        lst = tool.execute(action="list")
        entry_id = lst["entries"][0]["id"]

        result = tool.execute(action="delete", id=str(entry_id))
        assert result["ok"] is True

        # 文件确实不包含已删除内容
        text = tmp_heart.read_text(encoding="utf-8")
        assert "待删心证内容" not in text

    def test_verify_write_contains_missing_file(self, tmp_path):
        """_verify_write_contains 对不存在的文件返回 False。"""
        from tools.builtin_tools.heart_record import _verify_write_contains
        nonexistent = tmp_path / "nonexistent.md"
        assert _verify_write_contains(nonexistent, "anything") is False

    def test_verify_write_excludes_still_present(self, tmp_path):
        """_verify_write_excludes 对仍包含指定内容的文件返回 False。"""
        from tools.builtin_tools.heart_record import _verify_write_excludes
        f = tmp_path / "test.md"
        f.write_text("这个文件包含敏感内容", encoding="utf-8")
        assert _verify_write_excludes(f, "敏感内容") is False

    def test_verify_write_excludes_success(self, tmp_path):
        """_verify_write_excludes 对不含指定内容的文件返回 True。"""
        from tools.builtin_tools.heart_record import _verify_write_excludes
        f = tmp_path / "test.md"
        f.write_text("这个文件只有安全内容", encoding="utf-8")
        assert _verify_write_excludes(f, "敏感内容") is True


# ═══════════════════════════════════════════════════════════════════════════
# 2. feishu_client 推送前后验证
# ═══════════════════════════════════════════════════════════════════════════

class TestFeishuSendVerify:
    """验证 IM 消息发送前后的检查钩子。"""

    def test_verify_message_content_empty_text_warns(self):
        """text 消息内容为空时应 logger.warning。"""
        from im.feishu_client import _verify_message_content
        from loguru import logger

        with patch.object(logger, "warning") as mock_warn:
            _verify_message_content("text", {"text": ""})
        assert mock_warn.called
        # 至少有一次 warning 提到"内容为空"
        calls_text = str([c[0][0] for c in mock_warn.call_args_list])
        assert "内容为空" in calls_text

    def test_verify_message_content_long_text_warns(self):
        """text 消息过长时应 logger.warning。"""
        from im.feishu_client import _verify_message_content, _MAX_TEXT_LENGTH
        from loguru import logger

        long_text = "x" * (_MAX_TEXT_LENGTH + 100)
        with patch.object(logger, "warning") as mock_warn:
            _verify_message_content("text", {"text": long_text})
        assert mock_warn.called
        calls_text = str([c[0][0] for c in mock_warn.call_args_list])
        assert "消息过长" in calls_text

    def test_verify_message_content_ok_no_warning(self):
        """正常 text 消息不应产生 warning。"""
        from im.feishu_client import _verify_message_content
        from loguru import logger

        with patch.object(logger, "warning") as mock_warn:
            _verify_message_content("text", {"text": "你好，这是一条正常消息"})
        assert not mock_warn.called

    def test_verify_message_content_null_content(self):
        """空 content dict 时应 warning。"""
        from im.feishu_client import _verify_message_content
        from loguru import logger

        with patch.object(logger, "warning") as mock_warn:
            _verify_message_content("text", {})
        assert mock_warn.called
        calls_text = str([c[0][0] for c in mock_warn.call_args_list])
        assert "content 为空" in calls_text

    def test_extract_message_id_valid(self):
        """正常响应中应能提取 message_id。"""
        from im.feishu_client import _extract_message_id

        response = {
            "code": 0,
            "msg": "success",
            "data": {"message_id": "om_test123456"}
        }
        assert _extract_message_id(response) == "om_test123456"

    def test_extract_message_id_missing(self):
        """data 中没有 message_id 时返回 None。"""
        from im.feishu_client import _extract_message_id

        response = {"code": 0, "msg": "success", "data": {}}
        assert _extract_message_id(response) is None

    def test_extract_message_id_no_data(self):
        """响应中没有 data 字段时返回 None。"""
        from im.feishu_client import _extract_message_id

        response = {"code": 0, "msg": "success"}
        assert _extract_message_id(response) is None

    def test_do_send_retry_on_missing_message_id(self, monkeypatch):
        """message_id 缺失时应触发重试（_do_send 被调用两次）。"""
        from im.feishu_client import FeishuIMTool

        call_count = 0
        first_call_no_msgid = {"code": 0, "msg": "success", "data": {}}
        second_call_with_msgid = {
            "code": 0, "msg": "success",
            "data": {"message_id": "om_retry_success"}
        }

        def mock_do_send(self, receiver_id, msg_type, content, receive_id_type="open_id"):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return first_call_no_msgid
            return second_call_with_msgid

        monkeypatch.setattr(FeishuIMTool, "_do_send", mock_do_send)

        tool = FeishuIMTool()
        result = tool.execute(
            receiver_id="ou_test",
            msg_type="text",
            content={"text": "测试消息"},
        )
        assert call_count == 2
        assert result == second_call_with_msgid


# ═══════════════════════════════════════════════════════════════════════════
# 3. scheduler 任务执行后 file 写入验证
# ═══════════════════════════════════════════════════════════════════════════

class TestSchedulerFileVerify:
    """验证 scheduler 任务执行后的文件写入验证钩子。"""

    @pytest.fixture
    def sched(self, tmp_path):
        """创建不启动后台线程的调度器实例。"""
        from scheduler.simple_scheduler import SimpleTaskScheduler
        tasks_file = tmp_path / "tasks.json"
        s = SimpleTaskScheduler(check_interval=60, tasks_file=tasks_file)
        s._memory_md_path = tmp_path / "MEMORY.md"
        return s

    def test_verify_file_nonempty_exists_and_nonempty(self, tmp_path):
        """文件存在且非空时不产生 warning。"""
        from scheduler.simple_scheduler import SimpleTaskScheduler
        from loguru import logger

        f = tmp_path / "test.md"
        f.write_text("hello world", encoding="utf-8")

        with patch.object(logger, "warning") as mock_warn:
            SimpleTaskScheduler._verify_file_nonempty(f, tag="test")
        assert not mock_warn.called

    def test_verify_file_nonempty_missing(self, tmp_path):
        """文件不存在时产生 warning。"""
        from scheduler.simple_scheduler import SimpleTaskScheduler
        from loguru import logger

        f = tmp_path / "nonexistent.md"

        with patch.object(logger, "warning") as mock_warn:
            SimpleTaskScheduler._verify_file_nonempty(f, tag="test")
        assert mock_warn.called
        calls_text = str([c[0][0] for c in mock_warn.call_args_list])
        assert "文件不存在" in calls_text

    def test_verify_file_nonempty_empty_file(self, tmp_path):
        """文件为空时产生 warning。"""
        from scheduler.simple_scheduler import SimpleTaskScheduler
        from loguru import logger

        f = tmp_path / "empty.md"
        f.write_text("", encoding="utf-8")

        with patch.object(logger, "warning") as mock_warn:
            SimpleTaskScheduler._verify_file_nonempty(f, tag="test")
        assert mock_warn.called
        calls_text = str([c[0][0] for c in mock_warn.call_args_list])
        assert "文件为空" in calls_text

    def test_heartbeat_check_triggers_memory_verify(self, sched, tmp_path):
        """heartbeat_check 任务执行后应验证 MEMORY.md。"""
        memory_md = tmp_path / "MEMORY.md"
        memory_md.write_text("# MEMORY\n\n- some entry\n", encoding="utf-8")
        sched._memory_md_path = memory_md

        from scheduler.simple_models import SimpleTask, SimpleTaskStatus
        from loguru import logger

        task = SimpleTask(
            name="test-hb",
            action="heartbeat_check",
            args={"receiver_id": "ou_test"},
            schedule_type="immediate",
        )
        task.status = SimpleTaskStatus.COMPLETED

        with patch.object(logger, "warning") as mock_warn:
            sched._verify_task_file_write(task, {"success": True, "sent": False})
        # 文件存在且非空，不应有 warning
        assert not mock_warn.called

    def test_heartbeat_check_missing_memory_md(self, sched, tmp_path):
        """heartbeat_check 后 MEMORY.md 不存在时应 warning。"""
        from scheduler.simple_models import SimpleTask, SimpleTaskStatus
        from loguru import logger

        memory_md = tmp_path / "MEMORY.md"
        # 不创建文件
        sched._memory_md_path = memory_md

        task = SimpleTask(
            name="test-hb",
            action="heartbeat_check",
            args={"receiver_id": "ou_test"},
            schedule_type="immediate",
        )
        task.status = SimpleTaskStatus.COMPLETED

        with patch.object(logger, "warning") as mock_warn:
            sched._verify_task_file_write(task, {"success": True, "sent": False})
        assert mock_warn.called
        calls_text = str([c[0][0] for c in mock_warn.call_args_list])
        assert "文件不存在" in calls_text

    def test_result_file_path_verified(self, sched, tmp_path):
        """result 中包含 file_path 时应验证该文件。"""
        from scheduler.simple_models import SimpleTask, SimpleTaskStatus
        from loguru import logger

        output = tmp_path / "output.txt"
        output.write_text("result content", encoding="utf-8")

        task = SimpleTask(
            name="test-file-task",
            action="log",
            args={"message": "test"},
            schedule_type="immediate",
        )
        task.status = SimpleTaskStatus.COMPLETED

        with patch.object(logger, "warning") as mock_warn:
            sched._verify_task_file_write(
                task,
                {"message": "done", "file_path": str(output)}
            )
        # 文件存在且非空，不应有 warning
        assert not mock_warn.called
