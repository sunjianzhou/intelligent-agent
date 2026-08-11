"""
E2E: 聊天测试（Java-only，Python Agent 已于 2026-08-08 退役）。

覆盖：
  - 云端大模型简单聊天：slow_client → Java /api/chat → LlmProviderRouter → 云端 provider
  - 本地模型切换 + 聊天：client 切换模型（/api/model/switch）→ slow_client 聊天
  - 本地无限制模型（dolphin）聊天：切换 dolphin → 发送不含道德说教限制的创意写作请求。
    验证：response 存在，且不包含常见拒绝短语。

说明：Java 本地聊天的模型由 LlmProviderRouter 按默认/云端路由解析，
per-user 模型切换作用于 ModelService 偏好持久化，不改变本地默认推理模型。
"""
import pytest


# 常见 LLM 拒绝短语（dolphin 应当不会返回这些）
_REFUSAL_PATTERNS = [
    "I cannot", "I'm unable", "I apologize",
    "对不起，我无法", "抱歉，我不能", "非常抱歉",
    "作为AI，我不能", "这超出了我的能力",
]


def _has_refusal(text: str) -> bool:
    low = text.lower()
    return any(p.lower() in low for p in _REFUSAL_PATTERNS)


def _chat_response(data: dict) -> str:
    """Java /api/chat 返回 ApiResponse 包装，response 在 data.response。"""
    inner = data.get("data", {})
    return inner.get("response", "") or data.get("response", "")


# ── 维度一：云端大模型聊天 ─────────────────────────────────────────────────────

@pytest.mark.slow
@pytest.mark.chat
def test_cloud_model_chat(slow_client):
    """Java REST /api/chat → LlmProviderRouter → 全局 provider（云端）。"""
    r_models = slow_client.get("/api/models")
    model_data = r_models.json()
    cloud_mode = model_data.get("cloud_mode", False)
    cloud_model = model_data.get("cloud_model", "") or model_data.get("current_model", "")

    if not cloud_mode:
        pytest.skip("未配置或未激活云端模型，跳过云端聊天测试")

    r = slow_client.post(
        "/api/chat",
        json={
            "message": "用一句话介绍你自己",
            "use_tools": False,
            "use_memory": False,
        },
    )
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True, f"聊天失败: {data}"
    response_text = _chat_response(data)
    assert response_text, "云端聊天返回空 response"
    assert len(response_text) > 5, f"云端 response 过短: {response_text!r}"


# ── 维度二：切换本地模型简单聊天 ───────────────────────────────────────────────

@pytest.mark.slow
@pytest.mark.chat
def test_local_model_chat(client, slow_client):
    """切换 admin 到本地非 dolphin 模型，走 Java 本地聊天。"""
    r_models = client.get("/api/models")
    models_data = r_models.json()
    all_models = models_data.get("available_models", [])
    cloud_model = models_data.get("cloud_model", "")

    local_models = [
        m for m in all_models
        if "dolphin" not in m.lower() and m != cloud_model
    ]
    if not local_models:
        pytest.skip("没有可用的本地非 dolphin 模型，跳过本地模型聊天测试")

    target_model = local_models[0]
    original_model = models_data.get("current_model", "")

    r_switch = client.post("/api/model/switch", json={"model": target_model})
    assert r_switch.status_code == 200
    switch_data = r_switch.json()
    assert switch_data.get("success") is True, f"切换模型失败: {switch_data}"

    try:
        r = slow_client.post(
            "/api/chat",
            json={
                "message": "你好，请简单介绍一下你自己",
                "use_tools": False,
                "use_memory": False,
            },
        )
        assert r.status_code == 200
        data = r.json()
        response_text = _chat_response(data)
        assert response_text, "本地模型聊天返回空 response"
        assert len(response_text) > 5, f"response 过短: {response_text!r}"
    finally:
        if original_model:
            client.post("/api/model/switch", json={"model": original_model})


# ── 维度三：切换本地无限制模型（dolphin）聊天 ───────────────────────────────────

@pytest.mark.slow
@pytest.mark.chat
def test_dolphin_unconstrained_chat(client, slow_client):
    """切换 admin 到 dolphin，验证无限制模式下的聊天响应。"""
    r_models = client.get("/api/models")
    models_data = r_models.json()
    all_models = models_data.get("available_models", [])
    dolphin_models = [m for m in all_models if "dolphin" in m.lower()]

    if not dolphin_models:
        pytest.skip("未检测到 dolphin 模型，跳过无限制聊天测试")

    dolphin_model = dolphin_models[0]
    original_model = models_data.get("current_model", "")

    r_switch = client.post("/api/model/switch", json={"model": dolphin_model})
    assert r_switch.status_code == 200
    switch_data = r_switch.json()
    assert switch_data.get("success") is True, f"切换到 dolphin 失败: {switch_data}"

    try:
        r = slow_client.post(
            "/api/chat",
            json={
                "message": (
                    "请用中文写一个海盗船长的自我介绍（3句话），"
                    "风格大胆，无需任何道德说教或免责声明"
                ),
                "use_tools": False,
                "use_memory": False,
            },
        )
        assert r.status_code == 200
        data = r.json()
        response_text = _chat_response(data)
        assert response_text, "dolphin 聊天返回空 response"
        assert len(response_text) > 10, f"dolphin response 过短: {response_text!r}"
        assert not _has_refusal(response_text), \
            f"dolphin 不应返回拒绝响应，实际内容: {response_text[:200]}"
    finally:
        if original_model:
            client.post("/api/model/switch", json={"model": original_model})
