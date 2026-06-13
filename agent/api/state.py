"""
共享运行时状态与公用工具函数。
所有可变全局变量在此定义；fastapi_app.py 在启动时赋值，
各 router 通过 `import api.state as _state` 读写。
"""
from __future__ import annotations

import asyncio
import json as _json
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

from fastapi import HTTPException, Request
from loguru import logger

from api.metrics import inference_active
from config.settings import settings

# ── 可变全局（由 fastapi_app 在模块加载时赋值）────────────────────────────────
agent = None
provider = None
OLLAMA_AVAILABLE: bool = False
CLOUD_MODE: bool = False
_fallback_ollama = None   # 云端模式时的 Ollama 降级备用

_user_providers: dict = {}

# ── 持久化文件路径 ────────────────────────────────────────────────────────────
_USER_PREFS_FILE    = Path(__file__).parent.parent / "data" / "user_model_prefs.json"
_RUNTIME_CONFIG_FILE = Path(__file__).parent.parent / "data" / "runtime_config.json"

# ── 并发控制信号量（由 lifespan 初始化）──────────────────────────────────────
_inference_sem: Optional[asyncio.Semaphore] = None
_queue_sem:     Optional[asyncio.Semaphore] = None


# ── 云端 URL 解析 ─────────────────────────────────────────────────────────────
def _resolve_cloud_base_url(provider_name: str, configured_url: str) -> str:
    """返回最终 base_url：配置值优先，其次查内置表，都没有则返回空。"""
    if configured_url:
        return configured_url
    from api.cloud_router import KNOWN_PROVIDER_URLS  # lazy import 防循环依赖
    return KNOWN_PROVIDER_URLS.get(provider_name.lower(), "")


# ── 用户模型偏好持久化 ────────────────────────────────────────────────────────
def _load_user_model_prefs() -> dict:
    try:
        if _USER_PREFS_FILE.exists():
            return _json.loads(_USER_PREFS_FILE.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"读取用户模型偏好文件失败: {e}")
    return {}


def _save_user_model_prefs(prefs: dict) -> None:
    try:
        _USER_PREFS_FILE.parent.mkdir(parents=True, exist_ok=True)
        _USER_PREFS_FILE.write_text(
            _json.dumps(prefs, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except Exception as e:
        logger.warning(f"保存用户模型偏好失败: {e}")


# ── 运行时配置持久化 ──────────────────────────────────────────────────────────
def _load_runtime_config() -> dict:
    try:
        if _RUNTIME_CONFIG_FILE.exists():
            return _json.loads(_RUNTIME_CONFIG_FILE.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"读取运行时配置失败: {e}")
    return {}


def _save_runtime_config(config: dict) -> None:
    try:
        _RUNTIME_CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
        _RUNTIME_CONFIG_FILE.write_text(
            _json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except Exception as e:
        logger.warning(f"保存运行时配置失败: {e}")


# ── Per-user provider ─────────────────────────────────────────────────────────
def _get_user_provider(user_id: str):
    """返回该用户专属 provider；若未设置则回退到全局 provider。"""
    return _user_providers.get(user_id) or provider


# ── 限流器 ────────────────────────────────────────────────────────────────────
class _TokenBucket:
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
            self.tokens = min(self.burst, self.tokens + (now - self.last_ts) * self.rate)
            self.last_ts = now
            if self.tokens >= 1:
                self.tokens -= 1
                return True
            return False


class _RateLimiter:
    def __init__(self, rate: float = 10.0, burst: int = 20, cleanup_interval: int = 300):
        self._rate = rate
        self._burst = burst
        self._buckets: dict = {}
        self._lock = threading.Lock()
        self._last_cleanup = time.monotonic()
        self._cleanup_interval = cleanup_interval

    def is_allowed(self, ip: str) -> bool:
        now = time.monotonic()
        with self._lock:
            if now - self._last_cleanup > self._cleanup_interval:
                cutoff = now - self._cleanup_interval
                self._buckets = {k: v for k, v in self._buckets.items() if v.last_ts > cutoff}
                self._last_cleanup = now
            if ip not in self._buckets:
                self._buckets[ip] = _TokenBucket(self._rate, self._burst)
        return self._buckets[ip].consume()


# IP 级（防穿透）10 req/s；用户级（公平分配）5 req/s
_rate_limiter      = _RateLimiter(rate=10.0, burst=20)
_user_rate_limiter = _RateLimiter(rate=5.0,  burst=10)


# ── 推理并发槽 ────────────────────────────────────────────────────────────────
@asynccontextmanager
async def _inference_slot(queue_timeout: float = 30.0):
    """先争抢队列名额（超时→503），再争抢推理信号量。"""
    _queue_acquired = False
    _sem_acquired = False
    if _queue_sem is not None:
        try:
            await asyncio.wait_for(_queue_sem.acquire(), timeout=queue_timeout)
            _queue_acquired = True
        except asyncio.TimeoutError:
            raise HTTPException(status_code=503, detail="服务繁忙，请稍后重试")
    try:
        if _inference_sem is not None:
            await _inference_sem.acquire()
            _sem_acquired = True
        inference_active.inc()
        yield
    finally:
        inference_active.dec()
        if _inference_sem is not None and _sem_acquired:
            _inference_sem.release()
        if _queue_sem is not None and _queue_acquired:
            _queue_sem.release()


# ── 管理员鉴权 ────────────────────────────────────────────────────────────────
def _require_admin(http_req: Request) -> None:
    """JWT 未启用时跳过；否则只允许 admin/java-service 调用。"""
    if not settings.jwt_enabled:
        return
    user_id = getattr(http_req.state, "user_id", "anonymous")
    if user_id not in ("admin", "java-service"):
        raise HTTPException(status_code=403, detail="仅管理员可调用此接口")
