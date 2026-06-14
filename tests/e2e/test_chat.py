"""
E2E: 聊天三维度测试。

维度一 — 云端大模型简单聊天
  链路：test → slow_client(Java:8080/api/chat) → Python:8000/api/chat → 全局云端 provider
  说明：Java ChatController 以 java-service 身份调用 Python，无 per-user 覆盖，
        使用全局 provider（启动时由 .env CLOUD_* 或已激活云端服务商决定）。

维度二 — 切换本地模型简单聊天
  链路：test → client(Java:8080/api/model/switch) → Python 设置 per-user provider
        test → py_client(Python:8000/api/chat, admin 身份) → 使用 admin 的本地 provider

维度三 — 切换本地无限制模型（dolphin）聊天
  链路：同维度二，但切换目标为 dolphin，发送不含道德说教限制的创意写作请求。
        验证：response 存在，且不包含常见拒绝短语。
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


# ── 维度一：云端大模型聊天 ─────────────────────────────────────────────────────

@pytest.mark.slow
@pytest.mark.chat
def test_cloud_model_chat(slow_client):
    """Java REST /api/chat → Python(java-service) → 全局 provider（云端）。"""
    # 确认当前是否有云端模型
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

    # Java ChatController 返回 ApiResponse 格式
    assert data.get("success") is True, f"聊天失败: {data}"
    inner = data.get("data", {})
    response_text = inner.get("response", "")
    assert response_text, "云端聊天返回空 response"
    assert len(response_text) > 5, f"云端 response 过短: {response_text!r}"


# ── 维度二：切换本地模型简单聊天 ───────────────────────────────────────────────

@pytest.mark.slow
@pytest.mark.chat
def test_local_model_chat(client, py_client):
    """切换 admin 到本地非 dolphin 模型，py_client 直连 Python 聊天。"""
    # 获取可用模型
    r_models = client.get("/api/models")
    models_data = r_models.json()
    all_models = models_data.get("available_models", [])
    cloud_model = models_data.get("cloud_model", "")

    # 找一个本地非 dolphin 模型
    local_models = [
        m for m in all_models
        if "dolphin" not in m.lower() and m != cloud_model
    ]
    if not local_models:
        pytest.skip("没有可用的本地非 dolphin 模型，跳过本地模型聊天测试")

    target_model = local_models[0]
    original_model = models_data.get("current_model", "")

    # 通过 Java 切换 admin 用户模型（走完整 Java→Python 代理链）
    r_switch = client.post("/api/model/switch", json={"model": target_model})
    assert r_switch.status_code == 200
    switch_data = r_switch.json()
    assert switch_data.get("success") is True, f"切换模型失败: {switch_data}"

    try:
        # py_client 以 admin 身份直连 Python，使用刚切换的本地模型
        r = py_client.post(
            "/api/chat",
            json={
                "message": "你好，请简单介绍一下你自己",
                "use_tools": False,
                "use_memory": False,
            },
        )
        assert r.status_code == 200
        data = r.json()
        response_text = data.get("response", "")
        assert response_text, "本地模型聊天返回空 response"
        assert len(response_text) > 5, f"response 过短: {response_text!r}"

        # 验证使用的是本地模型
        model_used = data.get("model", "")
        assert target_model in model_used or model_used != cloud_model, \
            f"期望本地模型 {target_model}，实际使用 {model_used}"

    finally:
        # 还原 admin 的模型设置
        if original_model:
            client.post("/api/model/switch", json={"model": original_model})


# ── 维度三：切换本地无限制模型（dolphin）聊天 ───────────────────────────────────

@pytest.mark.slow
@pytest.mark.chat
def test_dolphin_unconstrained_chat(client, py_client):
    """切换 admin 到 dolphin，验证无限制模式下的聊天响应。"""
    # 确认 dolphin 是否可用
    r_models = client.get("/api/models")
    models_data = r_models.json()
    all_models = models_data.get("available_models", [])
    dolphin_models = [m for m in all_models if "dolphin" in m.lower()]

    if not dolphin_models:
        pytest.skip("未检测到 dolphin 模型，跳过无限制聊天测试")

    dolphin_model = dolphin_models[0]
    original_model = models_data.get("current_model", "")

    # 通过 Java 切换 admin 到 dolphin
    r_switch = client.post("/api/model/switch", json={"model": dolphin_model})
    assert r_switch.status_code == 200
    switch_data = r_switch.json()
    assert switch_data.get("success") is True, f"切换到 dolphin 失败: {switch_data}"

    try:
        # py_client 以 admin 身份直连 Python，dolphin 无限制模式
        r = py_client.post(
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
        response_text = data.get("response", "")
        assert response_text, "dolphin 聊天返回空 response"
        assert len(response_text) > 10, f"dolphin response 过短: {response_text!r}"

        # 无限制模式不应触发拒绝
        assert not _has_refusal(response_text), \
            f"dolphin 不应返回拒绝响应，实际内容: {response_text[:200]}"

        # 验证使用的是 dolphin 模型
        model_used = data.get("model", "")
        assert "dolphin" in model_used.lower(), \
            f"期望 dolphin 模型，实际使用: {model_used}"

    finally:
        # 还原 admin 的模型设置
        if original_model:
            client.post("/api/model/switch", json={"model": original_model})
