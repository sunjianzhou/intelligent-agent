"""Python 智能体 FastAPI 服务入口"""
import asyncio
import time
import threading
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import uvicorn
import logging  # 仅用于抑制 uvicorn 访问日志
import traceback
from loguru import logger
import json as _json
import jwt as pyjwt

from fastapi import Request
from fastapi.responses import JSONResponse, Response
from datetime import datetime
from fastapi.responses import StreamingResponse as _StreamingResponse
from typing import Optional, List, Dict, Any
from contextlib import asynccontextmanager
from config.settings import settings
from services.ollama_provider import OllamaProvider
from services.base_provider import LLMConfig, ChatMessage
from skills.router import router as skills_router
from analytics.router import router as analytics_router
from api.personas_router import (
    router as personas_router,
    _user_personas as _personas_state,
    _read_persona_content as _read_persona_content,
)
from api.metrics import (
    metrics_router, metrics_middleware,
    llm_inference_total, llm_inference_duration_seconds,
    cache_hits_total, cache_misses_total,
    tool_calls_total, inference_active,
)

logging.getLogger("uvicorn.access").setLevel(logging.WARNING)


_LOCAL_DEV_SECRET = "local-dev-only-change-in-production-must-be-32chars"

# ── 已知云端 Provider 的默认 base_url 映射 ────────────────────────────────
# cloud_base_url 留空时自动使用此处的默认值；填了则以填写值为准。
CLOUD_PROVIDER_BASE_URLS: Dict[str, str] = {
    "openai":    "https://api.openai.com/v1",
    "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "deepseek":  "https://api.deepseek.com/v1",
    "zhipu":     "https://open.bigmodel.cn/api/paas/v4",
    "moonshot":  "https://api.moonshot.cn/v1",
    "baidu":     "https://qianfan.baidubce.com/v2",
    "siliconflow": "https://api.siliconflow.cn/v1",
}


def _resolve_cloud_base_url(provider_name: str, configured_url: str) -> str:
    """返回最终使用的 base_url：优先使用配置值，其次查表，都没有则返回空。"""
    if configured_url:
        return configured_url
    return CLOUD_PROVIDER_BASE_URLS.get(provider_name.lower(), "")


# ── Per-user model preferences & provider pool ────────────────────────────
# Maps user_id → provider instance (in-memory; rebuilt from file on restart)
_user_providers: dict = {}

_USER_PREFS_FILE = Path(__file__).parent.parent / "data" / "user_model_prefs.json"

# ── Per-user persona preferences ──────────────────────────────────────────
# Maps user_id → persona_name (string); persona content loaded on demand from .md files
_USER_PERSONA_PREFS_FILE = Path(__file__).parent.parent / "data" / "user_persona_prefs.json"
_RUNTIME_CONFIG_FILE = Path(__file__).parent.parent / "data" / "runtime_config.json"


def _load_user_persona_prefs() -> dict:
    try:
        if _USER_PERSONA_PREFS_FILE.exists():
            return _json.loads(_USER_PERSONA_PREFS_FILE.read_text(encoding="utf-8"))
    except Exception as _e:
        logger.warning(f"读取用户角色偏好文件失败: {_e}")
    return {}


def _save_user_persona_prefs(prefs: dict) -> None:
    try:
        _USER_PERSONA_PREFS_FILE.parent.mkdir(parents=True, exist_ok=True)
        _USER_PERSONA_PREFS_FILE.write_text(
            _json.dumps(prefs, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception as _e:
        logger.warning(f"保存用户角色偏好失败: {_e}")


def _get_user_persona_content(user_id: str) -> str | None:
    """Return the Markdown content for user's active persona, or None for default template."""
    persona_name = _personas_state.get(user_id)
    if not persona_name or persona_name == "default":
        return None
    return _read_persona_content(persona_name)


def _load_runtime_config() -> dict:
    """从 data/runtime_config.json 加载运行时配置（不读 .env）。"""
    try:
        if _RUNTIME_CONFIG_FILE.exists():
            return _json.loads(_RUNTIME_CONFIG_FILE.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"读取运行时配置失败: {e}")
    return {}


def _save_runtime_config(config: dict) -> None:
    """将运行时配置写入 data/runtime_config.json。"""
    try:
        _RUNTIME_CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
        _RUNTIME_CONFIG_FILE.write_text(
            _json.dumps(config, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception as e:
        logger.warning(f"保存运行时配置失败: {e}")


def _load_user_model_prefs() -> dict:
    """Load persisted user→model mappings from disk."""
    try:
        if _USER_PREFS_FILE.exists():
            return _json.loads(_USER_PREFS_FILE.read_text(encoding="utf-8"))
    except Exception as _e:
        logger.warning(f"读取用户模型偏好文件失败: {_e}")
    return {}


def _save_user_model_prefs(prefs: dict) -> None:
    """Persist user→model mappings to disk."""
    try:
        _USER_PREFS_FILE.parent.mkdir(parents=True, exist_ok=True)
        _USER_PREFS_FILE.write_text(
            _json.dumps(prefs, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception as _e:
        logger.warning(f"保存用户模型偏好失败: {_e}")


def _build_provider_for_model(target_model: str):
    """Create a provider instance for target_model. Returns None if unavailable."""
    is_cloud = bool(
        settings.cloud_provider
        and settings.cloud_api_key
        and target_model == settings.cloud_model
    )
    if is_cloud:
        from services.openai_provider import OpenAIProvider
        return OpenAIProvider(
            api_key=settings.cloud_api_key,
            base_url=_resolve_cloud_base_url(settings.cloud_provider, settings.cloud_base_url),
            model=settings.cloud_model,
        )
    try:
        p = OllamaProvider(base_url=settings.ollama_base_url)
        ok = p.switch_model(target_model)
        return p if ok else None
    except Exception:
        return None


def _get_user_provider(user_id: str):
    """Return the effective provider for user_id, falling back to the global provider."""
    return _user_providers.get(user_id) or provider


# ── 轻量级 Token Bucket 限流器（无外部依赖）─────────────────────
class _TokenBucket:
    """每个 IP 独立的 Token Bucket，令牌速率 = rate/s，桶容量 = burst。"""
    __slots__ = ("tokens", "last_ts", "rate", "burst", "_lock")

    def __init__(self, rate: float, burst: int):
        self.rate = rate
        self.burst = burst
        self.tokens = float(burst)
        self.last_ts = time.monotonic()
        self._lock = threading.Lock()

    def consume(self) -> bool:
        with self._lock:
            now = time.monotonic()
            refill = (now - self.last_ts) * self.rate
            self.tokens = min(self.burst, self.tokens + refill)
            self.last_ts = now
            if self.tokens >= 1:
                self.tokens -= 1
                return True
            return False


class _RateLimiter:
    """全局 IP → TokenBucket 映射，定期清理不活跃桶。"""

    def __init__(self, rate: float = 10.0, burst: int = 20,
                 cleanup_interval: int = 300):
        self._rate = rate
        self._burst = burst
        self._buckets: dict[str, _TokenBucket] = {}
        self._lock = threading.Lock()
        self._last_cleanup = time.monotonic()
        self._cleanup_interval = cleanup_interval

    def is_allowed(self, ip: str) -> bool:
        now = time.monotonic()
        with self._lock:
            if now - self._last_cleanup > self._cleanup_interval:
                # 淘汰 5 分钟内无请求的桶
                cutoff = now - self._cleanup_interval
                self._buckets = {
                    k: v for k, v in self._buckets.items()
                    if v.last_ts > cutoff
                }
                self._last_cleanup = now
            if ip not in self._buckets:
                self._buckets[ip] = _TokenBucket(self._rate, self._burst)
        return self._buckets[ip].consume()


# 推理接口限流：默认每 IP 10 req/s，突发 20（本地开发一般不触发）
_rate_limiter = _RateLimiter(rate=10.0, burst=20)

# ── 推理并发控制 ──────────────────────────────────────────────
# 在 lifespan 中初始化（确保绑定到正确的事件循环）
_inference_sem: Optional[asyncio.Semaphore] = None   # 真正推理的并发度
_queue_sem: Optional[asyncio.Semaphore] = None        # 等待队列上限


@asynccontextmanager
async def _inference_slot(queue_timeout: float = 30.0):
    """获取推理槽位的上下文管理器。
    先争抢队列名额（超时 → 503），再争抢推理信号量（等待直到有空位）。
    """
    if _queue_sem is not None:
        try:
            await asyncio.wait_for(_queue_sem.acquire(), timeout=queue_timeout)
        except asyncio.TimeoutError:
            raise HTTPException(status_code=503, detail="服务繁忙，请稍后重试")
    try:
        if _inference_sem is not None:
            await _inference_sem.acquire()
        inference_active.inc()
        yield
    finally:
        inference_active.dec()
        if _inference_sem is not None:
            _inference_sem.release()
        if _queue_sem is not None:
            _queue_sem.release()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _inference_sem, _queue_sem

    # M-11: 先恢复运行时配置，让后续 Semaphore 使用持久化后的值
    for _k, _v in _load_runtime_config().items():
        try:
            if hasattr(settings, _k):
                setattr(settings, _k, type(getattr(settings, _k))(_v))
        except Exception as _e:
            logger.warning(f"恢复运行时配置 {_k}={_v} 失败: {_e}")

    _inference_sem = asyncio.Semaphore(
        getattr(settings, 'inference_concurrency', 3)
    )
    _queue_sem = asyncio.Semaphore(
        getattr(settings, 'inference_queue_size', 20)
    )
    logger.info(
        f"推理并发控制已启用：并发上限={_inference_sem._value}，"
        f"队列上限={_queue_sem._value}"
    )
    # 安全检查：JWT 密钥
    if settings.jwt_enabled:
        if not settings.jwt_secret:
            logger.error("❌ JWT_ENABLED=true 但 JWT_SECRET 为空，所有鉴权请求将被拒绝！请设置 JWT_SECRET 环境变量。")
        elif settings.jwt_secret == _LOCAL_DEV_SECRET:
            if not settings.debug:
                raise RuntimeError(
                    "生产模式下不允许使用默认 JWT 密钥！"
                    "请通过 JWT_SECRET 环境变量设置强随机密钥（建议 ≥32 字符随机字符串）。"
                )
            else:
                logger.warning("⚠️  使用本地开发 JWT 密钥，生产环境请通过 JWT_SECRET 环境变量注入强随机密钥。")

    # 恢复用户模型偏好（从持久化文件重建 per-user provider 实例）
    _saved_prefs = _load_user_model_prefs()
    for _uid, _model_name in _saved_prefs.items():
        try:
            _p = _build_provider_for_model(_model_name)
            if _p:
                _user_providers[_uid] = _p
                logger.info(f"恢复用户 {_uid} 的模型偏好: {_model_name}")
        except Exception as _restore_err:
            logger.warning(f"恢复用户 {_uid} 模型偏好失败: {_restore_err}")

    # 恢复用户角色偏好
    _saved_persona_prefs = _load_user_persona_prefs()
    for _uid, _persona_name in _saved_persona_prefs.items():
        _personas_state[_uid] = _persona_name
        logger.info(f"恢复用户 {_uid} 的角色偏好: {_persona_name}")

    # 启动时预热 embedding 模型
    try:
        if agent and hasattr(agent, 'memory'):
            logger.info("预热 embedding 模型...")
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(
                None,
                lambda: agent.memory.long_term.embedding_model.encode("warmup")
            )
            logger.info("embedding 模型预热完成")
    except Exception as e:
        logger.warning(f"embedding 预热失败（不影响主流程）: {e}")

    # C-1: 后台长期任务须在事件循环运行后调度，不在 __init__ 里调度
    if agent:
        _loop = asyncio.get_running_loop()
        _loop.create_task(agent._init_mcp_tools())
        _loop.create_task(agent._start_memory_cleanup())
        _loop.create_task(agent._warmup_embeddings())

    yield
    # 关闭时清理（可选）


app = FastAPI(
    title="智能体 Agent 服务",
    version="0.1.0",
    description="基于 Ollama 的本地智能体服务",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,   # 不能与 allow_origins=["*"] 同时为 True（CORS spec 禁止）
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(skills_router)
app.include_router(analytics_router)
app.include_router(personas_router)
app.include_router(metrics_router)
app.middleware("http")(metrics_middleware)


@app.middleware("http")
async def rate_limit_middleware(request: Request, call_next):
    """推理接口限流：对 /api/chat 类路径按来源 IP 做 Token Bucket 限速。"""
    rate_limited_prefixes = ("/api/chat",)
    if any(request.url.path.startswith(p) for p in rate_limited_prefixes):
        client_ip = request.client.host if request.client else "unknown"
        if not _rate_limiter.is_allowed(client_ip):
            return JSONResponse(
                status_code=429,
                content={"success": False, "message": "请求过于频繁，请稍后重试"},
            )
    return await call_next(request)


@app.middleware("http")
async def jwt_auth_middleware(request: Request, call_next):
    white_list = ["/health", "/", "/docs", "/openapi.json", "/redoc", "/metrics"]
    # 生成图片直接通过路径前缀放行，避免 Java 代理二进制流时附加 token 复杂度
    if request.url.path.startswith("/api/images/"):
        request.state.user_id = "anonymous"
        return await call_next(request)
    if request.url.path in white_list or not settings.jwt_enabled:
        request.state.user_id = "anonymous"
        return await call_next(request)

    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return JSONResponse(status_code=401,
                            content={"success": False, "message": "缺少 Authorization 头"})

    try:
        payload = pyjwt.decode(auth[7:], settings.jwt_secret, algorithms=["HS256"])
        jwt_user_id = payload.get("sub", "default")
        # X-User-Id: Java 透传的真实前端用户 ID（优先级高于服务间 token 的 sub）
        # 当 JWT sub 是 "java-service" 时，说明是 Java 代理请求，优先读取 X-User-Id
        x_user_id = request.headers.get("X-User-Id", "")
        if x_user_id and jwt_user_id == "java-service":
            request.state.user_id = x_user_id
        else:
            request.state.user_id = jwt_user_id
    except pyjwt.ExpiredSignatureError:
        return JSONResponse(status_code=401,
                            content={"success": False, "message": "token 已过期"})
    except pyjwt.InvalidTokenError as e:
        logger.debug(f"JWT 验证失败: {e}")
        return JSONResponse(status_code=401,
                            content={"success": False, "message": "token 无效"})

    return await call_next(request)


# ── Provider 初始化（云端优先）────────────────────────────
CLOUD_MODE = bool(settings.cloud_provider and settings.cloud_api_key and settings.cloud_model)

if CLOUD_MODE:
    from services.openai_provider import OpenAIProvider
    _effective_cloud_url = _resolve_cloud_base_url(settings.cloud_provider, settings.cloud_base_url)
    provider = OpenAIProvider(
        api_key=settings.cloud_api_key,
        base_url=_effective_cloud_url,
        model=settings.cloud_model,
    )
    OLLAMA_AVAILABLE = True
    logger.info(f"使用云端模型: {settings.cloud_model} ({settings.cloud_provider}, {_effective_cloud_url})")
else:
    try:
        provider = OllamaProvider()
        OLLAMA_AVAILABLE = provider.check_connection()
        logger.info(f"使用本地模型: {provider.current_model}")
    except Exception as e:
        logger.error(f"Provider 初始化失败: {e}")
        provider = None
        OLLAMA_AVAILABLE = False

# ── IntelligentAgent 初始化 ───────────────────────────────
agent = None
if OLLAMA_AVAILABLE and provider:
    try:
        from core.agent import IntelligentAgent

        agent = IntelligentAgent(provider=provider)
        # H-7: 注入依赖，解除 scheduler 对 fastapi_app 的循环 import
        if agent.task_manager:
            sch = agent.task_manager.scheduler
            sch._provider_getter = _get_user_provider
            sch._persona_getter = _get_user_persona_content
            sch._inference_slot = _inference_slot
        logger.info("IntelligentAgent 初始化成功")
    except Exception as e:
        logger.error(f"IntelligentAgent 初始化失败，降级为 Provider 直连: {e}")
        logger.error(traceback.format_exc())


# ── 数据模型 ──────────────────────────────────────────────
class ChatRequest(BaseModel):
    message: str
    use_tools: bool = True
    use_memory: bool = True
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    top_p: Optional[float] = None
    project_id: Optional[str] = None
    pending_tasks: Optional[List[Dict[str, Any]]] = None


class ModelSwitchRequest(BaseModel):
    model: str


class CreateTaskRequest(BaseModel):
    name: str
    description: str = ""
    action: str = "log"
    args: dict = {}
    schedule_type: str = "delay"
    delay_seconds: int = 60
    interval_seconds: int = 300
    cron_expr: Optional[str] = None
    run_at: Optional[str] = None
    max_runs: Optional[int] = None
    tags: list = []


# ── 基础接口 ──────────────────────────────────────────────
@app.get("/")
async def root():
    return {
        "status": "running",
        "ollama_available": OLLAMA_AVAILABLE,
        "model": provider.current_model if provider else "unavailable",
        "agent_ready": agent is not None,
    }


@app.get("/health")
async def health():
    current = provider.current_model if provider else "unavailable"
    # 只要配置了云端就返回，不管当前是否在用
    configured_cloud = bool(settings.cloud_provider and settings.cloud_api_key and settings.cloud_model)
    return {
        "status": "connected",
        "service": "intelligent-agent",
        "ollama_available": OLLAMA_AVAILABLE,
        "model": current,
        "agent_model": current,
        "agent_ready": agent is not None,
        "cloud_mode": CLOUD_MODE,
        "cloud_model": settings.cloud_model if configured_cloud else "",
        "cloud_base_url": settings.cloud_base_url if configured_cloud else "",
        "timestamp": datetime.now().isoformat()
    }


@app.get("/api/models")
async def get_models(http_req: Request):
    ollama_url = (settings.ollama_base_url or "http://localhost:11434").rstrip("/")

    local_models = []
    try:
        import httpx
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.get(f"{ollama_url}/api/tags")
        if r.status_code == 200:
            local_models = [m["name"] for m in r.json().get("models", [])]
    except Exception:
        pass

    # 云端模型只要配了就始终加进列表
    configured_cloud = bool(settings.cloud_provider and settings.cloud_api_key and settings.cloud_model)
    cloud_model_name = settings.cloud_model if configured_cloud else ""

    all_models = local_models[:]
    if cloud_model_name and cloud_model_name not in all_models:
        all_models.append(cloud_model_name)

    # Per-user current model: prefer user's own provider over global default
    user_id = getattr(http_req.state, "user_id", "default") if http_req else "default"
    user_provider = _get_user_provider(user_id)
    current = user_provider.current_model if user_provider else (cloud_model_name if CLOUD_MODE else "")

    # 用户视角的 cloud_mode：若用户主动切换到了 Ollama 本地模型，则为 False
    user_is_cloud = bool(
        configured_cloud and current == cloud_model_name
    )

    return {
        "available_models": all_models,
        "current_model": current,
        "ollama_available": len(local_models) > 0,
        "cloud_mode": user_is_cloud,
        "cloud_model": cloud_model_name if user_is_cloud else "",
        "cloud_provider": settings.cloud_provider if configured_cloud else "",
        "known_cloud_providers": list(CLOUD_PROVIDER_BASE_URLS.keys()),
    }


def _persist_model_to_env(key: str, value: str) -> None:
    """将模型选择写回 .env 文件，实现重启持久化。"""
    import re as _re
    env_path = Path(__file__).parent.parent / ".env"
    if not env_path.exists():
        return
    try:
        text = env_path.read_text(encoding="utf-8")
        pattern = _re.compile(rf"^{key}=.*$", _re.MULTILINE)
        if pattern.search(text):
            text = pattern.sub(f"{key}={value}", text)
        else:
            text += f"\n{key}={value}\n"
        env_path.write_text(text, encoding="utf-8")
        logger.info(f"模型已持久化到 .env: {key}={value}")
    except Exception as e:
        logger.warning(f"持久化模型到 .env 失败: {e}")


@app.post("/api/model/switch")
async def switch_model(request: ModelSwitchRequest, http_req: Request):
    """Per-user model switch: only affects the requesting user, not other concurrent sessions."""
    user_id = getattr(http_req.state, "user_id", "default")
    target_model = request.model

    new_provider = _build_provider_for_model(target_model)
    if new_provider is None:
        return {"success": False, "message": f"模型 {target_model} 不可用或切换失败"}

    # Store provider for this user only
    _user_providers[user_id] = new_provider

    # Persist model name preference so it survives restarts
    prefs = _load_user_model_prefs()
    prefs[user_id] = target_model
    _save_user_model_prefs(prefs)

    is_cloud = bool(
        settings.cloud_provider
        and settings.cloud_api_key
        and target_model == settings.cloud_model
    )
    logger.info(f"用户 {user_id} 切换模型 → {target_model} ({'云端' if is_cloud else '本地'})")
    return {
        "success": True,
        "current_model": new_provider.current_model,
        "cloud_mode": is_cloud,
    }


def _require_admin(http_req: Request) -> None:
    """仅允许 admin 用户调用危险写操作（批量写记忆、手动蒸馏等）。
    JWT 关闭时跳过检查（兼容本地开发环境）。"""
    if not settings.jwt_enabled:
        return
    user_id = getattr(http_req.state, "user_id", "anonymous")
    if user_id not in ("admin", "java-service"):
        raise HTTPException(status_code=403, detail="仅管理员可调用此接口")


@app.post("/api/memory/batch-import")
async def batch_import_memory(body: dict, http_req: Request):
    """批量导入记忆条目（WANT-002）。body.items: list of {content, category?, importance?}"""
    _require_admin(http_req)
    if not agent:
        return {"success": False, "message": "Agent 未初始化"}
    items = body.get("items", [])
    if not items:
        return {"success": False, "message": "items 不能为空"}
    imported = 0
    errors = []
    for idx, item in enumerate(items[:200]):  # 最多 200 条
        try:
            content = str(item.get("content", "")).strip()
            if not content:
                continue
            importance = max(0.0, min(1.0, float(item.get("importance", 0.6))))
            category   = item.get("category", "knowledge")
            # store(content, metadata, importance) — 正确调用签名
            agent.memory.long_term.store(
                content,
                metadata={"category": category, "type": "imported"},
                importance=importance,
            )
            imported += 1
        except Exception as e:
            errors.append(f"第{idx+1}条: {e}")
    return {"success": True, "imported": imported, "errors": errors}


@app.post("/api/memory/distill")
async def trigger_distill(http_req: Request):
    """手动触发记忆蒸馏（仅 admin，防止未授权 LLM 调用）"""
    _require_admin(http_req)
    if not agent:
        return {"success": False, "message": "Agent 未初始化"}
    try:
        await agent._cleanup_memories()
        return {"success": True, "message": "记忆整理完成"}
    except Exception as e:
        return {"success": False, "message": str(e)}


@app.get("/api/notifications/poll")
async def poll_notifications():
    """前端轮询端点：取出并返回所有待推送通知（执行后清空队列）。"""
    try:
        from scheduler.simple_scheduler import pop_notifications
        items = pop_notifications()
        return {"notifications": items, "count": len(items)}
    except Exception as e:
        logger.debug(f"notifications poll: {e}")
        return {"notifications": [], "count": 0}


@app.get("/api/tools/list")
async def list_tools():
    if agent:
        all_tools = agent.tool_manager.get_all_tools()
        name_to_category = {}
        for cat, names in agent.tool_manager.tool_categories.items():
            for n in names:
                name_to_category[n] = cat
        tools = [
            {
                "name": name,
                "description": tool.description,
                "category": name_to_category.get(name, "general"),
                "enabled": True,
            }
            for name, tool in all_tools.items()
        ]
        return {"tools": tools, "count": len(tools)}

    return {
        "tools": [
            {"name": "calculator", "description": "数学计算", "category": "math", "enabled": True},
            {"name": "time_tool", "description": "时间查询", "category": "utility", "enabled": True},
            {"name": "file_tool", "description": "文件读写", "category": "file", "enabled": True},
            {"name": "web_search", "description": "网络搜索", "category": "web", "enabled": False},
            {"name": "shell_tool", "description": "Shell执行", "category": "system", "enabled": False},
        ],
        "count": 5
    }


@app.post("/api/chat")
async def chat(request: ChatRequest, http_req: Request):
    user_id = getattr(http_req.state, "user_id", "default")
    logger.info(f"收到聊天请求 [user={user_id}, len={len(request.message)}]")

    user_provider = _get_user_provider(user_id)
    user_persona_content = _get_user_persona_content(user_id)
    async with _inference_slot():
        if agent and OLLAMA_AVAILABLE:
            try:
                result = await agent.chat(
                    message=request.message,
                    use_tools=request.use_tools,
                    use_memory=request.use_memory,
                    user_id=user_id,
                    provider_override=user_provider,
                    persona_override=user_persona_content,
                    project_id=request.project_id,
                    pending_tasks=request.pending_tasks,
                )
                return {
                    "response": result["content"],
                    "tool_calls": result["tool_calls"],
                    "model": user_provider.current_model if user_provider else "",
                    "agent_mode": True,
                    "ollama_available": OLLAMA_AVAILABLE,
                }
            except Exception as e:
                logger.error(f"Agent 调用异常: {e}\n{traceback.format_exc()}")

        # 降级：Provider 直连（使用用户自己的 provider）
        if user_provider and OLLAMA_AVAILABLE:
            try:
                config = LLMConfig(
                    temperature=request.temperature or settings.ollama_temperature,
                    max_tokens=request.max_tokens or settings.ollama_max_tokens,
                    top_p=request.top_p or settings.ollama_top_p,
                )
                messages = [
                    ChatMessage(role='system', content=(
                        f"你是一个有帮助的AI助手，请用中文回答。"
                        f"当前时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
                    )),
                    ChatMessage(role='user', content=request.message)
                ]
                resp = user_provider.chat(messages, config)
                return {
                    "response": resp.content,
                    "tool_calls": [],
                    "model": resp.model,
                    "agent_mode": False,
                    "ollama_available": OLLAMA_AVAILABLE,
                }
            except Exception as e:
                logger.error(f"Provider 直连异常: {e}")

        return {
            "response": "服务暂时不可用，请检查 Ollama 服务状态。",
            "tool_calls": [],
            "model": "unavailable",
            "agent_mode": False,
            "ollama_available": OLLAMA_AVAILABLE,
        }


@app.post("/api/chat/stream")
async def chat_stream_endpoint(request: ChatRequest, http_req: Request):
    """SSE 流式聊天接口（并发受 _inference_slot 限制）。
    Agent 可用时走完整 ReAct 循环；降级时走 Provider 直连流式输出。
    """
    user_id = getattr(http_req.state, "user_id", "default")
    user_provider = _get_user_provider(user_id)
    user_persona_content = _get_user_persona_content(user_id)

    if not OLLAMA_AVAILABLE:
        async def err():
            yield f"data: {_json.dumps({'type': 'error', 'data': 'Provider 不可用'}, ensure_ascii=False)}\n\n"
        return _StreamingResponse(err(), media_type="text/event-stream")

    cancel_ev = asyncio.Event()

    async def generate():
        try:
            async with _inference_slot():
                if agent:
                    # ── 完整 Agent 模式（ReAct + 工具 + 记忆）────────
                    async for event_type, data in agent.chat_stream(
                            message=request.message,
                            use_tools=request.use_tools,
                            use_memory=request.use_memory,
                            cancel_event=cancel_ev,
                            user_id=user_id,
                            provider_override=user_provider,
                            persona_override=user_persona_content,
                            project_id=request.project_id,
                            pending_tasks=request.pending_tasks,
                    ):
                        yield f"data: {_json.dumps({'type': event_type, 'data': data}, ensure_ascii=False)}\n\n"

                elif user_provider:
                    # ── 降级：Provider 直连流式输出（无工具/记忆）──────
                    logger.warning("Agent 不可用，降级为 Provider 流式直连")
                    config = LLMConfig(
                        temperature=request.temperature or settings.ollama_temperature,
                        max_tokens=request.max_tokens or settings.ollama_max_tokens,
                        top_p=request.top_p or settings.ollama_top_p,
                    )
                    chat_messages = [
                        ChatMessage(role='system', content=(
                            f"你是一个有帮助的AI助手，请用中文回答。"
                            f"当前时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
                        )),
                        ChatMessage(role='user', content=request.message),
                    ]
                    loop = asyncio.get_running_loop()
                    queue: asyncio.Queue = asyncio.Queue(maxsize=100)
                    DONE = object()

                    def _producer():
                        try:
                            for token in user_provider.chat_stream_generator(chat_messages, config):
                                if cancel_ev.is_set():
                                    break
                                asyncio.run_coroutine_threadsafe(queue.put(token), loop).result(timeout=5)
                        except Exception as _e:
                            logger.warning(f"Provider 流式中断: {_e}")
                        finally:
                            asyncio.run_coroutine_threadsafe(queue.put(DONE), loop).result(timeout=5)

                    loop.run_in_executor(None, _producer)
                    full = ""
                    while True:
                        item = await queue.get()
                        if item is DONE:
                            break
                        full += item
                        yield f"data: {_json.dumps({'type': 'token', 'data': item}, ensure_ascii=False)}\n\n"
                    yield f"data: {_json.dumps({'type': 'done', 'data': {'content': full}}, ensure_ascii=False)}\n\n"

                else:
                    yield f"data: {_json.dumps({'type': 'error', 'data': '服务暂时不可用'}, ensure_ascii=False)}\n\n"

        except HTTPException as e:
            yield f"data: {_json.dumps({'type': 'error', 'data': e.detail}, ensure_ascii=False)}\n\n"
        except (asyncio.CancelledError, GeneratorExit):
            cancel_ev.set()
        except Exception as e:
            logger.error(f"流式生成异常: {e}")
            yield f"data: {_json.dumps({'type': 'error', 'data': str(e)}, ensure_ascii=False)}\n\n"
        finally:
            cancel_ev.set()

    return _StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ── 记忆接口 ──────────────────────────────────────────────
@app.get("/api/memory")
async def get_memory():
    if agent:
        return {"stats": agent.get_memory_info()}
    return {"stats": {"long_term": {"count": 0}, "short_term": {"count": 0}}}


@app.delete("/api/memory")
async def clear_memory():
    if agent:
        agent.clear_history()
        return {"message": "记忆已清空"}
    return {"message": "Agent 未初始化"}


@app.get("/api/memory/list")
async def list_memories(memory_type: str = "long_term", limit: int = 50):
    if not agent:
        return {"memories": [], "count": 0}
    items = (agent.memory.short_term if memory_type == "short_term"
             else agent.memory.long_term).list(limit=limit)
    memories = [
        {
            "id": m.id,
            "content": m.content,
            "importance": round(m.importance, 2),
            "category": m.metadata.get("category") or m.metadata.get("type") or "unknown",
            "role": m.metadata.get("role", ""),
            "type": m.metadata.get("type", ""),
            "created_at": m.created_at.isoformat(),
            "access_count": m.access_count,
        }
        for m in items
    ]
    return {"memories": memories, "count": len(memories), "type": memory_type}


@app.delete("/api/memory/{memory_id}")
async def delete_memory(memory_id: str):
    if not agent:
        return {"success": False, "message": "Agent 未初始化"}
    ok = agent.memory.long_term.delete(memory_id)
    return ({"success": True, "message": f"已删除记忆 {memory_id}"}
            if ok else
            {"success": False, "message": f"记忆 {memory_id} 不存在"})


@app.patch("/api/memory/{memory_id}/importance")
async def update_memory_importance(memory_id: str, body: dict):
    """更新长期记忆的重要性分值（WANT-007）。"""
    if not agent:
        return {"success": False, "message": "Agent 未初始化"}
    importance = max(0.0, min(1.0, float(body.get("importance", 0.5))))
    try:
        lt = agent.memory.long_term
        if lt.collection is None:
            return {"success": False, "message": "ChromaDB 不可用，重要性更改不会持久化，请稍后重试"}
        # 1. 先更新 ChromaDB（持久层），成功后再更新内存缓存，避免两者状态不一致
        existing = lt.collection.get(ids=[memory_id], include=["metadatas"])
        if existing and existing["ids"]:
            meta = (existing["metadatas"] or [{}])[0] or {}
            meta["importance"] = importance
            lt.collection.update(ids=[memory_id], metadatas=[meta])
        # 2. 更新内存缓存（memories 是公开属性）
        if memory_id in lt.memories:
            lt.memories[memory_id].importance = importance
        return {"success": True, "memory_id": memory_id, "importance": importance}
    except Exception as e:
        logger.warning(f"更新记忆重要性失败: {e}")
        return {"success": False, "message": str(e)}


@app.get("/api/memory/search")
async def search_memory(q: str, limit: int = 10):
    if not agent:
        return {"results": []}
    from memory.base import MemoryQuery
    results = agent.memory.long_term.retrieve(MemoryQuery(text=q, limit=limit, threshold=0.2))
    return {
        "results": [
            {
                "id": r.memory.id,
                "content": r.memory.content,
                "similarity": round(r.similarity, 3),
                "importance": round(r.memory.importance, 2),
                "category": r.memory.metadata.get("category") or r.memory.metadata.get("type") or "unknown",
                "created_at": r.memory.created_at.isoformat(),
            }
            for r in results
        ]
    }


@app.get("/api/memory/summaries")
async def get_memory_summaries(request: Request, limit: int = 30):
    """返回当前用户的阶段性对话摘要列表（按时间倒序）。"""
    if not agent:
        return {"summaries": [], "count": 0}
    user_id = getattr(request.state, "user_id", "default") if request else "default"
    lt = agent.memory.long_term

    # 用 ChromaDB 服务端 where 过滤，不再 list(limit=1000) 全量加载再 Python 过滤
    if lt.collection is not None:
        try:
            if user_id in ("default", "anonymous"):
                where_clause = {"type": {"$eq": "session_summary"}}
            else:
                where_clause = {
                    "$and": [
                        {"type": {"$eq": "session_summary"}},
                        {"user_id": {"$eq": user_id}},
                    ]
                }
            results = lt.collection.get(
                where=where_clause,
                limit=limit,
                include=["documents", "metadatas"],
            )
            ids       = results.get("ids", []) or []
            docs      = results.get("documents", []) or []
            metas     = results.get("metadatas", []) or []
            summaries = []
            for i, sid in enumerate(ids):
                meta = metas[i] if i < len(metas) else {}
                summaries.append({
                    "id":         sid,
                    "content":    docs[i] if i < len(docs) else "",
                    "timestamp":  (meta or {}).get("timestamp", ""),
                    "created_at": (meta or {}).get("timestamp", ""),
                })
            summaries.sort(key=lambda s: s["timestamp"], reverse=True)
            return {"summaries": summaries, "count": len(summaries)}
        except Exception as _e:
            logger.warning(f"ChromaDB summaries 查询失败，回退全量扫描: {_e}")

    # 回退：全量加载（ChromaDB 不可用时）
    all_mems = lt.list(limit=500)
    summaries = [
        m for m in all_mems
        if m.metadata.get("type") == "session_summary"
        and (user_id in ("default", "anonymous")
             or m.metadata.get("user_id") == user_id)
    ]
    summaries.sort(key=lambda m: m.created_at, reverse=True)
    return {
        "summaries": [
            {
                "id":         m.id,
                "content":    m.content,
                "timestamp":  m.metadata.get("timestamp", m.created_at.strftime("%Y-%m-%d %H:%M")),
                "created_at": m.created_at.isoformat(),
            }
            for m in summaries[:limit]
        ],
        "count": len(summaries[:limit]),
    }


@app.get("/api/memory/export")
async def export_memory(request: Request, format: str = "json"):
    """导出当前用户的全部长期记忆。format=json|markdown"""
    if not agent:
        return Response(content="[]", media_type="application/json")
    user_id = getattr(request.state, "user_id", "default") if request else "default"
    all_mems = agent.memory.long_term.list(limit=2000)
    if user_id not in ("default", "anonymous"):
        all_mems = [m for m in all_mems if m.metadata.get("user_id") == user_id]

    if format == "markdown":
        facts     = [m for m in all_mems if m.metadata.get("type") == "fact"]
        summaries = [m for m in all_mems if m.metadata.get("type") == "session_summary"]
        others    = [m for m in all_mems if m.metadata.get("type") not in ("fact", "session_summary")]

        lines = [f"# 记忆导出 — {user_id}\n\n生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M')}\n"]
        if facts:
            lines.append("\n## 用户事实\n")
            for m in facts:
                lines.append(f"- {m.content}\n")
        if summaries:
            lines.append("\n## 阶段摘要\n")
            for m in sorted(summaries, key=lambda x: x.created_at):
                ts = m.metadata.get("timestamp", m.created_at.strftime("%Y-%m-%d %H:%M"))
                lines.append(f"\n### {ts}\n{m.content}\n")
        if others:
            lines.append("\n## 其他记忆\n")
            for m in others:
                lines.append(f"- [{m.metadata.get('type', '?')}] {m.content}\n")

        content = "".join(lines)
        filename = f"memory_{user_id}_{datetime.now().strftime('%Y%m%d')}.md"
        return Response(
            content=content,
            media_type="text/markdown; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )
    else:
        data = [
            {
                "id": m.id,
                "content": m.content,
                "type": m.metadata.get("type", "unknown"),
                "importance": round(m.importance, 2),
                "created_at": m.created_at.isoformat(),
                "metadata": {k: v for k, v in m.metadata.items() if k != "user_id"},
            }
            for m in all_mems
        ]
        filename = f"memory_{user_id}_{datetime.now().strftime('%Y%m%d')}.json"
        return Response(
            content=_json.dumps(data, ensure_ascii=False, indent=2),
            media_type="application/json; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )


# ── 任务接口 ──────────────────────────────────────────────
@app.get("/api/tasks/list")
async def list_tasks(status: Optional[str] = None, limit: int = 50):
    if not agent or not agent.task_manager:
        return {"tasks": [], "count": 0}
    tasks = agent.task_manager.list_tasks(status=status, limit=limit)
    return {"tasks": tasks, "count": len(tasks)}


@app.post("/api/tasks/create")
async def create_task(request: CreateTaskRequest, http_req: Request):
    if not agent or not agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    try:
        scheduler = agent.task_manager.scheduler
        user_id = getattr(http_req.state, "user_id", "java-service")
        args = dict(request.args)
        if request.action == "log" and "message" not in args:
            args["message"] = request.name
        # llm_generate 任务：自动注入创建者 user_id，让任务执行时能还原用户的 model/persona
        if request.action == "llm_generate" and "user_id" not in args:
            args["user_id"] = user_id
        kwargs = dict(
            name=request.name,
            action=request.action,
            args=args,
            description=request.description,
            tags=request.tags,
        )
        if request.schedule_type == "delay":
            task = scheduler.create_task(schedule_type="delay",
                                         delay_seconds=request.delay_seconds, **kwargs)
        elif request.schedule_type == "interval":
            task = scheduler.create_task(schedule_type="interval",
                                         interval_seconds=request.interval_seconds, **kwargs)
        elif request.schedule_type == "datetime":
            from datetime import datetime as dt
            run_at = dt.fromisoformat(request.run_at) if request.run_at else dt.now()
            task = scheduler.create_task(schedule_type="datetime", run_at=run_at, **kwargs)
        elif request.schedule_type == "cron":
            if not request.cron_expr:
                return {"success": False, "message": "Cron 任务需要 cron_expr 参数"}
            task = scheduler.create_task(schedule_type="cron",
                                         cron_expr=request.cron_expr, **kwargs)
        else:
            task = scheduler.create_task(schedule_type="immediate", **kwargs)
        return {"success": True, "task": task.to_dict()}
    except Exception as e:
        logger.error(f"创建任务失败: {e}")
        return {"success": False, "message": str(e)}


@app.delete("/api/tasks/{task_id}")
async def delete_task(task_id: str):
    if not agent or not agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    return agent.task_manager.delete_task(task_id)


@app.patch("/api/tasks/{task_id}")
async def update_task(task_id: str, body: dict):
    if not agent or not agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    scheduler = agent.task_manager.scheduler
    # args.message / args.role 可直接在 body 顶层传，也可嵌套在 args 里
    kwargs = dict(body)
    top_level_args = {}
    for key in ("message", "role"):
        if key in kwargs:
            top_level_args[key] = kwargs.pop(key)
    if top_level_args:
        kwargs.setdefault("args", {})
        kwargs["args"].update(top_level_args)
    task = scheduler.update_task(task_id, **kwargs)
    if task:
        return {"success": True, "task": task.to_dict()}
    return {"success": False, "message": "任务不存在"}


@app.post("/api/tasks/{task_id}/cancel")
async def cancel_task(task_id: str):
    if not agent or not agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    return agent.task_manager.cancel_task(task_id)


@app.post("/api/tasks/{task_id}/execute")
async def execute_task_now(task_id: str):
    """立即触发任务执行（fire-and-forget：立即返回 HTTP 200，任务在后台运行）。
    之所以不 await，是因为 llm_generate 等任务需要 30-300s，同步等待会卡住 HTTP 连接。
    前端轮询获取执行结果。
    """
    if not agent or not agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    scheduler = agent.task_manager.scheduler
    task = scheduler.get_task(task_id)
    if not task:
        return {"success": False, "message": "任务不存在"}
    from scheduler.simple_models import SimpleTaskStatus
    if task.status == SimpleTaskStatus.RUNNING:
        return {"success": False, "message": "任务正在执行中，请等待当前轮次完成"}
    # 异步触发，立即返回
    asyncio.create_task(scheduler.execute_task(task_id))
    return {"success": True, "message": "任务已触发，后台执行中"}


@app.get("/api/tasks/stats")
async def task_stats():
    if not agent or not agent.task_manager:
        return {"total_tasks": 0, "scheduler_running": False}
    return agent.task_manager.get_stats()


@app.get("/api/tasks/actions")
async def list_actions():
    if not agent or not agent.task_manager:
        return {"actions": ["log", "llm_generate"]}
    return {"actions": list(agent.task_manager.scheduler.actions.keys())}


# ── 系统资源接口 ──────────────────────────────────────────
@app.get("/api/system/resources")
async def get_system_resources():
    import psutil

    def _collect_cpu():
        # psutil.cpu_percent(interval>0) 会阻塞 interval 秒，必须在 executor 中运行
        overall = psutil.cpu_percent(interval=0.2, percpu=False)
        per_core = psutil.cpu_percent(interval=0.1, percpu=True)
        return overall, per_core

    loop = asyncio.get_running_loop()
    cpu_percent, cpu_per_core = await loop.run_in_executor(None, _collect_cpu)
    cpu_count = psutil.cpu_count(logical=True)
    mem = psutil.virtual_memory()

    result = {
        "cpu_percent": round(cpu_percent, 1),
        "cpu_count": cpu_count,
        "cpu_used_cores": round(cpu_percent / 100 * cpu_count, 2),
        "cpu_per_core": [round(x, 1) for x in cpu_per_core],
        "memory_percent": round(mem.percent, 1),
        "memory_used_gb": round(mem.used / 1024 ** 3, 2),
        "memory_total_gb": round(mem.total / 1024 ** 3, 2),
        "memory_avail_gb": round(mem.available / 1024 ** 3, 2),
        "processes": {},
        "disks": [],
        "ollama_models": [],
        "gpu": None,
        "timestamp": datetime.now().isoformat(),
        # 前端 token 用量进度条使用此字段同步上下文大小
        "ollama_num_ctx": settings.ollama_num_ctx,
    }

    # 进程内存——三层：
    #   1. Agent 服务关键词精确追踪
    #   2. 其他进程按 name 分组，取 Top-10 返回给前端
    #   3. 「其他进程」兜底项 = 系统已用 - Agent 合计，让数字对齐
    process_keywords = {
        "ollama":   "Ollama",
        "uvicorn":  "Python Agent",
        "java":     "Java 后端",
        "node":     "前端(Node)",
        "vite":     "前端(Vite)",
    }
    found: dict = {}
    other_by_name: dict = {}   # {display_name: {mem_mb, count}}
    try:
        for proc in psutil.process_iter(['pid', 'name', 'memory_info', 'cmdline']):
            try:
                pname   = (proc.info['name'] or '').lower()
                cmdline = ' '.join(proc.info['cmdline'] or []).lower()
                mem_mb  = round(proc.info['memory_info'].rss / 1024 ** 2, 1)
                matched = False
                for kw, label in process_keywords.items():
                    if kw in pname or kw in cmdline:
                        if label not in found:
                            found[label] = {"mem_mb": 0, "pids": []}
                        found[label]["mem_mb"] = round(found[label]["mem_mb"] + mem_mb, 1)
                        found[label]["pids"].append(proc.info['pid'])
                        matched = True
                        break
                if not matched and mem_mb >= 5:   # 忽略 <5MB 的微小进程，减少噪音
                    display = proc.info['name'] or 'unknown'
                    if display not in other_by_name:
                        other_by_name[display] = {"mem_mb": 0.0, "count": 0}
                    other_by_name[display]["mem_mb"] = round(other_by_name[display]["mem_mb"] + mem_mb, 1)
                    other_by_name[display]["count"] += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
    except Exception as e:
        result["process_error"] = str(e)

    # Top-10 其他进程（按内存降序）
    top_others = sorted(other_by_name.items(), key=lambda x: x[1]["mem_mb"], reverse=True)[:10]
    result["top_other_processes"] = [
        {"name": name, "mem_mb": info["mem_mb"], "count": info["count"]}
        for name, info in top_others
    ]

    # 「其他进程」兜底 = 系统已用 - Agent 合计
    agent_total_mb = sum(v["mem_mb"] for v in found.values())
    sys_used_mb    = round(result.get("memory_used_gb", 0) * 1024, 1)
    other_real_mb  = max(0.0, round(sys_used_mb - agent_total_mb, 1))

    result["processes"] = {
        label: {"mem_mb": info["mem_mb"], "pid_count": len(info["pids"])}
        for label, info in found.items()
    }
    result["processes"]["其他进程"] = {"mem_mb": other_real_mb, "pid_count": -1}
    result["agent_total_mb"] = agent_total_mb
    result["other_total_mb"] = other_real_mb

    # 磁盘（兼容 Windows 主机和 Docker Linux 容器）
    try:
        import platform as _platform
        _is_windows = _platform.system() == "Windows"
        disks = []
        seen_devices = set()
        for part in psutil.disk_partitions(all=False):
            if part.device in seen_devices:
                continue
            mp = part.mountpoint
            if _is_windows:
                # Windows：只收集盘符（C:\、D:\ 等）
                if mp not in ('C:\\', 'D:\\', 'E:\\', 'F:\\', 'G:\\') and not mp.endswith(':\\'):
                    continue
            else:
                # Linux/Docker：只收集主要挂载点，跳过伪文件系统和 Docker bind-mount 文件
                skip_prefixes = ('/proc', '/sys', '/dev', '/run', '/snap', '/etc/')
                skip_fs = {'tmpfs', 'devtmpfs', 'sysfs', 'proc', 'overlay', 'nsfs', 'cgroup'}
                if any(mp.startswith(p) for p in skip_prefixes):
                    continue
                if part.fstype in skip_fs:
                    continue
                # 跳过指向文件（非目录）的挂载点（Docker 注入的 /etc/hosts 等）
                import os as _os
                if not _os.path.isdir(mp):
                    continue
            try:
                usage = psutil.disk_usage(mp)
                seen_devices.add(part.device)
                disks.append({
                    "mountpoint": mp,
                    "total_gb": round(usage.total / 1024 ** 3, 1),
                    "used_gb": round(usage.used / 1024 ** 3, 1),
                    "free_gb": round(usage.free / 1024 ** 3, 1),
                    "percent": usage.percent,
                })
            except Exception:
                continue
        result["disks"] = disks
    except Exception as e:
        result["disk_error"] = str(e)

    # Ollama 已加载模型（使用配置的 base_url，确保 Docker 内可访问）
    try:
        import requests as _req
        _ollama_url = (settings.ollama_base_url or "http://localhost:11434").rstrip("/")
        r = _req.get(f"{_ollama_url}/api/ps", timeout=3)
        if r.status_code == 200:
            result["ollama_models"] = [
                {
                    "name": m.get("name", ""),
                    "size_gb": round(m.get("size", 0) / 1024 ** 3, 2),
                    "vram_gb": round(m.get("size_vram", 0) / 1024 ** 3, 2),
                    "expires_at": m.get("expires_at", ""),
                }
                for m in r.json().get("models", [])
            ]
    except Exception:
        result["ollama_models"] = []

    # GPU
    try:
        import pynvml
        pynvml.nvmlInit()
        handle = pynvml.nvmlDeviceGetHandleByIndex(0)
        gpu_util = pynvml.nvmlDeviceGetUtilizationRates(handle)
        gpu_mem = pynvml.nvmlDeviceGetMemoryInfo(handle)
        gpu_temp = pynvml.nvmlDeviceGetTemperature(handle, pynvml.NVML_TEMPERATURE_GPU)
        gpu_name = pynvml.nvmlDeviceGetName(handle)
        pynvml.nvmlShutdown()
        result["gpu"] = {
            "name": gpu_name if isinstance(gpu_name, str) else gpu_name.decode(),
            "util_percent": gpu_util.gpu,
            "mem_used_mb": round(gpu_mem.used / 1024 ** 2),
            "mem_total_mb": round(gpu_mem.total / 1024 ** 2),
            "mem_percent": round(gpu_mem.used / gpu_mem.total * 100, 1),
            "temperature": gpu_temp,
        }
    except Exception as e:
        result["gpu_error"] = str(e)

    return result


# ── 项目接口（上下文持久化 / 规格驱动 / 任务分解）────────────

class ProjectSpecRequest(BaseModel):
    project_id: str
    content: str
    version: int = 1


class ProjectContextExtractRequest(BaseModel):
    project_id: str
    user_id: str = "default"


class TaskDecomposeRequest(BaseModel):
    project_id: str
    task_description: Optional[str] = None


@app.put("/api/project/spec")
async def put_project_spec(request: ProjectSpecRequest):
    """保存项目规格文档到 ChromaDB（delete + re-insert）。"""
    if not agent:
        raise HTTPException(status_code=503, detail="Agent 未初始化")
    try:
        lt = agent.memory.long_term
        if lt.collection is None:
            raise HTTPException(status_code=503, detail="ChromaDB 不可用")
        spec_id = f"spec_{request.project_id}_v{request.version}"
        # 删除旧版本（如有）
        try:
            existing = lt.collection.get(
                where={"project_id": request.project_id, "type": "project_spec"},
                include=["metadatas"],
            )
            if existing and existing.get("ids"):
                lt.collection.delete(ids=existing["ids"])
        except Exception:
            pass
        # 插入新版本
        lt.collection.add(
            ids=[spec_id],
            documents=[request.content],
            metadatas=[{
                "type":       "project_spec",
                "project_id": request.project_id,
                "version":    request.version,
            }],
        )
        return {"project_id": request.project_id, "version": request.version, "synced": True}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"保存规格失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/project/spec")
async def get_project_spec(project_id: str):
    """读取项目最新规格文档。"""
    if not agent:
        return {"project_id": project_id, "content": "", "version": 0}
    try:
        lt = agent.memory.long_term
        if lt.collection is None:
            return {"project_id": project_id, "content": "", "version": 0}
        results = lt.collection.get(
            where={"project_id": project_id, "type": "project_spec"},
            include=["documents", "metadatas"],
        )
        if not results or not results.get("ids"):
            return {"project_id": project_id, "content": "", "version": 0}
        # 取最新版本
        pairs = list(zip(results["documents"], results["metadatas"]))
        pairs.sort(key=lambda x: x[1].get("version", 0), reverse=True)
        doc, meta = pairs[0]
        return {"project_id": project_id, "content": doc, "version": meta.get("version", 1)}
    except Exception as e:
        logger.warning(f"读取规格失败: {e}")
        return {"project_id": project_id, "content": "", "version": 0}


@app.post("/api/project/context/extract")
async def extract_project_context(request: ProjectContextExtractRequest):
    """触发项目上下文提取（后台 LLM 任务）。"""
    if not agent:
        raise HTTPException(status_code=503, detail="Agent 未初始化")
    try:
        items = agent.memory.short_term.list(limit=agent._context_extractor.interval * 4)
        stored = await agent._context_extractor.extract(
            project_id=request.project_id,
            user_id=request.user_id,
            short_term_items=items,
            call_model_fn=agent._call_model,
            chroma_client=agent.memory.long_term.vector_db,
            embedding_model=agent.memory.long_term.embedding_model,
            persist_dir=agent.memory.long_term.persist_dir,
        )
        version = agent._context_extractor._turn_counts.get(
            f"{request.user_id}:{request.project_id}", 0)
        return {"extracted": stored, "version": version}
    except Exception as e:
        logger.error(f"上下文提取失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/project/context")
async def get_project_context(project_id: str, query: str = "", limit: int = 5):
    """查询项目上下文 nuggets（语义搜索）。"""
    if not agent:
        return {"nuggets": []}
    nuggets = await agent._get_project_context(project_id, query or "general", limit)
    return {"project_id": project_id, "nuggets": nuggets}


@app.post("/api/project/tasks/decompose")
async def decompose_project_tasks(request: TaskDecomposeRequest):
    """用 LLM 将任务描述分解为子任务树。"""
    if not agent:
        raise HTTPException(status_code=503, detail="Agent 未初始化")
    # If no task_description, try to use the project spec as the decomposition basis
    task_desc = request.task_description
    if not task_desc:
        try:
            lt = agent.memory.long_term
            if lt.collection is not None:
                spec_res = lt.collection.get(
                    where={"project_id": request.project_id, "type": "project_spec"},
                    include=["documents"],
                )
                if spec_res and spec_res.get("documents"):
                    task_desc = f"（基于项目规格文档）\n{spec_res['documents'][0][:800]}"
        except Exception:
            pass
    if not task_desc:
        task_desc = "请根据项目目标合理规划任务"

    prompt = (
        "请分析以下任务并将其分解为子任务树，以JSON格式输出。"
        "要求：最多3层，每层最多5个节点，每个节点包含 id(task_xxx格式)、title、status(值固定为pending)、subtasks(数组)。"
        "只输出JSON，格式：{\"task_tree\": [{\"id\":\"task_001\",\"title\":\"...\","
        "\"status\":\"pending\",\"subtasks\":[],\"notes\":\"\","
        "\"created_at\":\"now\",\"completed_at\":null}]}\n\n"
        f"任务描述：{task_desc}"
    )
    try:
        import uuid as _uuid
        from datetime import datetime as _dt
        raw = await agent._call_model([
            {"role": "system", "content": "你是任务分解助手，只输出JSON。"},
            {"role": "user",   "content": prompt},
        ], fallback_timeout=90)
        clean = raw.strip().replace("```json", "").replace("```", "").strip()
        parsed = _json.loads(clean)
        task_tree = parsed.get("task_tree", [])

        def _fill_ids(tasks, depth=0):
            for t in tasks:
                if not t.get("id"):
                    t["id"] = f"task_{_uuid.uuid4().hex[:8]}"
                t.setdefault("status",       "pending")
                t.setdefault("notes",        "")
                t.setdefault("created_at",   _dt.now().isoformat())
                t.setdefault("completed_at", None)
                if depth < 3:
                    _fill_ids(t.get("subtasks", []), depth + 1)
        _fill_ids(task_tree)
        return {
            "project_id": request.project_id,
            "task_tree":  {"root_tasks": task_tree, "auto_decompose": True},
        }
    except _json.JSONDecodeError:
        raise HTTPException(status_code=422, detail="LLM 返回格式无效，请重试")
    except Exception as e:
        logger.error(f"任务分解失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/project/tasks")
async def get_project_tasks(project_id: str):
    """读取项目任务列表（此版本任务存储在客户端 IDB，服务端仅返回空响应供兼容）。"""
    return {"project_id": project_id, "task_tree": [], "note": "tasks are client-owned"}


# ── 运行时资源配置接口 ────────────────────────────────────

@app.get("/api/config/runtime")
async def get_runtime_config():
    """读取 Agent 当前资源配置与实时用量快照。"""
    def _sem_available(sem):
        return getattr(sem, '_value', None)

    # 实时用量
    l2_entries = 0
    if agent and agent._semantic_cache and agent._semantic_cache._collection:
        try:
            l2_entries = agent._semantic_cache._collection.count()
        except Exception:
            pass

    usage = {
        "active_inferences":  int(inference_active._value.get()) if hasattr(inference_active, '_value') else 0,
        "concurrency_slots":  _sem_available(_inference_sem) if _inference_sem else settings.inference_concurrency,
        "queue_slots":        _sem_available(_queue_sem)     if _queue_sem     else settings.inference_queue_size,
        "l1_cache_entries":   len(agent._response_cache)     if agent else 0,
        "l2_cache_entries":   l2_entries,
        "short_term_entries": agent.memory.short_term.count() if agent else 0,
        "long_term_entries":  agent.memory.long_term.count()  if agent else 0,
    }

    cfg = {
        "inference_concurrency":        settings.inference_concurrency,
        "inference_queue_size":         settings.inference_queue_size,
        "response_cache_max_size":      getattr(agent, '_cache_max_size',  settings.response_cache_max_size)    if agent else settings.response_cache_max_size,
        "response_cache_ttl_secs":      getattr(agent, '_cache_ttl_secs',  settings.response_cache_ttl_secs)    if agent else settings.response_cache_ttl_secs,
        "semantic_cache_threshold":     getattr(agent._semantic_cache, 'threshold',   settings.semantic_cache_threshold)   if (agent and agent._semantic_cache) else settings.semantic_cache_threshold,
        "semantic_cache_max_entries":   getattr(agent._semantic_cache, 'max_entries', settings.semantic_cache_max_entries) if (agent and agent._semantic_cache) else settings.semantic_cache_max_entries,
        "short_term_max_size":          agent.memory.short_term.max_size   if agent else settings.short_term_max_size,
        "short_term_ttl_hours":         agent.memory.short_term.ttl_hours  if agent else settings.short_term_ttl_hours,
        "scheduler_max_concurrent":     getattr(settings, 'scheduler_max_concurrent_tasks', 5),
        "chat_timeout":                 settings.chat_timeout,
        "tool_result_max_chars":        settings.tool_result_max_chars,
        "ollama_max_tokens":            settings.ollama_max_tokens,
        "ollama_temperature":           settings.ollama_temperature,
        "ollama_num_ctx":               settings.ollama_num_ctx,   # 上下文窗口 tokens
    }
    return {"config": cfg, "usage": usage}



# 各参数合法范围
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
    "ollama_num_ctx":             (512, 131072, int),  # 512 ~ 128K tokens
}


@app.patch("/api/config/runtime")
async def patch_runtime_config(body: dict):
    """动态更新 Agent 资源限制，绝大多数参数立即生效，无需重启。"""
    global _inference_sem, _queue_sem
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
                _inference_sem = asyncio.Semaphore(v)   # 替换全局信号量立即生效
            elif key == "inference_queue_size":
                settings.inference_queue_size = v
                _queue_sem = asyncio.Semaphore(v)
            elif key == "response_cache_max_size":
                settings.response_cache_max_size = v
                if agent: agent._cache_max_size = v
            elif key == "response_cache_ttl_secs":
                settings.response_cache_ttl_secs = v
                if agent: agent._cache_ttl_secs = v
            elif key == "semantic_cache_threshold":
                settings.semantic_cache_threshold = v
                if agent and agent._semantic_cache: agent._semantic_cache.threshold = v
            elif key == "semantic_cache_max_entries":
                settings.semantic_cache_max_entries = v
                if agent and agent._semantic_cache: agent._semantic_cache.max_entries = v
            elif key == "short_term_max_size":
                settings.short_term_max_size = v
                if agent: agent.memory.short_term.max_size = v
            elif key == "short_term_ttl_hours":
                settings.short_term_ttl_hours = v
                if agent: agent.memory.short_term.ttl_hours = v
            elif key == "scheduler_max_concurrent":
                setattr(settings, 'scheduler_max_concurrent_tasks', v)
                if agent and agent.task_manager:
                    agent.task_manager.scheduler.max_concurrent_tasks = v
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
            # 持久化到 runtime_config.json（使用 settings 字段名）
            _settings_key = "scheduler_max_concurrent_tasks" if key == "scheduler_max_concurrent" else key
            _rt = _load_runtime_config()
            _rt[_settings_key] = v
            _save_runtime_config(_rt)
        except Exception as e:
            errors[key] = str(e)
            logger.warning(f"运行时配置更新失败 {key}: {e}")

    return {"success": len(errors) == 0, "updated": updated, "errors": errors}


# ── 环境变量配置接口 ──────────────────────────────────────
_ALLOWED_ENV_KEYS = {"GITHUB_TOKEN", "WEB_SEARCH_API_KEY", "OLLAMA_MODEL", "CLOUD_MODEL"}


@app.patch("/api/config/env")
async def update_env_config(body: dict):
    """更新 .env 文件中的指定 Key（仅允许白名单 Key）。"""
    updated = []
    for key, value in body.items():
        if key not in _ALLOWED_ENV_KEYS:
            continue
        _persist_model_to_env(key, str(value))
        updated.append(key)
    return {"success": True, "updated": updated}


@app.patch("/api/config/params")
async def update_inference_params(body: dict):
    """即时更新推理参数（temperature/max_tokens/top_p），覆盖 settings 并持久化到 .env。"""
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
        _rt = _load_runtime_config()
        _rt[f"ollama_{k}"] = v
        _save_runtime_config(_rt)
    logger.info(f"推理参数已更新")
    return {"success": True}


# ── 图片文件服务（ImageGenerationTool 生成后存储于此）─────────────────────
_IMAGE_DIR = Path(__file__).parent.parent / "data" / "images"

@app.get("/api/images/{filename}")
async def serve_generated_image(filename: str):
    """返回本地生成图片的二进制内容；路径已在 JWT 中间件中白名单放行。"""
    import re
    # 防路径穿越：只允许 字母数字下划线横线点 组成的文件名
    if not re.match(r'^[\w\-]+\.(png|jpg|jpeg|webp)$', filename, re.IGNORECASE):
        raise HTTPException(status_code=400, detail="非法文件名")
    img_path = _IMAGE_DIR / filename
    if not img_path.exists():
        raise HTTPException(status_code=404, detail="图片不存在")
    suffix = img_path.suffix.lower()
    media_type = {"png": "image/png", "jpg": "image/jpeg",
                  "jpeg": "image/jpeg", "webp": "image/webp"}.get(suffix.lstrip("."), "image/png")
    return Response(content=img_path.read_bytes(), media_type=media_type)


if __name__ == "__main__":
    uvicorn.run(
        "api.fastapi_app:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.debug,
        log_level=settings.log_level.lower()
    )
