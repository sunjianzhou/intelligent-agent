"""模型列表查询与 per-user 模型切换端点。"""
from pathlib import Path

from fastapi import APIRouter, Request
from loguru import logger
from pydantic import BaseModel

import api.state as _state
from config.settings import settings

router = APIRouter()


class ModelSwitchRequest(BaseModel):
    model: str


# ── 辅助函数 ──────────────────────────────────────────────────────────────────
def _build_provider_for_model(target_model: str):
    """为 target_model 创建 provider 实例；不可用时返回 None。"""
    from services.ollama_provider import OllamaProvider
    is_cloud = bool(
        settings.cloud_provider
        and settings.cloud_api_key
        and target_model == settings.cloud_model
    )
    if is_cloud:
        from services.openai_provider import OpenAIProvider
        return OpenAIProvider(
            api_key=settings.cloud_api_key,
            base_url=_state._resolve_cloud_base_url(settings.cloud_provider, settings.cloud_base_url),
            model=settings.cloud_model,
        )
    try:
        p = OllamaProvider(base_url=settings.ollama_base_url)
        return p if p.switch_model(target_model) else None
    except Exception:
        return None


def _persist_model_to_env(key: str, value: str) -> None:
    """将模型选择写回 .env，实现重启持久化。"""
    import re as _re
    env_path = Path(__file__).parent.parent / ".env"
    if not env_path.exists():
        return
    try:
        text = env_path.read_text(encoding="utf-8")
        pattern = _re.compile(rf"^{key}=.*$", _re.MULTILINE)
        text = pattern.sub(f"{key}={value}", text) if pattern.search(text) else text + f"\n{key}={value}\n"
        env_path.write_text(text, encoding="utf-8")
        logger.info(f"模型已持久化到 .env: {key}={value}")
    except Exception as e:
        logger.warning(f"持久化模型到 .env 失败: {e}")


# ── 端点 ──────────────────────────────────────────────────────────────────────
@router.get("/api/models")
async def get_models(http_req: Request):
    ollama_url = (settings.ollama_base_url or "http://localhost:11434").rstrip("/")

    local_models = []
    try:
        import httpx
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.get(f"{ollama_url}/api/tags")
        if r.status_code == 200:
            # dict.fromkeys 保序去重，避免 Ollama 偶发返回重复条目
            local_models = list(dict.fromkeys(m["name"] for m in r.json().get("models", [])))
    except Exception:
        pass

    configured_cloud = bool(settings.cloud_provider and settings.cloud_api_key and settings.cloud_model)
    cloud_model_name = settings.cloud_model if configured_cloud else ""

    all_models = local_models[:]
    if cloud_model_name and cloud_model_name not in all_models:
        all_models.append(cloud_model_name)

    user_id = getattr(http_req.state, "user_id", "default") if http_req else "default"
    user_provider = _state._get_user_provider(user_id)
    current = user_provider.current_model if user_provider else (cloud_model_name if _state.CLOUD_MODE else "")
    user_is_cloud = bool(configured_cloud and current == cloud_model_name)

    from api.cloud_router import KNOWN_PROVIDER_URLS
    return {
        "available_models": all_models,
        "current_model":   current,
        "ollama_available": len(local_models) > 0,
        "cloud_mode":       user_is_cloud,
        "cloud_model":      cloud_model_name if user_is_cloud else "",
        "cloud_provider":   settings.cloud_provider if configured_cloud else "",
        "known_cloud_providers": list(KNOWN_PROVIDER_URLS.keys()),
    }


@router.post("/api/model/switch")
async def switch_model(request: ModelSwitchRequest, http_req: Request):
    """Per-user 模型切换：只影响当前用户，不干扰其他会话。"""
    user_id = getattr(http_req.state, "user_id", "default")
    new_provider = _build_provider_for_model(request.model)
    if new_provider is None:
        return {"success": False, "message": f"模型 {request.model} 不可用或切换失败"}

    _state._user_providers[user_id] = new_provider
    prefs = _state._load_user_model_prefs()
    prefs[user_id] = request.model
    _state._save_user_model_prefs(prefs)

    is_cloud = bool(
        settings.cloud_provider and settings.cloud_api_key and request.model == settings.cloud_model
    )
    logger.info(f"用户 {user_id} 切换模型 → {request.model} ({'云端' if is_cloud else '本地'})")
    return {
        "success": True,
        "current_model": new_provider.current_model,
        "cloud_mode": is_cloud,
    }
