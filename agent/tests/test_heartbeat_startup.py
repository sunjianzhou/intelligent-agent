"""测试飞书心跳巡检任务的启动注册逻辑（TODO-84）。

模拟 fastapi_app.py lifespan 中注册心跳任务的逻辑：
  - feishu_heartbeat_receiver_id 设置时新建 cron 任务
  - 已有 heartbeat_check 任务时跳过重复注册
  - 未设置 receiver_id 时不注册
"""
import pytest

from scheduler.simple_scheduler import SimpleTaskScheduler


@pytest.fixture
def tmp_tasks_file(tmp_path):
    return tmp_path / "tasks.json"


def make_scheduler(tasks_file):
    return SimpleTaskScheduler(check_interval=60, tasks_file=tasks_file)


def _register_heartbeat(scheduler, receiver_id: str, cron: str = "0 * * * *") -> bool:
    """复现 fastapi_app.py lifespan 中的注册逻辑，方便单元测试。"""
    if not receiver_id:
        return False
    existing = any(t.action == "heartbeat_check" for t in scheduler.tasks.values())
    if existing:
        return False
    scheduler.create_cron_task(
        name="飞书心跳巡检",
        action="heartbeat_check",
        cron_expression=cron,
        args={
            "receiver_id": receiver_id,
            "receive_id_type": "open_id",
            "user_id": "java-service",
        },
        description="定期巡检是否需要主动联系用户，安静时段自动跳过",
        tags=["heartbeat", "feishu"],
    )
    return True


# ── receiver_id 设置时应创建任务 ────────────────────────────────

def test_heartbeat_task_created_when_receiver_id_set(tmp_tasks_file):
    sch = make_scheduler(tmp_tasks_file)
    created = _register_heartbeat(sch, receiver_id="ou_abc123")

    assert created is True
    tasks = [t for t in sch.tasks.values() if t.action == "heartbeat_check"]
    assert len(tasks) == 1
    t = tasks[0]
    assert t.schedule_type == "cron"
    assert t.cron_expression == "0 * * * *"
    assert t.args["receiver_id"] == "ou_abc123"
    assert t.args["receive_id_type"] == "open_id"
    assert t.args["user_id"] == "java-service"
    assert "heartbeat" in t.tags
    assert "feishu" in t.tags


# ── receiver_id 为空时不注册 ──────────────────────────────────

def test_no_task_when_receiver_id_empty(tmp_tasks_file):
    sch = make_scheduler(tmp_tasks_file)
    created = _register_heartbeat(sch, receiver_id="")

    assert created is False
    assert not any(t.action == "heartbeat_check" for t in sch.tasks.values())


# ── 重复注册时跳过，不新增 ────────────────────────────────────

def test_no_duplicate_on_second_register(tmp_tasks_file):
    sch = make_scheduler(tmp_tasks_file)
    _register_heartbeat(sch, receiver_id="ou_abc123")
    created_again = _register_heartbeat(sch, receiver_id="ou_abc123")

    assert created_again is False
    tasks = [t for t in sch.tasks.values() if t.action == "heartbeat_check"]
    assert len(tasks) == 1


# ── 自定义 cron 表达式写入任务 ───────────────────────────────

def test_custom_cron_expression_stored(tmp_tasks_file):
    sch = make_scheduler(tmp_tasks_file)
    _register_heartbeat(sch, receiver_id="ou_abc123", cron="0 9,21 * * *")

    tasks = [t for t in sch.tasks.values() if t.action == "heartbeat_check"]
    assert tasks[0].cron_expression == "0 9,21 * * *"


# ── 任务持久化到文件后可恢复 ─────────────────────────────────

def test_heartbeat_task_persisted_and_reloaded(tmp_tasks_file):
    sch1 = make_scheduler(tmp_tasks_file)
    _register_heartbeat(sch1, receiver_id="ou_abc123")

    # 重新加载（模拟服务重启）
    sch2 = make_scheduler(tmp_tasks_file)
    tasks = [t for t in sch2.tasks.values() if t.action == "heartbeat_check"]
    assert len(tasks) == 1
    assert tasks[0].args["receiver_id"] == "ou_abc123"

    # 重启后再次注册应跳过
    created = _register_heartbeat(sch2, receiver_id="ou_abc123")
    assert created is False
    assert len([t for t in sch2.tasks.values() if t.action == "heartbeat_check"]) == 1
