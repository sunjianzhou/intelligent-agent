"""E2E: 运行时配置 — 读取 / 更新。"""


def test_get_runtime_config(client):
    r = client.get("/api/config/runtime")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)


def test_patch_runtime_config(client):
    # 读取当前值
    r = client.get("/api/config/runtime")
    current = r.json()
    original_temp = current.get("ollama_temperature", 0.8)

    # 改写
    r2 = client.patch("/api/config/runtime", json={"ollama_temperature": 0.75})
    assert r2.status_code == 200
    d2 = r2.json()
    assert d2.get("success") is not False

    # 还原
    client.patch("/api/config/runtime", json={"ollama_temperature": original_temp})
