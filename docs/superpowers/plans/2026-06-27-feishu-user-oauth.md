# Feishu 个人日历/任务 OAuth 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现飞书 OAuth 2.0 授权流程，获取 user_access_token，使 agent 能读写用户个人日历事件和飞书任务（读写全权限）。

**Architecture:** Python FastAPI 新增 OAuth Token Manager（`feishu_oauth.py`）和 3 个 API 端点（authorize/callback/status）；Java 新增 `FeishuOAuthController` 透传 callback（无 JWT）和其余两个端点（有 JWT）；现有读工具升级为优先 user_access_token、fallback tenant_access_token；新增两个写工具（日历创建、任务创建/完成）。

**Tech Stack:** Python `cryptography.fernet`（加密存储 token）、`threading.Lock`（per-open_id 并发刷新锁，全同步，避免 async/sync 边界问题）、`responses` 库（测试 mock）；Java Spring Boot（`AbstractProxyController`）。

## Global Constraints

- Python 测试：`cd agent && pytest tests/ -v`（全量），单测 `pytest tests/test_feishu_oauth.py -v`
- 测试 mock 模式：同 `test_feishu_readonly_tools.py`，用 `@responses.activate` + `unittest.mock.patch`
- 工具注册：与现有 `FeishuIMTool` / `FeishuCalendarTool` / `FeishuTaskTool` 在同一 `if settings.feishu_app_id:` 块
- 所有新增 Python 文件行长不超过 88 字符（black）
- Java 新 Controller 放在 `backend/web/src/main/java/com/intelligent/agent/web/feishu/` 包内
- Java 1.8 语法：不用 `Map.of()`，用 `new HashMap<>()`
- Fernet 加密：密钥来自 `settings.feishu_oauth_encryption_key`（`FEISHU_OAUTH_ENCRYPTION_KEY` 环境变量）
- state 映射：内存 dict `_pending_states`，TTL 5 分钟；不持久化（重启则需重新授权，正常运行期授权一次即可）
- 飞书 token exchange 端点：`POST https://open.feishu.cn/open-apis/authen/v1/oidc/access_token`
  - Headers: `Authorization: Basic {base64(app_id:app_secret)}`, `Content-Type: application/json`
  - Body: `{"grant_type": "authorization_code", "code": "xxx"}`
- 飞书 token refresh 端点：`POST https://open.feishu.cn/open-apis/authen/v1/oidc/refresh_access_token`
  - 同上 Headers
  - Body: `{"grant_type": "refresh_token", "refresh_token": "xxx"}`
- 响应体 data 结构：`{"access_token": "...", "refresh_token": "...", "expires_in": 7200, "refresh_token_expires_in": 2592000}`

---

## Task 1：依赖 + 配置 + 启动校验

**Files:**
- Modify: `agent/pyproject.toml`
- Modify: `agent/config/settings.py`
- Modify: `agent/api/fastapi_app.py`（lifespan 内加校验）
- Test: `agent/tests/test_feishu_oauth_settings.py`

**Interfaces:**
- Produces:
  - `settings.feishu_oauth_redirect_uri: str`
  - `settings.feishu_oauth_encryption_key: str`
  - lifespan 在 `feishu_enabled=true` 且任一字段为空时 `raise RuntimeError`

- [ ] **Step 1: 写失败测试**

```python
# agent/tests/test_feishu_oauth_settings.py
import os
import pytest

def test_settings_has_feishu_oauth_redirect_uri():
    from config.settings import Settings
    s = Settings(
        feishu_oauth_redirect_uri="https://example.com/feishu/oauth/callback",
        feishu_oauth_encryption_key="dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==",
    )
    assert s.feishu_oauth_redirect_uri == "https://example.com/feishu/oauth/callback"

def test_settings_feishu_oauth_defaults_empty():
    from config.settings import Settings
    s = Settings()
    assert s.feishu_oauth_redirect_uri == ""
    assert s.feishu_oauth_encryption_key == ""
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd agent && pytest tests/test_feishu_oauth_settings.py -v
# Expected: FAILED — AttributeError: 'Settings' object has no attribute 'feishu_oauth_redirect_uri'
```

- [ ] **Step 3: 添加 `cryptography` 依赖**

在 `agent/pyproject.toml` `dependencies` 列表中追加：
```toml
"cryptography>=41.0.0",
```

然后安装：
```bash
cd agent && pip install -e ".[dev]"
```

- [ ] **Step 4: 在 settings.py 追加两个字段**

在 `agent/config/settings.py` 的 `# 飞书IM` 相关配置区域末尾追加（在 `model_config = ...` 之前）：

```python
    # 飞书 OAuth 用户授权
    feishu_oauth_redirect_uri: str = ""      # 公网 callback URL（Cloudflare Tunnel 域名）
    feishu_oauth_encryption_key: str = ""    # Fernet 密钥，python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

- [ ] **Step 5: 在 fastapi_app.py lifespan 追加启动校验**

在 `lifespan` 函数的 `yield` 之前（JWT 密钥检查块之后），追加：

```python
    # 飞书 OAuth 配置校验
    if settings.feishu_enabled:
        if not settings.feishu_oauth_redirect_uri:
            raise RuntimeError(
                "FEISHU_ENABLED=true 时必须配置 FEISHU_OAUTH_REDIRECT_URI"
            )
        if not settings.feishu_oauth_encryption_key:
            raise RuntimeError(
                "FEISHU_ENABLED=true 时必须配置 FEISHU_OAUTH_ENCRYPTION_KEY"
            )
```

注意：`settings.feishu_enabled` 字段已存在于 `FeishuConfig`（`backend` 侧配置），Python settings 中需确认是否存在；若无则改为 `if os.environ.get("FEISHU_ENABLED", "").lower() == "true":`

- [ ] **Step 6: 跑测试确认通过**

```bash
cd agent && pytest tests/test_feishu_oauth_settings.py -v
# Expected: 2 passed
```

- [ ] **Step 7: 全量回归**

```bash
cd agent && pytest tests/ -v --tb=short -q
# Expected: all existing tests still pass
```

- [ ] **Step 8: Commit**

```bash
git add agent/pyproject.toml agent/config/settings.py agent/api/fastapi_app.py agent/tests/test_feishu_oauth_settings.py
git commit -m "feat(feishu-oauth): add settings fields + startup validation + cryptography dep"
```

---

## Task 2：OAuth Token Manager

**Files:**
- Create: `agent/services/feishu_oauth.py`
- Test: `agent/tests/test_feishu_oauth.py`

**Interfaces:**
- Produces (被 Task 3 路由和 Task 4/5 工具调用):
  - `get_auth_url(open_id: str) -> str` — 构造授权 URL，存入 `_pending_states`
  - `exchange_code(code: str, state: str) -> dict` — 验证 state，换 token，Fernet 加密，写 JSON
  - `get_valid_token(open_id: str) -> str` — **同步**，返回有效 access_token（threading.Lock 防并发刷新）
  - `get_oauth_status(open_id: str) -> dict` — 返回授权状态信息
  - `class OAuthNotAuthorizedError(Exception)` — 未授权时抛出
  - `class OAuthRefreshExpiredError(Exception)` — refresh_token 过期时抛出

- [ ] **Step 1: 写失败测试（全部）**

```python
# agent/tests/test_feishu_oauth.py
"""飞书 OAuth Token Manager 单元测试。"""
import asyncio
import base64
import json
import os
import time
from unittest.mock import MagicMock, patch, AsyncMock

import pytest
import responses as resp_lib


# ── 辅助：构造有效的 Fernet 密钥 ────────────────────────────────────────────
def _make_fernet_key() -> str:
    from cryptography.fernet import Fernet
    return Fernet.generate_key().decode()


TOKEN_EXCHANGE_URL = "https://open.feishu.cn/open-apis/authen/v1/oidc/access_token"
TOKEN_REFRESH_URL  = "https://open.feishu.cn/open-apis/authen/v1/oidc/refresh_access_token"

OPEN_ID = "ou_test_user_001"


def _patch_settings(redirect_uri="https://tunnel.example/feishu/oauth/callback",
                    key: str = None):
    key = key or _make_fernet_key()
    return patch(
        "services.feishu_oauth.settings",
        feishu_app_id="cli_test",
        feishu_app_secret="secret_test",
        feishu_oauth_redirect_uri=redirect_uri,
        feishu_oauth_encryption_key=key,
    ), key


# ── get_auth_url ─────────────────────────────────────────────────────────────

def test_get_auth_url_returns_feishu_authorize_url():
    ctx, _ = _patch_settings()
    with ctx:
        from services import feishu_oauth
        url = feishu_oauth.get_auth_url(OPEN_ID)
    assert "open.feishu.cn/open-apis/authen/v1/authorize" in url
    assert "cli_test" in url
    assert "calendar%3Acalendar" in url or "calendar:calendar" in url

def test_get_auth_url_stores_state_mapping():
    ctx, _ = _patch_settings()
    with ctx:
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        feishu_oauth._pending_states.clear()
        feishu_oauth.get_auth_url(OPEN_ID)
        assert len(feishu_oauth._pending_states) == 1
        entry = list(feishu_oauth._pending_states.values())[0]
        assert entry["open_id"] == OPEN_ID

def test_get_auth_url_state_is_not_open_id():
    ctx, _ = _patch_settings()
    with ctx:
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        feishu_oauth._pending_states.clear()
        url = feishu_oauth.get_auth_url(OPEN_ID)
        state = list(feishu_oauth._pending_states.keys())[0]
        assert state != OPEN_ID
        assert len(state) > 10


# ── exchange_code ─────────────────────────────────────────────────────────────

@resp_lib.activate
def test_exchange_code_saves_encrypted_tokens(tmp_path):
    ctx, fernet_key = _patch_settings()
    token_file = tmp_path / "feishu_oauth_tokens.json"
    now = time.time()
    resp_lib.add(resp_lib.POST, TOKEN_EXCHANGE_URL, json={
        "access_token":  "u-access-123",
        "refresh_token": "ur-refresh-456",
        "expires_in":    7200,
        "refresh_token_expires_in": 2592000,
    })
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(token_file)):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        feishu_oauth._pending_states["state-abc"] = {"open_id": OPEN_ID, "created_at": now}
        feishu_oauth.exchange_code("code-xyz", "state-abc")

    data = json.loads(token_file.read_text())
    assert OPEN_ID in data
    entry = data[OPEN_ID]
    # token 已加密，不是明文
    assert entry["access_token"] != "u-access-123"
    assert entry["refresh_token"] != "ur-refresh-456"
    # 时间戳存在
    assert entry["expire_at"] > now
    assert entry["refresh_expires_at"] > now + 2590000

def test_exchange_code_raises_on_invalid_state(tmp_path):
    ctx, _ = _patch_settings()
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(tmp_path / "t.json")):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        feishu_oauth._pending_states.clear()
        with pytest.raises(ValueError, match="state"):
            feishu_oauth.exchange_code("code-xyz", "bad-state")

def test_exchange_code_removes_state_after_use(tmp_path):
    ctx, _ = _patch_settings()
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(tmp_path / "t.json")):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        with resp_lib.RequestsMock() as rsps:
            rsps.add(rsps.POST, TOKEN_EXCHANGE_URL, json={
                "access_token": "u-a", "refresh_token": "ur-r",
                "expires_in": 7200, "refresh_token_expires_in": 2592000
            })
            feishu_oauth._pending_states["st-1"] = {"open_id": OPEN_ID, "created_at": time.time()}
            feishu_oauth.exchange_code("c", "st-1")
        assert "st-1" not in feishu_oauth._pending_states


# ── get_valid_token ───────────────────────────────────────────────────────────

def test_get_valid_token_returns_decrypted_token(tmp_path):
    fernet_key = _make_fernet_key()
    ctx, _ = _patch_settings(key=fernet_key)
    from cryptography.fernet import Fernet
    f = Fernet(fernet_key.encode())
    token_file = tmp_path / "feishu_oauth_tokens.json"
    token_file.write_text(json.dumps({
        OPEN_ID: {
            "access_token":       f.encrypt(b"u-valid-token").decode(),
            "refresh_token":      f.encrypt(b"ur-refresh").decode(),
            "expire_at":          time.time() + 3600,
            "refresh_expires_at": time.time() + 2500000,
        }
    }))
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(token_file)):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        result = feishu_oauth.get_valid_token(OPEN_ID)
    assert result == "u-valid-token"

def test_get_valid_token_raises_when_not_authorized(tmp_path):
    ctx, _ = _patch_settings()
    token_file = tmp_path / "feishu_oauth_tokens.json"
    token_file.write_text("{}")
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(token_file)):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        with pytest.raises(feishu_oauth.OAuthNotAuthorizedError):
            feishu_oauth.get_valid_token(OPEN_ID)

@resp_lib.activate
def test_get_valid_token_refreshes_when_near_expiry(tmp_path):
    fernet_key = _make_fernet_key()
    ctx, _ = _patch_settings(key=fernet_key)
    from cryptography.fernet import Fernet
    f = Fernet(fernet_key.encode())
    token_file = tmp_path / "feishu_oauth_tokens.json"
    token_file.write_text(json.dumps({
        OPEN_ID: {
            "access_token":       f.encrypt(b"u-old").decode(),
            "refresh_token":      f.encrypt(b"ur-old-refresh").decode(),
            "expire_at":          time.time() + 60,     # < 300s → 触发刷新
            "refresh_expires_at": time.time() + 2500000,
        }
    }))
    resp_lib.add(resp_lib.POST, TOKEN_REFRESH_URL, json={
        "access_token": "u-new", "refresh_token": "ur-new",
        "expires_in": 7200, "refresh_token_expires_in": 2592000,
    })
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(token_file)):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        result = feishu_oauth.get_valid_token(OPEN_ID)
    assert result == "u-new"

def test_get_valid_token_raises_when_refresh_expired(tmp_path):
    fernet_key = _make_fernet_key()
    ctx, _ = _patch_settings(key=fernet_key)
    from cryptography.fernet import Fernet
    f = Fernet(fernet_key.encode())
    token_file = tmp_path / "feishu_oauth_tokens.json"
    token_file.write_text(json.dumps({
        OPEN_ID: {
            "access_token":       f.encrypt(b"u-old").decode(),
            "refresh_token":      f.encrypt(b"ur-old").decode(),
            "expire_at":          time.time() - 100,
            "refresh_expires_at": time.time() - 1,
        }
    }))
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(token_file)):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        with pytest.raises(feishu_oauth.OAuthRefreshExpiredError):
            feishu_oauth.get_valid_token(OPEN_ID)


# ── get_oauth_status ──────────────────────────────────────────────────────────

def test_get_oauth_status_not_authorized(tmp_path):
    ctx, _ = _patch_settings()
    token_file = tmp_path / "feishu_oauth_tokens.json"
    token_file.write_text("{}")
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(token_file)):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        status = feishu_oauth.get_oauth_status(OPEN_ID)
    assert status["authorized"] is False
    assert status["expire_at"] is None

def test_get_oauth_status_authorized(tmp_path):
    fernet_key = _make_fernet_key()
    ctx, _ = _patch_settings(key=fernet_key)
    from cryptography.fernet import Fernet
    f = Fernet(fernet_key.encode())
    future = time.time() + 3600
    refresh_future = time.time() + 2500000
    token_file = tmp_path / "feishu_oauth_tokens.json"
    token_file.write_text(json.dumps({
        OPEN_ID: {
            "access_token":       f.encrypt(b"u-tok").decode(),
            "refresh_token":      f.encrypt(b"ur-ref").decode(),
            "expire_at":          future,
            "refresh_expires_at": refresh_future,
        }
    }))
    with ctx, patch("services.feishu_oauth._TOKEN_FILE", str(token_file)):
        import importlib
        from services import feishu_oauth
        importlib.reload(feishu_oauth)
        status = feishu_oauth.get_oauth_status(OPEN_ID)
    assert status["authorized"] is True
    assert abs(status["expire_at"] - future) < 1
    assert status["refresh_token_expired"] is False
```

- [ ] **Step 2: 跑测试确认全部失败**

```bash
cd agent && pytest tests/test_feishu_oauth.py -v
# Expected: all FAILED — ImportError: cannot import name 'get_auth_url' from 'services.feishu_oauth'
```

- [ ] **Step 3: 实现 `agent/services/feishu_oauth.py`**

```python
"""飞书 OAuth 2.0 Token Manager。

负责：
- 构造用户授权 URL（附随机 state 防 CSRF）
- 用 code 换 user_access_token + refresh_token（Fernet 加密后持久化）
- 自动刷新（提前 5 分钟，per-open_id asyncio.Lock 防并发重复刷新）
- JSON 多用户结构：{open_id: {access_token, refresh_token, expire_at, refresh_expires_at}}
"""
import base64
import json
import os
import threading
import time
from collections import defaultdict
from pathlib import Path
from typing import Optional
from urllib.parse import urlencode
from uuid import uuid4

import requests
from cryptography.fernet import Fernet
from loguru import logger

from config.settings import settings

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

# Token 文件路径（可被测试 patch）
_TOKEN_FILE = str(Path(__file__).parent.parent / "data" / "feishu_oauth_tokens.json")

# ── 内存状态 ──────────────────────────────────────────────────────────────────

# {state_uuid: {"open_id": str, "created_at": float}}
_pending_states: dict = {}

# per-open_id threading.Lock，防止并发重复刷新（全同步，避免 async/sync 边界问题）
_refresh_locks: defaultdict = defaultdict(threading.Lock)


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
    # 两种响应格式兼容
    if "data" in data:
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
    if "data" in data:
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd agent && pytest tests/test_feishu_oauth.py -v
# Expected: all passed
```

- [ ] **Step 5: 全量回归**

```bash
cd agent && pytest tests/ -v --tb=short -q
```

- [ ] **Step 6: Commit**

```bash
git add agent/services/feishu_oauth.py agent/tests/test_feishu_oauth.py
git commit -m "feat(feishu-oauth): OAuth token manager with Fernet encryption and auto-refresh"
```

---

## Task 3：OAuth API Router + 挂载

**Files:**
- Create: `agent/api/feishu_oauth_router.py`
- Modify: `agent/api/fastapi_app.py`（追加 include_router）
- Test: `agent/tests/test_feishu_oauth_router.py`

**Interfaces:**
- Consumes: `get_auth_url`, `exchange_code`, `get_oauth_status`, `OAuthNotAuthorizedError`, `OAuthRefreshExpiredError` from `services.feishu_oauth`
- Produces:
  - `GET /api/feishu/oauth/authorize?open_id=xxx` → `{"auth_url": "..."}`
  - `GET /api/feishu/oauth/callback?code=xxx&state=xxx` → HTML
  - `GET /api/feishu/oauth/callback?error=access_denied&state=xxx` → HTML error
  - `GET /api/feishu/oauth/status?open_id=xxx` → `{"authorized": bool, ...}`

- [ ] **Step 1: 写失败测试**

```python
# agent/tests/test_feishu_oauth_router.py
"""飞书 OAuth 路由端点测试。"""
import json
import time
from unittest.mock import patch, MagicMock

import pytest
from fastapi.testclient import TestClient


def _make_app():
    from fastapi import FastAPI
    from api.feishu_oauth_router import router
    app = FastAPI()
    app.include_router(router)
    return app


def test_authorize_returns_auth_url():
    with patch("api.feishu_oauth_router.get_auth_url", return_value="https://open.feishu.cn/auth?app_id=x"):
        client = TestClient(_make_app())
        resp = client.get("/api/feishu/oauth/authorize?open_id=ou_test")
    assert resp.status_code == 200
    assert "auth_url" in resp.json()
    assert "open.feishu.cn" in resp.json()["auth_url"]


def test_callback_success_returns_html():
    with patch("api.feishu_oauth_router.exchange_code", return_value={"open_id": "ou_test"}):
        client = TestClient(_make_app())
        resp = client.get("/api/feishu/oauth/callback?code=c123&state=s456")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    assert "授权成功" in resp.text


def test_callback_user_denied_returns_html_error():
    client = TestClient(_make_app())
    resp = client.get("/api/feishu/oauth/callback?error=access_denied&state=s456")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    assert "拒绝" in resp.text


def test_callback_invalid_state_returns_400():
    with patch("api.feishu_oauth_router.exchange_code", side_effect=ValueError("state 无效")):
        client = TestClient(_make_app(), raise_server_exceptions=False)
        resp = client.get("/api/feishu/oauth/callback?code=c&state=bad")
    assert resp.status_code == 400


def test_callback_exchange_failure_returns_400():
    with patch("api.feishu_oauth_router.exchange_code", side_effect=RuntimeError("invalid_code")):
        client = TestClient(_make_app(), raise_server_exceptions=False)
        resp = client.get("/api/feishu/oauth/callback?code=bad&state=s")
    assert resp.status_code == 400


def test_status_returns_authorized_info():
    fake_status = {
        "authorized": True,
        "expire_at": time.time() + 3600,
        "refresh_expires_at": time.time() + 2500000,
        "refresh_token_expired": False,
    }
    with patch("api.feishu_oauth_router.get_oauth_status", return_value=fake_status):
        client = TestClient(_make_app())
        resp = client.get("/api/feishu/oauth/status?open_id=ou_test")
    assert resp.status_code == 200
    assert resp.json()["authorized"] is True


def test_status_not_authorized():
    fake_status = {"authorized": False, "expire_at": None, "refresh_expires_at": None, "refresh_token_expired": False}
    with patch("api.feishu_oauth_router.get_oauth_status", return_value=fake_status):
        client = TestClient(_make_app())
        resp = client.get("/api/feishu/oauth/status?open_id=ou_test")
    assert resp.status_code == 200
    assert resp.json()["authorized"] is False
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd agent && pytest tests/test_feishu_oauth_router.py -v
# Expected: FAILED — ModuleNotFoundError: No module named 'api.feishu_oauth_router'
```

- [ ] **Step 3: 实现路由**

```python
# agent/api/feishu_oauth_router.py
"""飞书 OAuth 端点：authorize / callback / status。"""
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import HTMLResponse, JSONResponse

from services.feishu_oauth import (
    OAuthNotAuthorizedError,
    OAuthRefreshExpiredError,
    exchange_code,
    get_auth_url,
    get_oauth_status,
)

router = APIRouter(prefix="/api/feishu/oauth", tags=["feishu-oauth"])

_HTML_SUCCESS = """<!DOCTYPE html><html><head><meta charset="utf-8">
<title>授权成功</title></head><body>
<h2>✅ 飞书授权成功</h2>
<p>你已授权 agent 访问个人日历和任务，可以关闭此页面。</p>
</body></html>"""

_HTML_DENIED = """<!DOCTYPE html><html><head><meta charset="utf-8">
<title>授权被拒绝</title></head><body>
<h2>❌ 授权被拒绝</h2>
<p>你拒绝了飞书授权。如需重新授权，请向 agent 发送"给我飞书日历授权链接"。</p>
</body></html>"""


@router.get("/authorize")
async def authorize(open_id: str = Query(..., description="用户 open_id")):
    """返回飞书 OAuth 授权链接。"""
    url = get_auth_url(open_id)
    return JSONResponse({"auth_url": url})


@router.get("/callback")
async def callback(
    code: str = Query(None),
    state: str = Query(None),
    error: str = Query(None),
):
    """接收飞书 OAuth 回调，完成 code 换 token。"""
    if error:
        return HTMLResponse(_HTML_DENIED)

    if not code or not state:
        raise HTTPException(status_code=400, detail="缺少 code 或 state 参数")

    try:
        exchange_code(code, state)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=400, detail=f"飞书 token 换取失败: {e}")

    return HTMLResponse(_HTML_SUCCESS)


@router.get("/status")
async def status(open_id: str = Query(..., description="用户 open_id")):
    """查询用户 OAuth 授权状态。"""
    return JSONResponse(get_oauth_status(open_id))
```

- [ ] **Step 4: 在 `fastapi_app.py` 挂载路由**

在 `agent/api/fastapi_app.py` 中，`image_router` 之后追加：

```python
from api.feishu_oauth_router import router as feishu_oauth_router
app.include_router(feishu_oauth_router)
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd agent && pytest tests/test_feishu_oauth_router.py -v
# Expected: 7 passed
```

- [ ] **Step 6: 全量回归**

```bash
cd agent && pytest tests/ -v --tb=short -q
```

- [ ] **Step 7: Commit**

```bash
git add agent/api/feishu_oauth_router.py agent/api/fastapi_app.py agent/tests/test_feishu_oauth_router.py
git commit -m "feat(feishu-oauth): add OAuth API router (authorize/callback/status)"
```

---

## Task 4：升级现有读工具（calendar_list / task_list）

**Files:**
- Modify: `agent/tools/builtin_tools/feishu_calendar.py`
- Modify: `agent/tools/builtin_tools/feishu_task.py`
- Test: `agent/tests/test_feishu_readonly_tools.py`（追加新测试用例）

**Interfaces:**
- Consumes: `get_valid_token`, `OAuthNotAuthorizedError`, `OAuthRefreshExpiredError` from `services.feishu_oauth`
- Produces:
  - `feishu_calendar_list(calendar_id, start_time, end_time, open_id="", page_size=50)` — open_id 有值时用 user token；否则用 tenant token
  - `feishu_task_list(open_id="", tasklist_guid="", page_size=50)` — 同上

- [ ] **Step 1: 在 `test_feishu_readonly_tools.py` 追加新测试**

在文件末尾追加：

```python
# ── 新增：open_id 存在时优先用 user_access_token ─────────────────────────────

@responses.activate
def test_calendar_list_uses_user_token_when_open_id_provided():
    """有 open_id + 已授权时，发出的请求带 user_access_token。"""
    events_url = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_u/events"
    responses.add(responses.GET, events_url, json={"code": 0, "data": {"items": []}})

    def fake_get_valid_token(open_id):   # 同步 mock
        return "u-user-access-token"

    with patch("tools.builtin_tools.feishu_calendar.get_valid_token", side_effect=fake_get_valid_token):
        from tools.builtin_tools.feishu_calendar import FeishuCalendarTool
        tool = FeishuCalendarTool()
        result = tool.execute(calendar_id="cal_u", start_time="1", end_time="2", open_id="ou_test")

    assert responses.calls[0].request.headers["Authorization"] == "Bearer u-user-access-token"
    assert result["code"] == 0


@responses.activate
def test_calendar_list_falls_back_to_tenant_when_not_authorized():
    """有 open_id 但未授权时，fallback 到 tenant_access_token。"""
    from services.feishu_oauth import OAuthNotAuthorizedError

    TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
    events_url = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_fb/events"
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok-tenant", "expire": 7200})
    responses.add(responses.GET, events_url, json={"code": 0, "data": {"items": []}})

    def raise_not_auth(open_id):   # 同步 mock
        raise OAuthNotAuthorizedError("未授权")

    with patch("tools.builtin_tools.feishu_calendar.get_valid_token", side_effect=raise_not_auth), \
         patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        from tools.builtin_tools.feishu_calendar import FeishuCalendarTool
        tool = FeishuCalendarTool()
        result = tool.execute(calendar_id="cal_fb", start_time="1", end_time="2", open_id="ou_test")

    assert responses.calls[-1].request.headers["Authorization"] == "Bearer tok-tenant"
```

- [ ] **Step 2: 跑新测试确认失败**

```bash
cd agent && pytest tests/test_feishu_readonly_tools.py::test_calendar_list_uses_user_token_when_open_id_provided -v
# Expected: FAILED — execute_async or open_id parameter not found
```

- [ ] **Step 3: 修改 `feishu_calendar.py`**

完整替换文件内容：

```python
"""飞书日历只读工具——查询指定日历在一段时间范围内的事件列表。

open_id 不为空时优先用 user_access_token（个人日历）；否则 fallback 到
tenant_access_token（应用自建/共享日历）。
"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import _get_tenant_access_token, FEISHU_BASE


class FeishuCalendarTool(BaseTool):
    """查询飞书日历事件列表（只读）。"""

    def __init__(self):
        super().__init__(name="feishu_calendar_list", category="im")
        self.parameters = [
            ToolParameter(
                name="calendar_id",
                type="string",
                description="日历 ID（应用自建/共享日历，或通过 feishu_calendar_list_cals 获取的个人日历 ID）",
                required=True,
            ),
            ToolParameter(
                name="start_time",
                type="string",
                description="起始时间，Unix 秒级时间戳字符串",
                required=True,
            ),
            ToolParameter(
                name="end_time",
                type="string",
                description="结束时间，Unix 秒级时间戳字符串",
                required=True,
            ),
            ToolParameter(
                name="open_id",
                type="string",
                description="查询个人日历时传入用户 open_id，使用 user_access_token；留空则使用应用身份",
                required=False,
                default="",
            ),
            ToolParameter(
                name="page_size",
                type="int",
                description="每页返回事件数量，默认 50",
                required=False,
                default=50,
            ),
        ]

    def execute(self, calendar_id: str, start_time: str, end_time: str,
                open_id: str = "", page_size: int = 50) -> Any:
        token = self._resolve_token(open_id)
        resp = requests.get(
            f"{FEISHU_BASE}/open-apis/calendar/v4/calendars/{calendar_id}/events",
            params={"start_time": start_time, "end_time": end_time, "page_size": page_size},
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书日历查询失败: {result}")
        return result

    def _resolve_token(self, open_id: str) -> str:
        if open_id:
            try:
                from services.feishu_oauth import get_valid_token
                return get_valid_token(open_id)
            except Exception as e:
                logger.warning(f"user_access_token 获取失败，fallback 到 tenant token: {e}")
        return _get_tenant_access_token()
```

- [ ] **Step 4: 修改 `feishu_task.py`**

完整替换文件内容：

```python
"""飞书任务只读工具——查询任务列表。

open_id 不为空时优先用 user_access_token（个人任务）；否则 fallback 到
tenant_access_token（应用可见任务）。
"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import _get_tenant_access_token, FEISHU_BASE


class FeishuTaskTool(BaseTool):
    """查询飞书任务列表（只读）。"""

    def __init__(self):
        super().__init__(name="feishu_task_list", category="im")
        self.parameters = [
            ToolParameter(
                name="open_id",
                type="string",
                description="查询个人任务时传入用户 open_id；留空则使用应用身份",
                required=False,
                default="",
            ),
            ToolParameter(
                name="tasklist_guid",
                type="string",
                description="任务清单 GUID；留空则查询所有可见任务",
                required=False,
                default="",
            ),
            ToolParameter(
                name="page_size",
                type="int",
                description="每页返回任务数量，默认 50",
                required=False,
                default=50,
            ),
        ]

    def execute(self, open_id: str = "", tasklist_guid: str = "", page_size: int = 50) -> Any:
        token = self._resolve_token(open_id)
        params: dict = {"page_size": page_size}
        if tasklist_guid:
            params["tasklist_guid"] = tasklist_guid
        resp = requests.get(
            f"{FEISHU_BASE}/open-apis/task/v2/tasks",
            params=params,
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书任务查询失败: {result}")
        return result

    def _resolve_token(self, open_id: str) -> str:
        if open_id:
            try:
                from services.feishu_oauth import get_valid_token
                return get_valid_token(open_id)
            except Exception as e:
                logger.warning(f"user_access_token 获取失败，fallback 到 tenant token: {e}")
        return _get_tenant_access_token()
```

- [ ] **Step 5: 跑全部 feishu readonly 测试**

```bash
cd agent && pytest tests/test_feishu_readonly_tools.py -v
# Expected: all passed（包含原有 5 个 + 新增 2 个）
```

- [ ] **Step 6: 全量回归**

```bash
cd agent && pytest tests/ -v --tb=short -q
```

- [ ] **Step 7: Commit**

```bash
git add agent/tools/builtin_tools/feishu_calendar.py agent/tools/builtin_tools/feishu_task.py agent/tests/test_feishu_readonly_tools.py
git commit -m "feat(feishu-oauth): upgrade read tools to prefer user_access_token with tenant fallback"
```

---

## Task 5：新增写工具 + 注册到 dispatcher

**Files:**
- Create: `agent/tools/builtin_tools/feishu_calendar_create.py`
- Create: `agent/tools/builtin_tools/feishu_task_write.py`
- Modify: `agent/core/tool_dispatcher.py`（注册两个新工具）
- Test: `agent/tests/test_feishu_write_tools.py`

**Interfaces:**
- Consumes: `get_valid_token` from `services.feishu_oauth`
- Produces:
  - `feishu_calendar_create(open_id, calendar_id, summary, start_time, end_time, description="")` → dict
  - `feishu_task_write(open_id, action, summary="", task_id="", due_time="")` → dict
    - `action="create"` → 创建任务（需 summary）
    - `action="complete"` → 完成任务（需 task_id）

- [ ] **Step 1: 写失败测试**

```python
# agent/tests/test_feishu_write_tools.py
"""飞书写工具测试：feishu_calendar_create / feishu_task_write。"""
import pytest
import responses as resp_lib
from unittest.mock import patch

OPEN_ID = "ou_test_user"
CAL_URL  = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_x/events"
TASK_CREATE_URL  = "https://open.feishu.cn/open-apis/task/v2/tasks"
TASK_COMPLETE_URL = "https://open.feishu.cn/open-apis/task/v2/tasks/task_001/complete"


def _mock_token(open_id):   # 同步 mock
    return "u-user-token"


# ── feishu_calendar_create ────────────────────────────────────────────────────

@resp_lib.activate
def test_calendar_create_posts_event():
    resp_lib.add(resp_lib.POST, CAL_URL, json={"code": 0, "data": {"event": {"event_id": "ev_1"}}})
    with patch("tools.builtin_tools.feishu_calendar_create.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_calendar_create import FeishuCalendarCreateTool
        tool = FeishuCalendarCreateTool()
        result = tool.execute(
            open_id=OPEN_ID,
            calendar_id="cal_x",
            summary="周会",
            start_time="2026-07-01T10:00:00+08:00",
            end_time="2026-07-01T11:00:00+08:00",
        )
    assert result["code"] == 0
    req_body = resp_lib.calls[0].request.body
    assert "周会" in req_body
    assert resp_lib.calls[0].request.headers["Authorization"] == "Bearer u-user-token"


def test_calendar_create_raises_without_user_token():
    """写工具不提供 tenant fallback，无授权时直接抛出。"""
    from services.feishu_oauth import OAuthNotAuthorizedError

    def raise_no_auth(open_id):
        raise OAuthNotAuthorizedError("未授权")

    with patch("tools.builtin_tools.feishu_calendar_create.get_valid_token", side_effect=raise_no_auth):
        from tools.builtin_tools.feishu_calendar_create import FeishuCalendarCreateTool
        tool = FeishuCalendarCreateTool()
        with pytest.raises(OAuthNotAuthorizedError):
            tool.execute(
                open_id=OPEN_ID, calendar_id="cal_x", summary="测试",
                start_time="2026-07-01T10:00:00+08:00",
                end_time="2026-07-01T11:00:00+08:00",
            )


# ── feishu_task_write ─────────────────────────────────────────────────────────

@resp_lib.activate
def test_task_write_creates_task():
    resp_lib.add(resp_lib.POST, TASK_CREATE_URL, json={"code": 0, "data": {"task": {"guid": "task_001"}}})
    with patch("tools.builtin_tools.feishu_task_write.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_task_write import FeishuTaskWriteTool
        tool = FeishuTaskWriteTool()
        result = tool.execute(open_id=OPEN_ID, action="create", summary="提交代码")
    assert result["code"] == 0
    assert "提交代码" in resp_lib.calls[0].request.body


@resp_lib.activate
def test_task_write_completes_task():
    resp_lib.add(resp_lib.POST, TASK_COMPLETE_URL, json={"code": 0})
    with patch("tools.builtin_tools.feishu_task_write.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_task_write import FeishuTaskWriteTool
        tool = FeishuTaskWriteTool()
        result = tool.execute(open_id=OPEN_ID, action="complete", task_id="task_001")
    assert result["code"] == 0


def test_task_write_raises_on_invalid_action():
    with patch("tools.builtin_tools.feishu_task_write.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_task_write import FeishuTaskWriteTool
        tool = FeishuTaskWriteTool()
        with pytest.raises(ValueError, match="action"):
            tool.execute(open_id=OPEN_ID, action="delete")
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd agent && pytest tests/test_feishu_write_tools.py -v
# Expected: FAILED — ModuleNotFoundError
```

- [ ] **Step 3: 创建 `feishu_calendar_create.py`**

```python
# agent/tools/builtin_tools/feishu_calendar_create.py
"""飞书日历事件创建/更新工具（user_access_token 必须）。"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import FEISHU_BASE


class FeishuCalendarCreateTool(BaseTool):
    """在指定飞书日历中创建事件（需要用户 OAuth 授权）。"""

    def __init__(self):
        super().__init__(name="feishu_calendar_create", category="im")
        self.parameters = [
            ToolParameter(name="open_id", type="string",
                          description="用户 open_id（必须已完成飞书 OAuth 授权）", required=True),
            ToolParameter(name="calendar_id", type="string",
                          description="目标日历 ID", required=True),
            ToolParameter(name="summary", type="string",
                          description="事件标题", required=True),
            ToolParameter(name="start_time", type="string",
                          description="开始时间，RFC3339 格式，如 2026-07-01T10:00:00+08:00", required=True),
            ToolParameter(name="end_time", type="string",
                          description="结束时间，RFC3339 格式", required=True),
            ToolParameter(name="description", type="string",
                          description="事件描述（可选）", required=False, default=""),
        ]

    def execute(self, open_id: str, calendar_id: str, summary: str,
                start_time: str, end_time: str, description: str = "") -> Any:
        from services.feishu_oauth import get_valid_token
        token = get_valid_token(open_id)   # 无授权时直接抛出，不 fallback
        payload = {
            "summary": summary,
            "description": description,
            "start_time": {"timestamp": start_time},
            "end_time":   {"timestamp": end_time},
        }
        resp = requests.post(
            f"{FEISHU_BASE}/open-apis/calendar/v4/calendars/{calendar_id}/events",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json=payload,
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书日历创建事件失败: {result}")
        return result
```

- [ ] **Step 4: 创建 `feishu_task_write.py`**

```python
# agent/tools/builtin_tools/feishu_task_write.py
"""飞书任务创建/完成工具（user_access_token 必须）。"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import FEISHU_BASE


class FeishuTaskWriteTool(BaseTool):
    """创建或完成飞书任务（需要用户 OAuth 授权）。"""

    def __init__(self):
        super().__init__(name="feishu_task_write", category="im")
        self.parameters = [
            ToolParameter(name="open_id", type="string",
                          description="用户 open_id（必须已完成飞书 OAuth 授权）", required=True),
            ToolParameter(name="action", type="string",
                          description="操作类型：create（创建任务）或 complete（完成任务）", required=True),
            ToolParameter(name="summary", type="string",
                          description="任务标题（action=create 时必填）", required=False, default=""),
            ToolParameter(name="task_id", type="string",
                          description="任务 GUID（action=complete 时必填）", required=False, default=""),
            ToolParameter(name="due_time", type="string",
                          description="截止时间，Unix 秒级时间戳（可选）", required=False, default=""),
        ]

    def execute(self, open_id: str, action: str, summary: str = "",
                task_id: str = "", due_time: str = "") -> Any:
        from services.feishu_oauth import get_valid_token
        if action not in ("create", "complete"):
            raise ValueError(f"不支持的 action={action!r}，只支持 create 或 complete")

        token = get_valid_token(open_id)
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

        if action == "create":
            payload: dict = {"summary": summary}
            if due_time:
                payload["due"] = {"timestamp": due_time}
            resp = requests.post(
                f"{FEISHU_BASE}/open-apis/task/v2/tasks",
                headers=headers, json=payload, timeout=15,
            )
        else:  # complete
            resp = requests.post(
                f"{FEISHU_BASE}/open-apis/task/v2/tasks/{task_id}/complete",
                headers=headers, json={}, timeout=15,
            )

        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书任务操作失败(action={action}): {result}")
        return result
```

- [ ] **Step 5: 在 `tool_dispatcher.py` 注册新工具**

在 `agent/core/tool_dispatcher.py` 的飞书工具注册块（约第 330-336 行）中，`logger.info(...)` 之前追加：

```python
                from tools.builtin_tools.feishu_calendar_create import FeishuCalendarCreateTool
                self.tool_manager.register_tool(FeishuCalendarCreateTool(), "im")
                from tools.builtin_tools.feishu_task_write import FeishuTaskWriteTool
                self.tool_manager.register_tool(FeishuTaskWriteTool(), "im")
```

并更新 `logger.info` 的工具列表字符串：

```python
                logger.info(
                    "飞书工具已注册（im_message / feishu_calendar_list / feishu_task_list"
                    " / feishu_calendar_create / feishu_task_write）"
                )
```

- [ ] **Step 6: 跑写工具测试**

```bash
cd agent && pytest tests/test_feishu_write_tools.py -v
# Expected: 5 passed
```

- [ ] **Step 7: 全量回归**

```bash
cd agent && pytest tests/ -v --tb=short -q
```

- [ ] **Step 8: Commit**

```bash
git add agent/tools/builtin_tools/feishu_calendar_create.py agent/tools/builtin_tools/feishu_task_write.py agent/core/tool_dispatcher.py agent/tests/test_feishu_write_tools.py
git commit -m "feat(feishu-oauth): add calendar_create and task_write tools, register in dispatcher"
```

---

## Task 6：Java FeishuOAuthController

**Files:**
- Modify: `backend/web/src/main/java/com/intelligent/agent/web/controller/AbstractProxyController.java`
- Create: `backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuOAuthController.java`
- Test: `backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuOAuthControllerTest.java`

**Interfaces:**
- Consumes: `proxy.get(path)` (无 userId，携带 service token) from PythonProxyService
- Produces:
  - `GET /feishu/oauth/callback` → 返回 Python 响应原样（HTML），无 JWT 校验
  - `GET /feishu/oauth/status?open_id=xxx` → JSON，有 JWT 校验
  - `GET /feishu/oauth/authorize?open_id=xxx` → JSON，有 JWT 校验

- [ ] **Step 1: 在 `AbstractProxyController` 追加 `proxyGetRaw` 方法**

在 `AbstractProxyController.java` 的 `// ── 公共工具 ──` 注释之前追加（DELETE 方法之后）：

```java
    /**
     * 代理 GET 请求，返回原始字符串（不解析为 JSON）。
     * 用于返回 HTML 等非 JSON 响应的场景（如 OAuth callback 成功页）。
     * 不附加 userId（用于无 JWT 的飞书回调等）。
     */
    protected ResponseEntity<String> proxyGetRaw(String path) {
        try {
            return proxy.get(path);
        } catch (Exception e) {
            log.error("GET raw {} 失败", path, e);
            return ResponseEntity.status(500).body("Internal proxy error");
        }
    }
```

- [ ] **Step 2: 创建 `FeishuOAuthController.java`**

```java
package com.intelligent.agent.web.feishu;

import com.intelligent.agent.web.controller.AbstractProxyController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 飞书 OAuth 代理 Controller。
 *
 * callback 端点无 JWT 校验（飞书服务器重定向，没有 JWT）；
 * authorize / status 端点有正常 JWT 校验（前端调用）。
 */
@Slf4j
@RestController
@RequestMapping("/feishu/oauth")
public class FeishuOAuthController extends AbstractProxyController {

    /**
     * 无 JWT：飞书服务器将用户浏览器重定向至此，不携带 JWT。
     * 原样透传 code+state 给 Python，Python 返回 HTML，直接透传给浏览器。
     */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oauthCallback(HttpServletRequest request) {
        String query = request.getQueryString();
        String path = "/api/feishu/oauth/callback" + (query != null ? "?" + query : "");
        log.info("飞书 OAuth 回调透传: {}", path);
        return proxyGetRaw(path);
    }

    /** 有 JWT：前端查询用户授权状态。 */
    @GetMapping("/status")
    public ResponseEntity<?> oauthStatus(
            @RequestParam("open_id") String openId,
            HttpServletRequest req) {
        return proxyGet("/api/feishu/oauth/status?open_id=" + openId, req);
    }

    /** 有 JWT：前端获取授权链接。 */
    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(
            @RequestParam("open_id") String openId,
            HttpServletRequest req) {
        return proxyGet("/api/feishu/oauth/authorize?open_id=" + openId, req);
    }
}
```

- [ ] **Step 3: 写 Java 测试**

```java
// backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuOAuthControllerTest.java
package com.intelligent.agent.web.feishu;

import com.intelligent.agent.web.service.PythonProxyService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@WebMvcTest(FeishuOAuthController.class)
public class FeishuOAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PythonProxyService proxy;

    @Test
    public void callbackProxiesToPythonWithoutJwt() throws Exception {
        Mockito.when(proxy.get(Mockito.contains("/api/feishu/oauth/callback")))
               .thenReturn(ResponseEntity.ok("<html>授权成功</html>"));

        mockMvc.perform(get("/feishu/oauth/callback?code=c123&state=s456"))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("授权成功")));
    }

    @Test
    public void callbackPassesErrorParamThrough() throws Exception {
        Mockito.when(proxy.get(Mockito.contains("error=access_denied")))
               .thenReturn(ResponseEntity.ok("<html>拒绝</html>"));

        mockMvc.perform(get("/feishu/oauth/callback?error=access_denied&state=s456"))
               .andExpect(status().isOk());
    }
}
```

- [ ] **Step 4: 编译 + 跑 Java 测试**

```bash
cd backend/web
./mvnw test -pl . -Dtest=FeishuOAuthControllerTest -q
# Expected: BUILD SUCCESS
```

- [ ] **Step 5: 全量 Java 测试**

```bash
cd backend/web && ./mvnw test -q
# Expected: BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add backend/web/src/main/java/com/intelligent/agent/web/controller/AbstractProxyController.java \
        backend/web/src/main/java/com/intelligent/agent/web/feishu/FeishuOAuthController.java \
        backend/web/src/test/java/com/intelligent/agent/web/feishu/FeishuOAuthControllerTest.java
git commit -m "feat(feishu-oauth): add Java FeishuOAuthController with proxyGetRaw for HTML callback"
```

---

## Task 7：文档 + TODOS.md

**Files:**
- Modify: `docs/feishu-integration.md`
- Modify: `TODOS.md`
- Modify: `AI_PROJECT_CONTEXT.md`（追加 TODO-85 工具列表）

- [ ] **Step 1: 在 `docs/feishu-integration.md` 追加「个人日历 OAuth 授权」小节**

在文件末尾追加：

```markdown
## 个人日历/任务 OAuth 授权（user_access_token）

应用 tenant_access_token 只能访问应用自建或共享的日历/任务。要读写**你的私人日历和飞书任务**，
需先完成一次 OAuth 授权，获取 user_access_token。

### 一次性配置

```env
# .env.docker 追加
FEISHU_OAUTH_REDIRECT_URI=https://{tunnel域名}/feishu/oauth/callback
FEISHU_OAUTH_ENCRYPTION_KEY=<Fernet.generate_key() 输出>
```

生成 Fernet 密钥：
```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

飞书开放平台后台：「安全设置」tab → 「重定向 URL」填入 tunnel callback 地址；
「权限管理」开通 `contact:user.id:readonly` / `calendar:calendar` / `calendar:calendar:write` /
`task:task` / `task:task:write`；「版本管理与发布」发布新版本。

### 授权流程（每次 refresh_token 过期后重做，约 30 天一次）

1. 启动 Cloudflare Tunnel：`docker compose --profile tunnel up -d`
2. 在聊天中说「给我飞书日历授权链接」，agent 返回授权 URL
3. 浏览器打开链接，点「允许」
4. 浏览器跳转到 callback 页显示「授权成功」即完成

之后 `feishu_calendar_list` / `feishu_task_list` 传入 `open_id` 参数即可访问个人数据；
`feishu_calendar_create` / `feishu_task_write` 则必须传入已授权的 `open_id`。
```

- [ ] **Step 2: 在 `TODOS.md` 追加 TODO-85 条目**

在文件末尾追加：

```markdown
## ~~TODO-85: 飞书个人日历/任务 OAuth 授权~~ ✅ 已完成（2026-06-27）

**结果**：
- `agent/services/feishu_oauth.py` — OAuth Token Manager（Fernet 加密 / asyncio.Lock 刷新 / state CSRF 防护）
- `agent/api/feishu_oauth_router.py` — 3 个端点（authorize / callback / status）
- `agent/tools/builtin_tools/feishu_calendar_create.py` — 创建日历事件（user_access_token）
- `agent/tools/builtin_tools/feishu_task_write.py` — 创建/完成任务（user_access_token）
- `feishu_calendar.py` / `feishu_task.py` — 升级为 user_access_token 优先，tenant fallback
- `backend/.../feishu/FeishuOAuthController.java` — callback 透传无 JWT，authorize/status 有 JWT
- Token 多用户 JSON（含 refresh_expires_at 30 天监控）+ Fernet 加密存储
```

- [ ] **Step 3: Commit**

```bash
git add docs/feishu-integration.md TODOS.md AI_PROJECT_CONTEXT.md
git commit -m "docs(feishu-oauth): update integration guide, TODOS.md, AI context for TODO-85"
```
