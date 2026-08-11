"""E2E: 健康检查 — Java、系统信息、系统资源。"""


def test_java_health(client):
    r = client.get("/api/health")
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "UP"
    assert "service" in data
    assert "timestamp" in data


def test_python_health(client):
    # /api/python/health 为历史兼容端点：Java-only 模式下返回自包含状态
    r = client.get("/api/python/health")
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "java-only", f"unexpected status: {data}"


def test_system_info(client):
    r = client.get("/api/system/info")
    assert r.status_code == 200
    data = r.json()
    assert "agent_model" in data or "ollama_available" in data


def test_system_resources(client):
    r = client.get("/api/system/resources")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)
