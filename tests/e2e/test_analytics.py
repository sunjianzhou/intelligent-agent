"""E2E: Analytics — stats / records / feedback / skill-stats / tool-calls / tool-stats。"""
import pytest
from conftest import USERNAME


def test_analytics_stats(client):
    r = client.get(f"/api/analytics/stats/{USERNAME}")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)


def test_analytics_records(client):
    r = client.get(f"/api/analytics/records/{USERNAME}?limit=10")
    assert r.status_code == 200
    data = r.json()
    assert "records" in data or isinstance(data, dict)


def test_analytics_skill_logs(client):
    r = client.get(f"/api/analytics/skill-logs/{USERNAME}?limit=10")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)


def test_analytics_skill_stats(client):
    r = client.get(f"/api/analytics/skill-stats/{USERNAME}")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)


def test_analytics_tool_calls(client):
    r = client.get("/api/analytics/tool-calls?limit=10")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)


def test_analytics_tool_stats(client):
    r = client.get("/api/analytics/tool-stats")
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)


def test_submit_feedback(client):
    # Python analytics/feedback 需要 message / response / username 三个必填字段
    payload = {
        "username": USERNAME,
        "session_id": "e2e-test-session",
        "message_id": "e2e-msg-001",
        "message": "你好",
        "response": "你好，有什么可以帮助你的？",
        "rating": "up",
    }
    r = client.post("/api/analytics/feedback", json=payload)
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is not False
