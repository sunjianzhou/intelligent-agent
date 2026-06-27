"""飞书 OAuth 路由端点测试。"""
import time
from unittest.mock import patch

import httpx
import pytest


def _make_app():
    from fastapi import FastAPI
    from api.feishu_oauth_router import router
    app = FastAPI()
    app.include_router(router)
    return app


@pytest.mark.asyncio
async def test_authorize_returns_auth_url():
    with patch("api.feishu_oauth_router.get_auth_url", return_value="https://open.feishu.cn/auth?app_id=x"):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_make_app()), base_url="http://test"
        ) as client:
            resp = await client.get("/api/feishu/oauth/authorize?open_id=ou_test")
    assert resp.status_code == 200
    assert "auth_url" in resp.json()
    assert "open.feishu.cn" in resp.json()["auth_url"]


@pytest.mark.asyncio
async def test_callback_success_returns_html():
    with patch("api.feishu_oauth_router.exchange_code", return_value={"open_id": "ou_test"}):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_make_app()), base_url="http://test"
        ) as client:
            resp = await client.get("/api/feishu/oauth/callback?code=c123&state=s456")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    assert "授权成功" in resp.text


@pytest.mark.asyncio
async def test_callback_user_denied_returns_html_error():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=_make_app()), base_url="http://test"
    ) as client:
        resp = await client.get("/api/feishu/oauth/callback?error=access_denied&state=s456")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    assert "拒绝" in resp.text


@pytest.mark.asyncio
async def test_callback_invalid_state_returns_400():
    with patch("api.feishu_oauth_router.exchange_code", side_effect=ValueError("state 无效")):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_make_app()), base_url="http://test"
        ) as client:
            resp = await client.get("/api/feishu/oauth/callback?code=c&state=bad")
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_callback_exchange_failure_returns_400():
    with patch("api.feishu_oauth_router.exchange_code", side_effect=RuntimeError("invalid_code")):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_make_app()), base_url="http://test"
        ) as client:
            resp = await client.get("/api/feishu/oauth/callback?code=bad&state=s")
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_status_returns_authorized_info():
    fake_status = {
        "authorized": True,
        "expire_at": time.time() + 3600,
        "refresh_expires_at": time.time() + 2500000,
        "refresh_token_expired": False,
    }
    with patch("api.feishu_oauth_router.get_oauth_status", return_value=fake_status):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_make_app()), base_url="http://test"
        ) as client:
            resp = await client.get("/api/feishu/oauth/status?open_id=ou_test")
    assert resp.status_code == 200
    assert resp.json()["authorized"] is True


@pytest.mark.asyncio
async def test_status_not_authorized():
    fake_status = {
        "authorized": False,
        "expire_at": None,
        "refresh_expires_at": None,
        "refresh_token_expired": False,
    }
    with patch("api.feishu_oauth_router.get_oauth_status", return_value=fake_status):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_make_app()), base_url="http://test"
        ) as client:
            resp = await client.get("/api/feishu/oauth/status?open_id=ou_test")
    assert resp.status_code == 200
    assert resp.json()["authorized"] is False
