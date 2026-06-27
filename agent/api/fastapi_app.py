"""Python 智能体 FastAPI 服务入口（精简版编排器）。"""
import asyncio
import logging
import os
import traceback
from contextlib import asynccontextmanager

import jwt as pyjwt
import uvicorn
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from loguru import logger

import api.state as _state
from api.cloud_router import (
    KNOWN_PROVIDER_URLS as _CLOUD_KNOWN_URLS,
    load_providers as _load_cloud_providers,
    router as cloud_router,
    set_callbacks as _set_cloud_callbacks,
)
from api.conversations_router import router as conversations_router
from api.metrics import metrics_middleware, metrics_router
from api.roles_router import (
    _user_active_roles as _user_active_roles_state,
    get_role_manager as _get_role_manager,
    router as roles_router,
    _load_user_active_roles as _load_active_roles,
)
from analytics.router import router as analytics_router
from config.settings import settings
from services.ollama_provider import OllamaProvider
from skills.router import router as skills_router

# 抑制 uvicorn 访问日志
logging.getLogger("uvicorn.access").setLevel(logging.WARNING)

_LOCAL_DEV_SECRET = "local-dev-only-change-in-production-must-be-32chars"


# ── 云端服务商回调 ────────────────────────────────────────────────────────────
def _activate_cloud_provider(base_url: str, api_key: str, model: str, provider_name: str):
    from services.openai_provider import OpenAIProvider
    try:
        new_p = OpenAIProvider(api_key=api_key, base_url=base_url, model=model)
        _state.provider = new_p
        settings.cloud_api_key  = api_key
        settings.cloud_base_url = base_url
        settings.cloud_model    = model
        settings.cloud_provider = provider_name
        logger.info(f"云端服务商已切换: {provider_name} / {model} @ {base_url}")
    except Exception as e:
        logger.error(f"创建云端 Provider 实例失败: {e}")
        raise


def _deactivate_cloud_provider():
    try:
        new_p = OllamaProvider(base_url=settings.ollama_base_url)
        if new_p.check_connection():
            _state.provider = new_p
            settings.cloud_api_key  = ""
            settings.cloud_base_url = ""
            settings.cloud_model    = ""
            settings.cloud_provider = ""
            logger.info("已切换回本地 Ollama")
        else:
            logger.warning("切换回 Ollama 失败：Ollama 连接不可用，保持当前提供方")
    except Exception as e:
        logger.warning(f"切换回 Ollama 时出错: {e}")


_set_cloud_callbacks(_activate_cloud_provider, _deactivate_cloud_provider)


# ── Provider 初始化（模块加载时执行，云端优先）───────────────────────────────
_state.CLOUD_MODE = bool(
    settings.cloud_provider and settings.cloud_api_key and settings.cloud_model
)

if _state.CLOUD_MODE:
    from services.openai_provider import OpenAIProvider
    _effective_cloud_url = _state._resolve_cloud_base_url(
        settings.cloud_provider, settings.cloud_base_url
    )
    _state.provider = OpenAIProvider(
        api_key=settings.cloud_api_key,
        base_url=_effective_cloud_url,
        model=settings.cloud_model,
    )
    _state.OLLAMA_AVAILABLE = True
    logger.info(f"使用云端模型: {settings.cloud_model} ({settings.cloud_provider}, {_effective_cloud_url})")
    try:
        _state._fallback_ollama = OllamaProvider(base_url=settings.ollama_base_url)
    except Exception:
        _state._fallback_ollama = None
else:
    try:
        _state.provider = OllamaProvider()
        _state.OLLAMA_AVAILABLE = _state.provider.check_connection()
        logger.info(f"使用本地模型: {_state.provider.current_model}")
    except Exception as e:
        logger.error(f"Provider 初始化失败: {e}")
        _state.provider = None
        _state.OLLAMA_AVAILABLE = False


# ── IntelligentAgent 初始化 ───────────────────────────────────────────────────
if _state.OLLAMA_AVAILABLE and _state.provider:
    try:
        from core.agent import IntelligentAgent
        _state.agent = IntelligentAgent(provider=_state.provider)
        if _state.agent.task_manager:
            sch = _state.agent.task_manager.scheduler
            sch._provider_getter = _state._get_user_provider
            sch._inference_slot  = _state._inference_slot
        logger.info("IntelligentAgent 初始化成功")
    except Exception as e:
        logger.error(f"IntelligentAgent 初始化失败，降级为 Provider 直连: {e}")
        logger.error(traceback.format_exc())


# ── Lifespan ──────────────────────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 恢复持久化配置
    for _k, _v in _state._load_runtime_config().items():
        try:
            if hasattr(settings, _k):
                setattr(settings, _k, type(getattr(settings, _k))(_v))
        except Exception as _e:
            logger.warning(f"恢复运行时配置 {_k}={_v} 失败: {_e}")

    _state._inference_sem = asyncio.Semaphore(
        getattr(settings, "inference_concurrency", 3)
    )
    _state._queue_sem = asyncio.Semaphore(
        getattr(settings, "inference_queue_size", 20)
    )
    logger.info(
        f"推理并发控制已启用：并发上限={_state._inference_sem._value}，"
        f"队列上限={_state._queue_sem._value}"
    )

    # 修正 scheduler 绑定到正确的 uvicorn 事件循环
    _uvicorn_loop = asyncio.get_running_loop()
    if _state.agent and _state.agent.task_manager:
        _state.agent.task_manager.scheduler.main_loop = _uvicorn_loop
        logger.info("已将 scheduler.main_loop 更新为 uvicorn 事件循环")
        # Register teaching push schedules from scheduler_config.json
        # 按 topic 注册到 SimpleTaskScheduler，顺序：morning 07:00 → midmorning 10:00 → review 13:40 → afternoon 15:00
        try:
            from teaching.pusher import register as _register_teaching
            _register_teaching(_state.agent.task_manager.scheduler)
        except Exception as _te:
            logger.warning(f"教学推送注册失败（非致命）: {_te}")

    # 飞书 OAuth 配置校验（与工具注册门控对齐：FEISHU_APP_ID 设置即激活）
    if settings.feishu_app_id:
        if not settings.feishu_oauth_redirect_uri:
            raise RuntimeError(
                "FEISHU_APP_ID 已设置时必须配置 FEISHU_OAUTH_REDIRECT_URI"
            )
        if not settings.feishu_oauth_encryption_key:
            raise RuntimeError(
                "FEISHU_APP_ID 已设置时必须配置 FEISHU_OAUTH_ENCRYPTION_KEY"
            )

    # JWT 密钥检查
    if settings.jwt_enabled:
        if not settings.jwt_secret:
            logger.error(
                "❌ JWT_ENABLED=true 但 JWT_SECRET 为空，所有鉴权请求将被拒绝！"
                "请设置 JWT_SECRET 环境变量。"
            )
        elif settings.jwt_secret == _LOCAL_DEV_SECRET:
            if not settings.debug:
                raise RuntimeError(
                    "生产模式下不允许使用默认 JWT 密钥！"
                    "请通过 JWT_SECRET 环境变量设置强随机密钥（建议 ≥32 字符随机字符串）。"
                )
            else:
                logger.warning(
                    "⚠️  使用本地开发 JWT 密钥，生产环境请通过 JWT_SECRET 环境变量注入强随机密钥。"
                )

    # 恢复用户模型偏好
    for _uid, _model_name in _state._load_user_model_prefs().items():
        try:
            from api.model_router import _build_provider_for_model
            _p = _build_provider_for_model(_model_name)
            if _p:
                _state._user_providers[_uid] = _p
                logger.info(f"恢复用户 {_uid} 的模型偏好: {_model_name}")
        except Exception as _restore_err:
            logger.warning(f"恢复用户 {_uid} 模型偏好失败: {_restore_err}")

    # 恢复激活角色
    for _uid, _role_id in _load_active_roles().items():
        _user_active_roles_state[_uid] = _role_id
        logger.info(f"恢复用户 {_uid} 的激活角色: {_role_id}")

    # 恢复激活的云端服务商
    _cloud_provs = _load_cloud_providers()
    _active_cloud = next((p for p in _cloud_provs if p.get("is_active")), None)
    if _active_cloud:
        try:
            from services.openai_provider import OpenAIProvider
            _eff_url = _active_cloud.get("base_url") or _CLOUD_KNOWN_URLS.get(
                _active_cloud["provider"].lower(), ""
            )
            _state.provider = OpenAIProvider(
                api_key=_active_cloud["api_key"],
                base_url=_eff_url,
                model=_active_cloud["model"],
            )
            settings.cloud_api_key  = _active_cloud["api_key"]
            settings.cloud_base_url = _eff_url
            settings.cloud_model    = _active_cloud["model"]
            settings.cloud_provider = _active_cloud["provider"]
            logger.info(
                f"已从配置文件恢复云端服务商: {_active_cloud['name']} / {_active_cloud['model']}"
            )
        except Exception as _ce:
            logger.warning(f"恢复云端服务商失败（将继续使用当前 provider）: {_ce}")

    # embedding 模型预热
    try:
        if _state.agent and hasattr(_state.agent, "memory"):
            logger.info("预热 embedding 模型...")
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(
                None,
                lambda: _state.agent.memory.long_term.embedding_model.encode("warmup"),
            )
            logger.info("embedding 模型预热完成")
    except Exception as e:
        logger.warning(f"embedding 预热失败（不影响主流程）: {e}")

    if _state.agent:
        _loop = asyncio.get_running_loop()
        _loop.create_task(_state.agent._init_mcp_tools())
        _loop.create_task(_state.agent._start_memory_cleanup())
        _loop.create_task(_state.agent._warmup_embeddings())
        _loop.create_task(_state.agent._warmup_llm())

    yield


# ── FastAPI 应用 ──────────────────────────────────────────────────────────────
app = FastAPI(
    title="智能体 Agent 服务",
    version="0.1.0",
    description="基于 Ollama 的本地智能体服务",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册所有路由
app.include_router(skills_router)
app.include_router(analytics_router)
app.include_router(conversations_router)
app.include_router(roles_router)
app.include_router(cloud_router)
app.include_router(metrics_router)

from api.projects_router import router as projects_router
app.include_router(projects_router)

from api.health_router      import router as health_router
from api.model_router       import router as model_router
from api.memory_router      import router as memory_router
from api.task_router        import router as task_router
from api.config_router      import router as config_router
from api.system_router      import router as system_router
from api.project_spec_router import router as project_spec_router
from api.chat_router        import router as chat_router
from api.knowledge_router   import router as knowledge_router
from api.image_router       import router as image_router

app.include_router(health_router)
app.include_router(model_router)
app.include_router(memory_router)
app.include_router(task_router)
app.include_router(config_router)
app.include_router(system_router)
app.include_router(project_spec_router)
app.include_router(chat_router)
app.include_router(knowledge_router)
app.include_router(image_router)

from api.teaching_router import router as teaching_router
app.include_router(teaching_router)

from api.feishu_oauth_router import router as feishu_oauth_router
app.include_router(feishu_oauth_router)

app.middleware("http")(metrics_middleware)


# ── 中间件 ────────────────────────────────────────────────────────────────────
@app.middleware("http")
async def rate_limit_middleware(request: Request, call_next):
    if any(request.url.path.startswith(p) for p in ("/api/chat",)):
        client_ip = request.client.host if request.client else "unknown"
        if not _state._rate_limiter.is_allowed(client_ip):
            return JSONResponse(
                status_code=429,
                content={"success": False, "message": "请求过于频繁，请稍后重试"},
            )
    return await call_next(request)


@app.middleware("http")
async def jwt_auth_middleware(request: Request, call_next):
    white_list = ["/health", "/", "/docs", "/openapi.json", "/redoc", "/metrics",
                  "/api/feishu/oauth/callback"]
    if request.url.path.startswith("/api/images/"):
        request.state.user_id = "anonymous"
        return await call_next(request)
    if request.url.path in white_list or not settings.jwt_enabled:
        request.state.user_id = "anonymous"
        return await call_next(request)

    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return JSONResponse(
            status_code=401,
            content={"success": False, "message": "缺少 Authorization 头"},
        )
    try:
        payload = pyjwt.decode(auth[7:], settings.jwt_secret, algorithms=["HS256"])
        jwt_user_id = payload.get("sub", "default")
        x_user_id = request.headers.get("X-User-Id", "")
        if x_user_id and jwt_user_id == "java-service":
            request.state.user_id = x_user_id
        else:
            request.state.user_id = jwt_user_id
    except pyjwt.ExpiredSignatureError:
        return JSONResponse(
            status_code=401, content={"success": False, "message": "token 已过期"}
        )
    except pyjwt.InvalidTokenError as e:
        logger.debug(f"JWT 验证失败: {e}")
        return JSONResponse(
            status_code=401, content={"success": False, "message": "token 无效"}
        )
    return await call_next(request)


if __name__ == "__main__":
    uvicorn.run(
        "api.fastapi_app:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.debug,
        log_level=settings.log_level.lower(),
    )
