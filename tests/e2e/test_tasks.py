"""E2E: 任务管理 — 列表 / 创建 / 取消 / 删除 / 统计 / actions。

Python 侧字段说明：
  创建 payload 使用 args（不是 action_params）
  响应 ID 在 data["task"]["id"]（不是顶层 task_id）
"""
import pytest


def _extract_task_id(data: dict) -> str:
    return (data.get("task_id") or data.get("id")
            or data.get("task", {}).get("id") or "")


def test_tasks_list(client):
    r = client.get("/api/tasks/list?limit=20")
    assert r.status_code == 200
    data = r.json()
    assert "tasks" in data
    assert isinstance(data["tasks"], list)


def test_tasks_stats(client):
    r = client.get("/api/tasks/stats")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)


def test_tasks_actions(client):
    r = client.get("/api/tasks/actions")
    assert r.status_code == 200
    data = r.json()
    assert "actions" in data or isinstance(data, dict)


def test_task_create_and_delete(client):
    payload = {
        "name": "E2E测试任务",
        "description": "由E2E测试创建，可安全删除",
        "action": "log_action",
        "args": {"message": "E2E test task"},
        "schedule_type": "delay",
        "delay_seconds": 3600,
    }
    r = client.post("/api/tasks/create", json=payload)
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True, f"创建失败: {data}"
    task_id = _extract_task_id(data)
    assert task_id, f"未返回 task_id: {data}"

    # 删除
    r2 = client.delete(f"/api/tasks/{task_id}")
    assert r2.status_code == 200
    d2 = r2.json()
    assert d2.get("success") is not False


def test_task_create_and_cancel(client):
    payload = {
        "name": "E2E取消任务",
        "description": "由E2E测试创建，会被取消",
        "action": "log_action",
        "args": {"message": "cancel test"},
        "schedule_type": "delay",
        "delay_seconds": 3600,
    }
    r = client.post("/api/tasks/create", json=payload)
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True, f"创建失败: {data}"
    task_id = _extract_task_id(data)
    assert task_id, f"未返回 task_id: {data}"

    # 取消
    r2 = client.post(f"/api/tasks/{task_id}/cancel")
    assert r2.status_code == 200

    # 清理
    client.delete(f"/api/tasks/{task_id}")


def test_task_update(client):
    payload = {
        "name": "E2E更新任务",
        "description": "will be updated",
        "action": "log_action",
        "args": {"message": "update test"},
        "schedule_type": "delay",
        "delay_seconds": 3600,
    }
    r = client.post("/api/tasks/create", json=payload)
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True, f"创建失败: {data}"
    task_id = _extract_task_id(data)
    assert task_id, f"未返回 task_id: {data}"

    # 更新描述
    r2 = client.patch(f"/api/tasks/{task_id}", json={"description": "updated by E2E"})
    assert r2.status_code == 200

    # 清理
    client.delete(f"/api/tasks/{task_id}")
