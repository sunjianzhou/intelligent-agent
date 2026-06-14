"""E2E: 模型管理 — 列表 / 切换。"""
import pytest


def test_models_list(client):
    r = client.get("/api/models")
    assert r.status_code == 200
    data = r.json()
    assert "available_models" in data
    assert isinstance(data["available_models"], list)
    assert "current_model" in data
    assert "ollama_available" in data
    assert "cloud_mode" in data


def test_models_no_duplicates(client):
    r = client.get("/api/models")
    models = r.json().get("available_models", [])
    assert len(models) == len(set(models)), "available_models 含重复条目"


def test_model_switch_to_existing(client):
    r = client.get("/api/models")
    data = r.json()
    models = data.get("available_models", [])
    if not models:
        pytest.skip("没有可用模型")

    current = data.get("current_model", "")
    target = models[0]

    r2 = client.post("/api/model/switch", json={"model": target})
    assert r2.status_code == 200
    d2 = r2.json()
    assert d2.get("success") is True
    assert d2.get("current_model") == target

    # 还原
    if current and current != target:
        client.post("/api/model/switch", json={"model": current})


def test_model_switch_nonexistent(client):
    r = client.post("/api/model/switch", json={"model": "nonexistent-model-xyz:999"})
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is False
