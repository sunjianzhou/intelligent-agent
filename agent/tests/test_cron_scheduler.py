"""Cron 调度类型测试（无 Ollama，不启动调度器线程）"""
import pytest
from datetime import datetime, timedelta
from scheduler.simple_models import SimpleTask, SimpleTaskStatus


def _make_task(cron_expression: str, last_run=None) -> SimpleTask:
    task = SimpleTask(
        id="test",
        name="cron-test",
        action="log",
        schedule_type="cron",
    )
    task.cron_expression = cron_expression
    if last_run:
        task.last_run = last_run
    return task


class TestCronShouldRun:

    def test_every_minute_triggers_after_one_minute(self):
        """每分钟执行：上次运行 61 秒前，should_run == True"""
        task = _make_task("* * * * *", last_run=datetime.now() - timedelta(seconds=61))
        assert task.should_run() is True

    def test_every_minute_not_yet(self):
        """每分钟执行：上次运行 10 秒前，should_run == False"""
        task = _make_task("* * * * *", last_run=datetime.now() - timedelta(seconds=10))
        assert task.should_run() is False

    def test_no_last_run_uses_created_at(self):
        """无 last_run 时，以 created_at 为基准；若 created_at 1 分钟前，should_run == True"""
        task = _make_task("* * * * *")
        task.created_at = datetime.now() - timedelta(minutes=2)
        assert task.should_run() is True

    def test_invalid_expression_returns_false(self):
        """无效 cron 表达式不崩溃，返回 False"""
        task = _make_task("NOT_A_CRON_EXPRESSION")
        assert task.should_run() is False

    def test_completed_task_does_not_run(self):
        """COMPLETED 状态不触发"""
        task = _make_task("* * * * *", last_run=datetime.now() - timedelta(minutes=2))
        task.status = SimpleTaskStatus.COMPLETED
        assert task.should_run() is False

    def test_running_task_does_not_run(self):
        """RUNNING 状态不触发（防止并发）"""
        task = _make_task("* * * * *", last_run=datetime.now() - timedelta(minutes=2))
        task.status = SimpleTaskStatus.RUNNING
        assert task.should_run() is False


class TestCronNextRun:

    def test_next_run_calculated_correctly(self):
        """每小时整点：下次运行在下一个整点"""
        base = datetime(2026, 1, 1, 10, 0, 0)  # 10:00:00
        task = _make_task("0 * * * *", last_run=base)
        next_run = task.calculate_next_run()
        assert next_run == datetime(2026, 1, 1, 11, 0, 0)

    def test_every_5_minutes(self):
        """每 5 分钟：10:00 后下次是 10:05"""
        base = datetime(2026, 1, 1, 10, 0, 0)
        task = _make_task("*/5 * * * *", last_run=base)
        next_run = task.calculate_next_run()
        assert next_run == datetime(2026, 1, 1, 10, 5, 0)

    def test_invalid_expression_returns_none(self):
        """无效表达式返回 None 而不是抛异常"""
        task = _make_task("bad expr")
        assert task.calculate_next_run() is None
