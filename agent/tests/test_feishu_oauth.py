"""飞书 OAuth Token Manager 单元测试。"""
import base64
import json
import os
import time
from unittest.mock import MagicMock, patch

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
