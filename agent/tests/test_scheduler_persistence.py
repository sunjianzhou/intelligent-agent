"""
测试任务调度器 JSON 持久化功能。
无需 Ollama，纯内存 + 临时文件，可跑在 CI 里。
"""
import json
import tempfile
import time
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from scheduler.simple_scheduler import SimpleTaskScheduler
from scheduler.simple_models import SimpleTask, SimpleTaskStatus


# ── Fixtures ─────────────────────────────────────────────────

@pytest.fixture
def tmp_tasks_file(tmp_path):
    """每个测试独立的临时持久化文件"""
    return tmp_path / "tasks.json"


def make_scheduler(tasks_file):
    """创建一个不启动后台线程的调度器（测试用）"""
    return SimpleTaskScheduler(check_interval=60, tasks_file=tasks_file)


# ── 测试：创建 → 持久化 ──────────────────────────────────────

def test_create_task_writes_file(tmp_tasks_file):
    """创建任务后，持久化文件应存在且包含该任务"""
    sched = make_scheduler(tmp_tasks_file)
    sched.create_task("测试任务", action="log", args={"message": "hello"})

    assert tmp_tasks_file.exists(), "任务文件应已被创建"
    data = json.loads(tmp_tasks_file.read_text(encoding="utf-8"))
    assert len(data) == 1
    assert data[0]["name"] == "测试任务"


def test_multiple_tasks_all_persisted(tmp_tasks_file):
    """多个任务均写入文件"""
    sched = make_scheduler(tmp_tasks_file)
    sched.create_task("任务A", action="log", args={"message": "A"})
    sched.create_task("任务B", action="log", args={"message": "B"},
                      schedule_type="delay", delay_seconds=60)

    data = json.loads(tmp_tasks_file.read_text(encoding="utf-8"))
    names = {t["name"] for t in data}
    assert names == {"任务A", "任务B"}


# ── 测试：重启恢复（最关键路径）─────────────────────────────

def test_restart_recovers_pending_tasks(tmp_tasks_file):
    """
    模拟服务重启：
    第一个调度器创建任务后，第二个调度器从同一文件加载，
    任务应该还在 pending 状态。
    """
    # 第一次启动：创建任务
    sched1 = make_scheduler(tmp_tasks_file)
    task = sched1.create_task("重要提醒", action="log",
                               args={"message": "别忘了"},
                               schedule_type="delay", delay_seconds=300)
    task_id = task.id

    # 模拟重启：用相同文件路径创建新调度器
    sched2 = make_scheduler(tmp_tasks_file)

    assert task_id in sched2.tasks, "重启后任务应从文件恢复"
    recovered = sched2.tasks[task_id]
    assert recovered.name == "重要提醒"
    assert recovered.status == SimpleTaskStatus.PENDING


def test_restart_resets_running_tasks_to_pending(tmp_tasks_file):
    """
    重启前处于 running 状态的任务（被中断）应恢复为 pending，
    而不是永远卡在 running。
    """
    sched1 = make_scheduler(tmp_tasks_file)
    task = sched1.create_task("中断的任务", action="log", args={})

    # 直接修改状态模拟"正在运行时崩溃"
    sched1.tasks[task.id].status = SimpleTaskStatus.RUNNING
    sched1._save_tasks()

    # 重启
    sched2 = make_scheduler(tmp_tasks_file)
    assert task.id in sched2.tasks
    assert sched2.tasks[task.id].status == SimpleTaskStatus.PENDING, \
        "running 状态应在重启时重置为 pending"


# ── 测试：删除 / 取消后文件更新 ─────────────────────────────

def test_delete_task_removes_from_file(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    task = sched.create_task("临时任务", action="log", args={})
    task_id = task.id

    sched.delete_task(task_id)

    data = json.loads(tmp_tasks_file.read_text(encoding="utf-8"))
    ids = [t["id"] for t in data]
    assert task_id not in ids, "删除后任务不应出现在文件中"


def test_cancel_task_persists_cancelled_status(tmp_tasks_file):
    sched = make_scheduler(tmp_tasks_file)
    task = sched.create_task("待取消", action="log", args={})
    sched.cancel_task(task.id)

    data = json.loads(tmp_tasks_file.read_text(encoding="utf-8"))
    task_data = next(t for t in data if t["id"] == task.id)
    assert task_data["status"] == "cancelled"


# ── 测试：from_dict 数据完整性 ───────────────────────────────

def test_roundtrip_preserves_all_fields(tmp_tasks_file):
    """to_dict → from_dict 往返应保留所有关键字段"""
    sched = make_scheduler(tmp_tasks_file)
    original = sched.create_task(
        name="往返测试",
        action="log",
        args={"message": "test"},
        schedule_type="interval",
        interval_seconds=120,
    )

    # 通过重启触发 from_dict
    sched2 = make_scheduler(tmp_tasks_file)
    recovered = sched2.tasks[original.id]

    assert recovered.name == original.name
    assert recovered.action == original.action
    assert recovered.args == original.args
    assert recovered.schedule_type == original.schedule_type
    assert recovered.interval_seconds == original.interval_seconds


# ── 测试：文件不存在时调度器正常启动 ────────────────────────

def test_no_file_starts_empty(tmp_tasks_file):
    """首次启动（无持久化文件）应正常初始化，任务列表为空"""
    assert not tmp_tasks_file.exists()
    sched = make_scheduler(tmp_tasks_file)
    assert len(sched.tasks) == 0
