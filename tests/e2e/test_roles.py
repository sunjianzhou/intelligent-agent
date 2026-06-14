"""E2E: 角色管理 — 列表 / 激活 / 取消激活。"""
import pytest


def test_list_roles(client):
    r = client.get("/api/roles")
    assert r.status_code == 200
    data = r.json()
    assert "roles" in data
    assert isinstance(data["roles"], list)


def test_get_active_role(client):
    r = client.get("/api/roles/activate")
    assert r.status_code == 200
    data = r.json()
    # 无论激活与否，接口本身应正常返回
    assert isinstance(data, dict)


def test_activate_and_deactivate_role(client):
    # 获取可用角色列表
    r = client.get("/api/roles")
    roles = r.json().get("roles", [])
    if not roles:
        pytest.skip("没有可用角色")

    role_id = roles[0].get("role_id") or roles[0].get("id")
    assert role_id

    # 激活
    r2 = client.post("/api/roles/activate", json={"role_id": role_id})
    assert r2.status_code == 200
    data2 = r2.json()
    assert data2.get("success") is not False

    # 确认激活
    r3 = client.get("/api/roles/activate")
    active = r3.json()
    assert active.get("role_id") == role_id or active.get("active_role_id") == role_id

    # 取消激活
    r4 = client.delete("/api/roles/activate")
    assert r4.status_code == 200


def test_activate_nonexistent_role(client):
    r = client.post("/api/roles/activate", json={"role_id": "nonexistent-xyz"})
    # Python 返回 404 → Java RoleController 以 500 转发（RestTemplate 抛 4xx 异常被 catch）
    # 接受 500 或 200(success=False)，不接受服务未响应
    assert r.status_code in (200, 404, 500)
    if r.status_code == 200:
        assert r.json().get("success") is False
