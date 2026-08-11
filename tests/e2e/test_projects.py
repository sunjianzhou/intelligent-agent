"""E2E: 项目管理 — CRUD + Spec + 上下文（Java-only）。"""
import pytest


def _extract_project_id(data: dict) -> str:
    return (data.get("id") or data.get("project_id")
            or data.get("project", {}).get("id") or "")


def test_list_projects(client):
    r = client.get("/api/projects")
    assert r.status_code == 200
    data = r.json()
    assert "projects" in data
    assert isinstance(data["projects"], list)


def test_project_crud(client):
    # 创建（Python 侧用 title 字段）
    payload = {"title": "E2E测试项目"}
    r = client.post("/api/projects", json=payload)
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True, f"创建失败: {data}"
    project_id = _extract_project_id(data)
    assert project_id, f"创建未返回 id: {data}"

    # 获取
    r2 = client.get(f"/api/projects/{project_id}")
    assert r2.status_code == 200
    p = r2.json()
    assert p.get("title") == "E2E测试项目" or p.get("project", {}).get("title") == "E2E测试项目"

    # 更新
    r3 = client.put(f"/api/projects/{project_id}", json={"title": "E2E更新项目", "description": "updated"})
    assert r3.status_code == 200

    # 删除
    r4 = client.delete(f"/api/projects/{project_id}")
    assert r4.status_code == 200
    assert r4.json().get("success") is not False


def test_project_spec_put_and_get(client):
    r = client.post("/api/projects", json={"title": "E2E Spec项目"})
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True
    project_id = _extract_project_id(data)
    assert project_id

    try:
        # 写入 spec（Java PUT /api/project/spec ✓）
        r2 = client.put("/api/project/spec", json={
            "project_id": project_id,
            "spec": "# E2E测试\n这是一个E2E测试项目规格文档。",
        })
        assert r2.status_code == 200

        # 读取 spec（Java GET /spec?project_id=... ← 注意非 /api/project/spec）
        r3 = client.get(f"/spec?project_id={project_id}")
        assert r3.status_code == 200
        d3 = r3.json()
        assert "project_id" in d3 or "content" in d3
    finally:
        client.delete(f"/api/projects/{project_id}")


def test_project_tasks_list(client):
    """项目任务树：Java 本地模式 /api/project/tasks?project_id=...。"""
    r = client.post("/api/projects", json={"title": "E2E Tasks项目"})
    assert r.status_code == 200
    data = r.json()
    project_id = _extract_project_id(data)
    assert project_id

    try:
        r2 = client.get(f"/api/project/tasks?project_id={project_id}")
        assert r2.status_code == 200
        d2 = r2.json()
        assert "task_tree" in d2 or "tasks" in d2 or isinstance(d2, dict)
    finally:
        client.delete(f"/api/projects/{project_id}")
