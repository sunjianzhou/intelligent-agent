"""E2E: 认证 — 登录 / 登出 / 鉴权拦截。"""
import httpx
import pytest
from conftest import BASE_URL, USERNAME, PASSWORD


def test_login_success():
    r = httpx.post(
        f"{BASE_URL}/api/auth/login",
        json={"username": USERNAME, "password": PASSWORD},
        timeout=10,
    )
    assert r.status_code == 200
    data = r.json()
    assert data["success"] is True
    assert data["token"]
    assert data["username"] == USERNAME


def test_login_wrong_password():
    r = httpx.post(
        f"{BASE_URL}/api/auth/login",
        json={"username": USERNAME, "password": "wrong"},
        timeout=10,
    )
    assert r.status_code == 401
    data = r.json()
    assert data["success"] is False


def test_login_missing_user():
    r = httpx.post(
        f"{BASE_URL}/api/auth/login",
        json={"username": "nobody", "password": "x"},
        timeout=10,
    )
    assert r.status_code == 401


def test_protected_endpoint_without_token():
    # Java 代理层不做 JWT 拦截（由 Python 层负责鉴权）；
    # 直连 Python 不带 token → 应返回 401
    from conftest import PYTHON_URL
    r = httpx.get(f"{PYTHON_URL}/api/memory", timeout=10)
    assert r.status_code == 401


def test_logout(client):
    r = client.post("/api/auth/logout")
    assert r.status_code == 200
    assert r.json()["success"] is True
