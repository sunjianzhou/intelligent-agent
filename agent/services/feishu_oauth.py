"""飞书 OAuth 2.0 Token Manager。

负责：
- 构造用户授权 URL（附随机 state 防 CSRF）
- 用 code 换 user_access_token + refresh_token（Fernet 加密后持久化）
- 自动刷新（提前 5 分钟，per-open_id threading.Lock 防并发重复刷新）
- JSON 多用户结构：{open_id: {access_token, refresh_token, expire_at, refresh_expires_at}}

实现说明
--------
``settings`` 和 ``_TOKEN_FILE`` 仅在**首次导入**时赋值（由 ``_module_initialized`` 守卫）。
这样测试可通过 ``importlib.reload()`` 重置 ``_pending_states`` / ``_refresh_locks`` 的同时，
保留外部已施加的 ``patch``（patch 在 reload 之前生效，reload 不会覆盖已 patch 的属性）。
"""
import base64
import json
import sys as _sys
import threading
import time
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlencode
from uuid import uuid4

import requests
from cryptography.fernet import Fernet
from loguru import logger

# ── 常量 ──────────────────────────────────────────────────────────────────────

FEISHU_BASE = "https://open.feishu.cn"
_AUTHORIZE_URL = f"{FEISHU_BASE}/open-apis/authen/v1/authorize"
_TOKEN_URL     = f"{FEISHU_BASE}/open-apis/authen/v1/oidc/access_token"
_REFRESH_URL   = f"{FEISHU_BASE}/open-apis/authen/v1/oidc/refresh_access_token"

_SCOPES = " ".join([
    "contact:user.id:readonly",
    "calendar:calendar",
    "calendar:calendar:write",
    "task:task",
    "task:task:write",
])

# state TTL（秒）：5 分钟
_STATE_TTL = 300

# 提前刷新阈值（秒）：到期前 5 分钟主动刷新
_REFRESH_AHEAD = 300

# ── 首次导入保护 ──────────────────────────────────────────────────────────────
# settings 和 _TOKEN_FILE 只在第一次导入时赋值。
# reload() 时跳过这段，使测试 patch 能在 reload 后继续生效。
_this_module = _sys.modules.get(__name__)
if not hasattr(_this_module, "_module_initialized"):
    from config.settings import settings  # noqa: E402
    _TOKEN_FILE: str = str(
        Path(__file__).parent.parent / "data" / "feishu_oauth_tokens.json"
    )
    _module_initialized = True  # 标记已完成首次初始化

# ── 内存状态（每次 reload 都会重置，意图如此）────────────────────────────────

# {state_uuid: {"open_id": str, "created_at": float}}
_pending_states: dict = {}

# per-open_id threading.Lock，防止并发重复刷新
_refresh_locks: defaultdict = defaultdict(threading.Lock)

# 文件级锁：保护 _save_token 的 read-modify-write，防止跨用户互相覆盖
_file_lock = threading.Lock()


# ── 自定义异常 ────────────────────────────────────────────────────────────────

class OAuthNotAuthorizedError(Exception):
    """用户尚未完成飞书 OAuth 授权。"""


class OAuthRefreshExpiredError(Exception):
    """refresh_token 已过期（30 天），需重新授权。"""


# ── 加解密 ────────────────────────────────────────────────────────────────────

def _get_fernet() -> Fernet:
    key = settings.feishu_oauth_encryption_key
    if not key:
        raise RuntimeError("FEISHU_OAUTH_ENCRYPTION_KEY 未配置")
    return Fernet(key.encode())


def _encrypt(text: str) -> str:
    return _get_fernet().encrypt(text.encode()).decode()


def _decrypt(ciphertext: str) -> str:
    return _get_fernet().decrypt(ciphertext.encode()).decode()


# ── Token 持久化 ───────────────────────────────────────────────────────────────

def _load_tokens() -> dict:
    try:
        with open(_TOKEN_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_token(open_id: str, data: dict) -> None:
    with _file_lock:
        tokens = _load_tokens()
        tokens[open_id] = data
        Path(_TOKEN_FILE).parent.mkdir(parents=True, exist_ok=True)
        with open(_TOKEN_FILE, "w", encoding="utf-8") as f:
            json.dump(tokens, f, ensure_ascii=False, indent=2)


# ── 内部：Auth Header ─────────────────────────────────────────────────────────

def _basic_auth_header() -> dict:
    cred = base64.b64encode(
        f"{settings.feishu_app_id}:{settings.feishu_app_secret}".encode()
    ).decode()
    return {"Authorization": f"Basic {cred}", "Content-Type": "application/json"}


# ── 内部：Token Exchange / Refresh ───────────────────────────────────────────

def _do_exchange(code: str) -> dict:
    resp = requests.post(
        _TOKEN_URL,
        headers=_basic_auth_header(),
        json={"grant_type": "authorization_code", "code": code},
        timeout=15,
    )
    resp.raise_for_status()
    data = resp.json()
    # 兼容两种响应格式：直接字段 vs. 嵌套在 data 里
    if "data" in data and isinstance(data["data"], dict):
        data = data["data"]
    if "access_token" not in data:
        raise RuntimeError(f"飞书 token exchange 失败: {data}")
    return data


def _do_refresh(refresh_token_plain: str) -> dict:
    resp = requests.post(
        _REFRESH_URL,
        headers=_basic_auth_header(),
        json={"grant_type": "refresh_token", "refresh_token": refresh_token_plain},
        timeout=15,
    )
    resp.raise_for_status()
    data = resp.json()
    if "data" in data and isinstance(data["data"], dict):
        data = data["data"]
    if "access_token" not in data:
        raise RuntimeError(f"飞书 token refresh 失败: {data}")
    return data


def _pack_token_entry(data: dict) -> dict:
    now = time.time()
    return {
        "access_token":       _encrypt(data["access_token"]),
        "refresh_token":      _encrypt(data["refresh_token"]),
        "expire_at":          now + int(data.get("expires_in", 7200)),
        "refresh_expires_at": now + int(data.get("refresh_token_expires_in", 2592000)),
    }


# ── 公共 API ──────────────────────────────────────────────────────────────────

def get_auth_url(open_id: str) -> str:
    """生成飞书 OAuth 授权 URL，并记录 state → open_id 映射（5 分钟有效）。"""
    # 清理超时的 pending state
    now = time.time()
    for k in list(_pending_states.keys()):
        if now - _pending_states[k]["created_at"] > _STATE_TTL:
            del _pending_states[k]

    state = str(uuid4())
    _pending_states[state] = {"open_id": open_id, "created_at": now}

    params = {
        "app_id":       settings.feishu_app_id,
        "redirect_uri": settings.feishu_oauth_redirect_uri,
        "scope":        _SCOPES,
        "state":        state,
    }
    return f"{_AUTHORIZE_URL}?{urlencode(params)}"


def exchange_code(code: str, state: str) -> dict:
    """验证 state，用 code 换 token，加密后写入 JSON，返回 open_id。"""
    now = time.time()
    pending = _pending_states.get(state)
    if not pending:
        raise ValueError(f"state 无效或已过期: {state}")
    if now - pending["created_at"] > _STATE_TTL:
        del _pending_states[state]
        raise ValueError("state 已过期，请重新发起授权")

    open_id = pending["open_id"]
    del _pending_states[state]

    raw = _do_exchange(code)
    entry = _pack_token_entry(raw)
    _save_token(open_id, entry)
    logger.info(f"飞书 OAuth 授权成功，open_id={open_id}")
    return {"open_id": open_id}


def get_valid_token(open_id: str) -> str:
    """返回有效的 user_access_token（过期则自动刷新）。

    同步调用，threading.Lock 防止并发重复刷新。

    Raises:
        OAuthNotAuthorizedError: 用户尚未授权
        OAuthRefreshExpiredError: refresh_token 过期（30天），需重新授权
    """
    with _refresh_locks[open_id]:
        tokens = _load_tokens()
        entry = tokens.get(open_id)
        if not entry:
            raise OAuthNotAuthorizedError(
                f"用户 {open_id} 尚未完成飞书 OAuth 授权，"
                "请调用 GET /api/feishu/oauth/authorize 获取授权链接"
            )

        now = time.time()
        if now > entry["refresh_expires_at"]:
            raise OAuthRefreshExpiredError(
                f"用户 {open_id} 的 refresh_token 已过期（30天），请重新授权"
            )

        if entry["expire_at"] - now < _REFRESH_AHEAD:
            logger.info(f"user_access_token 临近过期，刷新 open_id={open_id}")
            refresh_plain = _decrypt(entry["refresh_token"])
            raw = _do_refresh(refresh_plain)
            entry = _pack_token_entry(raw)
            _save_token(open_id, entry)

        return _decrypt(entry["access_token"])


def get_oauth_status(open_id: str) -> dict:
    """返回用户 OAuth 授权状态（不解密 token，仅检查元数据）。"""
    tokens = _load_tokens()
    entry = tokens.get(open_id)
    if not entry:
        return {
            "authorized": False,
            "expire_at": None,
            "refresh_expires_at": None,
            "refresh_token_expired": False,
        }
    now = time.time()
    return {
        "authorized": True,
        "expire_at": entry["expire_at"],
        "refresh_expires_at": entry["refresh_expires_at"],
        "refresh_token_expired": now > entry["refresh_expires_at"],
    }
