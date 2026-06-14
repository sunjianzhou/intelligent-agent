"""E2E: 历史对话 — 列表 / 获取 / 删除。"""
import pytest


def test_list_conversations(client):
    r = client.get("/api/conversations")
    assert r.status_code == 200
    data = r.json()
    # 后端返回 sessions 字段（历史原因）
    assert "sessions" in data
    assert isinstance(data["sessions"], list)


def test_get_conversation_not_found(client):
    r = client.get("/api/conversations/nonexistent-id-xyz")
    # 应返回 404 或 success:false，不应 500
    assert r.status_code in (200, 404)
    if r.status_code == 200:
        assert r.json().get("success") is False or r.json().get("messages") is None


def test_delete_conversation_not_found(client):
    r = client.delete("/api/conversations/nonexistent-id-xyz")
    assert r.status_code in (200, 404)


def test_list_returns_metadata(client):
    r = client.get("/api/conversations")
    convs = r.json().get("sessions", [])
    for conv in convs[:3]:
        assert "session_id" in conv or "id" in conv or "title" in conv
