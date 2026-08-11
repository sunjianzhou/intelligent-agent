"""
E2E 测试公共 fixtures（Java-only：Python Agent 已于 2026-08-08 退役）。
请求路径：pytest → httpx.Client → Java 后端 :8080

环境变量覆盖：
  E2E_BASE_URL      Java 后端地址（默认 http://localhost:8080）
  E2E_USERNAME      登录用户名（默认 admin）
  E2E_PASSWORD      登录密码（默认 admin123）
  E2E_CHAT_TIMEOUT  聊天推理超时秒数（默认 300）
"""
import os
import pytest
import httpx

BASE_URL     = os.getenv("E2E_BASE_URL",   "http://localhost:8080")
USERNAME     = os.getenv("E2E_USERNAME",   "admin")
PASSWORD     = os.getenv("E2E_PASSWORD",   "admin123")
CHAT_TIMEOUT = int(os.getenv("E2E_CHAT_TIMEOUT", "300"))


def _reachable(url: str, path: str = "/api/health") -> bool:
    try:
        r = httpx.get(f"{url}{path}", timeout=5)
        return r.status_code < 500
    except Exception:
        return False


# ── 可达性 ────────────────────────────────────────────────────────────────────

@pytest.fixture(scope="session")
def java_up():
    if not _reachable(BASE_URL, "/api/health"):
        pytest.skip(f"Java backend not reachable at {BASE_URL}")


# ── 认证 ──────────────────────────────────────────────────────────────────────

@pytest.fixture(scope="session")
def auth_token(java_up):
    r = httpx.post(
        f"{BASE_URL}/api/auth/login",
        json={"username": USERNAME, "password": PASSWORD},
        timeout=10,
    )
    r.raise_for_status()
    data = r.json()
    assert data.get("token"), "Login did not return a token"
    return data["token"]


# ── HTTP 客户端 ───────────────────────────────────────────────────────────────

@pytest.fixture(scope="session")
def client(auth_token):
    """通用 Java 后端客户端，超时 30s。"""
    with httpx.Client(
        base_url=BASE_URL,
        headers={"Authorization": f"Bearer {auth_token}"},
        timeout=30.0,
    ) as c:
        yield c


@pytest.fixture(scope="session")
def slow_client(auth_token):
    """Java 后端客户端，超时 CHAT_TIMEOUT（用于 LLM 推理类接口）。"""
    with httpx.Client(
        base_url=BASE_URL,
        headers={"Authorization": f"Bearer {auth_token}"},
        timeout=float(CHAT_TIMEOUT),
    ) as c:
        yield c
