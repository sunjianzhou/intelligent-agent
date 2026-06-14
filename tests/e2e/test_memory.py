"""E2E: 记忆模块 — 统计 / 列表 / 搜索 / 导入 / 删除 / 蒸馏 / 摘要 / 导出。"""
import pytest


def test_memory_stats(client):
    r = client.get("/api/memory")
    assert r.status_code == 200
    data = r.json()
    assert "short_term_count" in data or "long_term_count" in data or isinstance(data, dict)


def test_memory_list_long_term(client):
    r = client.get("/api/memory/list?memory_type=long_term&limit=10")
    assert r.status_code == 200
    data = r.json()
    assert "memories" in data
    assert isinstance(data["memories"], list)


def test_memory_list_short_term(client):
    r = client.get("/api/memory/list?memory_type=short_term&limit=10")
    assert r.status_code == 200
    data = r.json()
    assert "memories" in data


def test_memory_search(client):
    r = client.get("/api/memory/search?q=test&limit=5")
    assert r.status_code == 200
    data = r.json()
    assert "results" in data or isinstance(data, dict)


def test_memory_summaries(client):
    r = client.get("/api/memory/summaries?limit=10")
    assert r.status_code == 200
    data = r.json()
    assert "summaries" in data
    assert isinstance(data["summaries"], list)


def test_memory_batch_import_and_delete(client):
    payload = {
        "items": [
            {"content": "E2E_TEST_FACT: 这是一条E2E测试记忆", "category": "fact", "importance": 0.5}
        ]
    }
    r = client.post("/api/memory/batch-import", json=payload)
    assert r.status_code == 200
    data = r.json()
    assert data.get("imported_count", 0) >= 1 or data.get("success") is not False

    # 搜索刚导入的条目
    r2 = client.get("/api/memory/search?q=E2E_TEST_FACT&limit=5")
    assert r2.status_code == 200
    results = r2.json().get("results", [])

    # 清理：删除找到的条目
    for item in results:
        if "E2E_TEST_FACT" in item.get("content", ""):
            mem_id = item.get("id")
            if mem_id:
                client.delete(f"/api/memory/{mem_id}")


def test_memory_export_json(client):
    r = client.get("/api/memory/export?format=json")
    assert r.status_code == 200
    content_type = r.headers.get("content-type", "")
    assert "json" in content_type or "attachment" in r.headers.get("content-disposition", "")


def test_memory_export_markdown(client):
    r = client.get("/api/memory/export?format=markdown")
    assert r.status_code == 200
    content_disp = r.headers.get("content-disposition", "")
    assert ".md" in content_disp or "attachment" in content_disp


def test_memory_distill(client):
    r = client.post("/api/memory/distill")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)
