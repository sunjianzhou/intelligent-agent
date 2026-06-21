"""E2E: 消息撤回 — POST /api/conversations/{session_id}/retract

链路覆盖：
  - 快速用例（无需 LLM 推理）：直接对不存在的会话/边界条件发起撤回请求，
    分别走 client（Java:8080 代理）和 py_client（Python:8000 直连），验证两条
    链路都能正确转发请求/响应形状。
  - 慢速用例（@pytest.mark.slow @pytest.mark.chat，需真实 LLM 推理）：
    py_client 真实发一轮聊天 → 从响应里取 user_message_id/assistant_message_id
    → 对同一 session_id 调用撤回 → 验证 GET /api/conversations/{session_id}
    里这两条消息确实消失。

约定：聊天类测试统一用 py_client（admin 身份直连 Python），原因同 test_chat.py
里维度二/三的说明——Java ChatController 固定 user_id 为 java-service，
无法测 per-user 行为；而撤回端点本身不依赖 per-user provider，Java 代理
转发的快速用例单独用 client 验证一次即可。
"""
import uuid

import pytest


# ── 快速用例：无需 LLM，验证请求/响应形状 ──────────────────────────────────────

def test_retract_nonexistent_session_via_java_proxy(client):
    """Java 代理转发：会话不存在时不报错，返回 deleted=0。"""
    fake_session = f"e2e-retract-{uuid.uuid4().hex[:8]}"
    r = client.post(
        f"/api/conversations/{fake_session}/retract",
        json={"message_ids": ["fake-id-1"]},
    )
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True
    assert data.get("requested") == 1
    assert data.get("deleted") == 0
    assert data.get("deleted_ids") == []


def test_retract_nonexistent_session_via_python_direct(py_client):
    """直连 Python：同上场景，验证 Python 端点本身行为一致。"""
    fake_session = f"e2e-retract-{uuid.uuid4().hex[:8]}"
    r = py_client.post(
        f"/api/conversations/{fake_session}/retract",
        json={"message_ids": ["fake-id-1"]},
    )
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True
    assert data.get("requested") == 1
    assert data.get("deleted") == 0


def test_retract_empty_message_ids_is_noop(py_client):
    fake_session = f"e2e-retract-{uuid.uuid4().hex[:8]}"
    r = py_client.post(
        f"/api/conversations/{fake_session}/retract",
        json={"message_ids": []},
    )
    assert r.status_code == 200
    data = r.json()
    assert data == {
        "success": True, "requested": 0, "deleted": 0,
        "deleted_ids": [], "memory_purged": 0,
    }


def test_retract_over_batch_limit_returns_400(py_client):
    fake_session = f"e2e-retract-{uuid.uuid4().hex[:8]}"
    too_many_ids = [f"mid-{i}" for i in range(51)]
    r = py_client.post(
        f"/api/conversations/{fake_session}/retract",
        json={"message_ids": too_many_ids},
    )
    assert r.status_code == 400
    assert r.json().get("success") is False


# ── 慢速用例：真实聊天 → 撤回 → 验证已从历史中删除 ──────────────────────────────

@pytest.mark.slow
@pytest.mark.chat
def test_retract_removes_real_message_end_to_end(py_client):
    """真实跑一轮聊天，拿到后端分配的 message_id，撤回后确认历史记录里不再出现。"""
    session_id = f"e2e-retract-real-{uuid.uuid4().hex[:8]}"

    r_chat = py_client.post(
        "/api/chat",
        json={
            "message": "请用一句话回复：测试消息撤回功能",
            "use_tools": False,
            "use_memory": True,
            "session_id": session_id,
        },
    )
    assert r_chat.status_code == 200
    chat_data = r_chat.json()
    assert chat_data.get("response"), "聊天未返回内容，无法继续撤回测试"

    user_msg_id = chat_data.get("user_message_id")
    assistant_msg_id = chat_data.get("assistant_message_id")
    assert user_msg_id, "响应缺少 user_message_id"
    assert assistant_msg_id, "响应缺少 assistant_message_id"

    # 撤回前：确认历史记录里能查到这两条
    r_before = py_client.get(f"/api/conversations/{session_id}")
    assert r_before.status_code == 200
    msgs_before = r_before.json().get("session", {}).get("messages", [])
    ids_before = {m.get("id") for m in msgs_before}
    assert user_msg_id in ids_before
    assert assistant_msg_id in ids_before

    # 撤回两条消息
    r_retract = py_client.post(
        f"/api/conversations/{session_id}/retract",
        json={"message_ids": [user_msg_id, assistant_msg_id]},
    )
    assert r_retract.status_code == 200
    retract_data = r_retract.json()
    assert retract_data.get("success") is True
    assert retract_data.get("requested") == 2
    assert retract_data.get("deleted") == 2
    assert set(retract_data.get("deleted_ids", [])) == {user_msg_id, assistant_msg_id}

    # 撤回后：历史记录里这两条应彻底消失（不留 tombstone）
    r_after = py_client.get(f"/api/conversations/{session_id}")
    assert r_after.status_code == 200
    msgs_after = r_after.json().get("session", {}).get("messages", [])
    ids_after = {m.get("id") for m in msgs_after}
    assert user_msg_id not in ids_after
    assert assistant_msg_id not in ids_after

    # 清理：删除本次测试创建的会话，避免污染历史列表
    py_client.delete(f"/api/conversations/{session_id}")
