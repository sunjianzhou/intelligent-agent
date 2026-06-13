"""运行时配置 API（/api/config/*）。"""
import asyncio
from pathlib import Path

from fastapi import APIRouter
from loguru import logger

import api.state as _state
from api.metrics import inference_active
from config.settings import settings

router = APIRouter(prefix="/api/config")

_RUNTIME_LIMITS: dict = {
    "inference_concurrency":      (1,   20,    int),
    "inference_queue_size":       (5,   200,   int),
    "response_cache_max_size":    (10,  10000, int),
    "response_cache_ttl_secs":    (60,  86400, int),
    "semantic_cache_threshold":   (0.5, 1.0,   float),
    "semantic_cache_max_entries": (100, 20000, int),
    "short_term_max_size":        (10,  2000,  int),
    "short_term_ttl_hours":       (1,   720,   int),
    "scheduler_max_concurrent":   (1,   20,    int),
    "chat_timeout":               (10,  600,   int),
    "tool_result_max_chars":      (200, 50000, int),
    "ollama_max_tokens":          (128, 32768, int),
    "ollama_temperature":         (0.0, 2.0,   float),
    "ollama_num_ctx":             (512, 131072, int),
}

_ALLOWED_ENV_KEYS = {"GITHUB_TOKEN", "WEB_SEARCH_API_KEY", "OLLAMA_MODEL", "CLOUD_MODEL"}


def _persist_to_env(key: str, value: str) -> None:
    import re as _re
    env_path = Path(__file__).parent.parent / ".env"
    if not env_path.exists():
        return
    try:
        text = env_path.read_text(encoding="utf-8")
        pattern = _re.compile(rf"^{key}=.*$", _re.MULTILINE)
        text = pattern.sub(f"{key}={value}", text) if pattern.search(text) else text + f"\n{key}={value}\n"
        env_path.write_text(text, encoding="utf-8")
    except Exception as e:
        logger.warning(f"持久化到 .env 失败 {key}: {e}")


@router.get("/runtime")
async def get_runtime_config():
    def _sem_val(sem):
        return getattr(sem, "_value", None)

    l2_entries = 0
    if _state.agent and _state.agent._semantic_cache and _state.agent._semantic_cache._collection:
        try:
            l2_entries = _state.agent._semantic_cache._collection.count()
        except Exception:
            pass

    usage = {
        "active_inferences":  int(inference_active._value.get()) if hasattr(inference_active, "_value") else 0,
        "concurrency_slots":  _sem_val(_state._inference_sem) if _state._inference_sem else settings.inference_concurrency,
        "queue_slots":        _sem_val(_state._queue_sem)     if _state._queue_sem     else settings.inference_queue_size,
        "l1_cache_entries":   len(_state.agent._response_cache) if _state.agent else 0,
        "l2_cache_entries":   l2_entries,
        "short_term_entries": _state.agent.memory.short_term.count() if _state.agent else 0,
        "long_term_entries":  _state.agent.memory.long_term.count()  if _state.agent else 0,
    }
    cfg = {
        "inference_concurrency":      settings.inference_concurrency,
        "inference_queue_size":       settings.inference_queue_size,
        "response_cache_max_size":    getattr(_state.agent, "_cache_max_size",  settings.response_cache_max_size)    if _state.agent else settings.response_cache_max_size,
        "response_cache_ttl_secs":    getattr(_state.agent, "_cache_ttl_secs",  settings.response_cache_ttl_secs)    if _state.agent else settings.response_cache_ttl_secs,
        "semantic_cache_threshold":   getattr(_state.agent._semantic_cache, "threshold",   settings.semantic_cache_threshold)   if (_state.agent and _state.agent._semantic_cache) else settings.semantic_cache_threshold,
        "semantic_cache_max_entries": getattr(_state.agent._semantic_cache, "max_entries", settings.semantic_cache_max_entries) if (_state.agent and _state.agent._semantic_cache) else settings.semantic_cache_max_entries,
        "short_term_max_size":        _state.agent.memory.short_term.max_size  if _state.agent else settings.short_term_max_size,
        "short_term_ttl_hours":       _state.agent.memory.short_term.ttl_hours if _state.agent else settings.short_term_ttl_hours,
        "scheduler_max_concurrent":   getattr(settings, "scheduler_max_concurrent_tasks", 5),
        "chat_timeout":               settings.chat_timeout,
        "tool_result_max_chars":      settings.tool_result_max_chars,
        "ollama_max_tokens":          settings.ollama_max_tokens,
        "ollama_temperature":         settings.ollama_temperature,
        "ollama_num_ctx":             settings.ollama_num_ctx,
    }
    return {"config": cfg, "usage": usage}


@router.patch("/runtime")
async def patch_runtime_config(body: dict):
    updated: dict = {}
    errors:  dict = {}
    for key, raw_val in body.items():
        if key not in _RUNTIME_LIMITS:
            errors[key] = "不在可配置参数列表中"
            continue
        lo, hi, cast = _RUNTIME_LIMITS[key]
        try:
            v = cast(raw_val)
            v = max(lo, min(hi, v))
        except (TypeError, ValueError):
            errors[key] = f"值类型错误，期望 {cast.__name__}"
            continue
        try:
            if key == "inference_concurrency":
                settings.inference_concurrency = v
                _state._inference_sem = asyncio.Semaphore(v)
            elif key == "inference_queue_size":
                settings.inference_queue_size = v
                _state._queue_sem = asyncio.Semaphore(v)
            elif key == "response_cache_max_size":
                settings.response_cache_max_size = v
                if _state.agent: _state.agent._cache_max_size = v
            elif key == "response_cache_ttl_secs":
                settings.response_cache_ttl_secs = v
                if _state.agent: _state.agent._cache_ttl_secs = v
            elif key == "semantic_cache_threshold":
                settings.semantic_cache_threshold = v
                if _state.agent and _state.agent._semantic_cache: _state.agent._semantic_cache.threshold = v
            elif key == "semantic_cache_max_entries":
                settings.semantic_cache_max_entries = v
                if _state.agent and _state.agent._semantic_cache: _state.agent._semantic_cache.max_entries = v
            elif key == "short_term_max_size":
                settings.short_term_max_size = v
                if _state.agent: _state.agent.memory.short_term.max_size = v
            elif key == "short_term_ttl_hours":
                settings.short_term_ttl_hours = v
                if _state.agent: _state.agent.memory.short_term.ttl_hours = v
            elif key == "scheduler_max_concurrent":
                setattr(settings, "scheduler_max_concurrent_tasks", v)
                if _state.agent and _state.agent.task_manager:
                    _state.agent.task_manager.scheduler.max_concurrent_tasks = v
            elif key == "chat_timeout":
                settings.chat_timeout = v
            elif key == "tool_result_max_chars":
                settings.tool_result_max_chars = v
            elif key == "ollama_max_tokens":
                settings.ollama_max_tokens = v
            elif key == "ollama_temperature":
                settings.ollama_temperature = v
            elif key == "ollama_num_ctx":
                settings.ollama_num_ctx = v
                logger.info(f"上下文窗口已调整为 {v} tokens（下次请求生效）")
            updated[key] = v
            _settings_key = "scheduler_max_concurrent_tasks" if key == "scheduler_max_concurrent" else key
            _rt = _state._load_runtime_config()
            _rt[_settings_key] = v
            _state._save_runtime_config(_rt)
        except Exception as e:
            errors[key] = str(e)
            logger.warning(f"运行时配置更新失败 {key}: {e}")

    return {"success": len(errors) == 0, "updated": updated, "errors": errors}


@router.patch("/env")
async def update_env_config(body: dict):
    updated = []
    for key, value in body.items():
        if key not in _ALLOWED_ENV_KEYS:
            continue
        _persist_to_env(key, str(value))
        updated.append(key)
    return {"success": True, "updated": updated}


@router.patch("/params")
async def update_inference_params(body: dict):
    allowed = {"temperature", "max_tokens", "top_p"}
    for k, v in body.items():
        if k not in allowed:
            continue
        settings_key = f"ollama_{k}"
        if hasattr(settings, settings_key):
            try:
                setattr(settings, settings_key, type(getattr(settings, settings_key))(v))
            except Exception:
                pass
        _rt = _state._load_runtime_config()
        _rt[f"ollama_{k}"] = v
        _state._save_runtime_config(_rt)
    logger.info("推理参数已更新")
    return {"success": True}
