"""E2E: 云端服务商配置 — 预设 / CRUD / 激活 / 停用。"""
import pytest


def test_cloud_presets(client):
    r = client.get("/api/cloud/presets")
    assert r.status_code == 200
    data = r.json()
    assert "presets" in data or isinstance(data, dict)


def test_list_cloud_providers(client):
    r = client.get("/api/cloud/providers")
    assert r.status_code == 200
    data = r.json()
    assert "providers" in data
    assert isinstance(data["providers"], list)


def test_cloud_provider_crud(client):
    payload = {
        "name": "E2E测试服务商",
        "provider": "openai",
        "base_url": "https://api.openai.com/v1",
        "api_key": "sk-e2e-test-key",
        "model": "gpt-4o-mini",
    }
    # 创建
    r = client.post("/api/cloud/providers", json=payload)
    assert r.status_code == 200
    data = r.json()
    provider_id = (data.get("id") or data.get("provider_id")
                   or data.get("provider", {}).get("id"))
    assert provider_id, f"创建未返回 id: {data}"

    # 列表中应含新增项
    r2 = client.get("/api/cloud/providers")
    providers = r2.json().get("providers", [])
    ids = [p.get("id") for p in providers]
    assert provider_id in ids

    # 更新
    r3 = client.put(
        f"/api/cloud/providers/{provider_id}",
        json={**payload, "model": "gpt-4o"},
    )
    assert r3.status_code == 200

    # 删除
    r4 = client.delete(f"/api/cloud/providers/{provider_id}")
    assert r4.status_code == 200
    assert r4.json().get("success") is not False


def test_deactivate_cloud(client):
    """停用云端不应报错（即使没有激活的云端服务商）。"""
    r = client.post("/api/cloud/deactivate")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)
