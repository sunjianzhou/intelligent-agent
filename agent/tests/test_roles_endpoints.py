"""Tests for roles REST endpoints — happy paths + 404/400 errors.

Uses a minimal FastAPI app that mounts only roles_router, with
RoleManager replaced by a mock, so no Ollama / ChromaDB is needed.
Compatible with Python 3.8+ (no pytest-asyncio required).
"""
import sys
import os
import asyncio
import pytest
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx
from fastapi import FastAPI

from personas.role_models import RoleConfig, RoleCard, CoreIdentity


# ── Helpers ───────────────────────────────────────────────────────────────────

def _make_role(role_id="r001", name="Tester") -> RoleConfig:
    return RoleConfig(
        role_id=role_id,
        role_card=RoleCard(name=name, signature="hi"),
        core_identity=CoreIdentity(personality=["friendly"]),
    )


def _role_payload(role_id="r001", name="Tester") -> dict:
    return {
        "role_id": role_id,
        "role_card": {"name": name, "avatar_url": "", "signature": "", "tags": []},
        "core_identity": {"personality": [], "principles": [], "redlines": [], "language_style": ""},
        "user_profile": {"nickname": "用户", "background": "", "preferences": {},
                         "relationship": "朋友", "disclosed_info": []},
        "role_memory": {"commitments": [], "retrieval_rule": {}, "short_term_size": 20},
        "knowledge_journal": {"recent_journal_entries": [],
                              "retrieval_strategy": "semantic_similarity",
                              "knowledge_sources": []},
    }


def _run(coro):
    """Run async coroutine in current test synchronously."""
    return asyncio.run(coro)


# ── App factory ───────────────────────────────────────────────────────────────

def _build_raw_app() -> FastAPI:
    """Build FastAPI app with roles_router included (no patching here)."""
    import api.roles_router as rr
    rr._user_active_roles.clear()

    from fastapi import Request
    a = FastAPI()

    @a.middleware("http")
    async def inject_user(request: Request, call_next):
        request.state.user_id = "test_user"
        return await call_next(request)

    from api.roles_router import router as roles_router
    a.include_router(roles_router)
    return a


@pytest.fixture
def mock_rm():
    role = _make_role()
    rm = MagicMock()
    rm.load_role.return_value = role
    rm.list_roles.return_value = ["r001"]
    rm.list_role_cards.return_value = [{"role_id": "r001", "role_card": {"name": "Tester"}}]
    rm.save_role.return_value = None
    rm.delete_role.return_value = True
    rm.add_commitment.return_value = role
    rm.add_memory.return_value = None
    return rm


@pytest.fixture
def app(mock_rm):
    import api.roles_router as rr
    # Keep patches active for the lifetime of the test
    with patch.object(rr, "get_role_manager", return_value=mock_rm), \
         patch.object(rr, "_save_user_active_roles"):
        yield _build_raw_app(), mock_rm


# ── GET /api/roles ─────────────────────────────────────────────────────────────

def test_list_roles(app):
    a, rm = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.get("/api/roles")
    resp = _run(_req())
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["count"] == 1
    assert body["roles"][0]["role_id"] == "r001"


# ── GET /api/roles/{role_id} ───────────────────────────────────────────────────

def test_get_role_found(app):
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.get("/api/roles/r001")
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["role"]["role_id"] == "r001"


def test_get_role_not_found(app):
    a, rm = app
    rm.load_role.return_value = None
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.get("/api/roles/ghost")
    resp = _run(_req())
    assert resp.status_code == 404


# ── GET /api/roles/{role_id}/card ─────────────────────────────────────────────

def test_get_role_card(app):
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.get("/api/roles/r001/card")
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["card"]["name"] == "Tester"


def test_get_role_card_not_found(app):
    a, rm = app
    rm.load_role.return_value = None
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.get("/api/roles/ghost/card")
    resp = _run(_req())
    assert resp.status_code == 404


# ── POST /api/roles ────────────────────────────────────────────────────────────

def test_create_role(app):
    a, rm = app
    rm.load_role.return_value = _make_role("r002", "NewBot")
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles", json=_role_payload("r002", "NewBot"))
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["role_id"] == "r002"
    rm.save_role.assert_called_once()


def test_create_role_invalid_payload(app):
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles", json={"bad": "data"})
    resp = _run(_req())
    assert resp.status_code == 400


# ── PUT /api/roles/{role_id} ───────────────────────────────────────────────────

def test_update_role(app):
    a, rm = app
    rm.load_role.return_value = _make_role("r001", "Updated")
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.put("/api/roles/r001", json=_role_payload("r001", "Updated"))
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["success"] is True
    rm.save_role.assert_called()


# ── PATCH /api/roles/{role_id} ────────────────────────────────────────────────

def test_patch_role(app):
    a, rm = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.patch("/api/roles/r001", json={"role_card": {"signature": "new sig"}})
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["success"] is True


def test_patch_role_not_found(app):
    a, rm = app
    rm.load_role.return_value = None
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.patch("/api/roles/ghost", json={"role_card": {"signature": "x"}})
    resp = _run(_req())
    assert resp.status_code == 404


# ── DELETE /api/roles/{role_id} ───────────────────────────────────────────────

def test_delete_role(app):
    a, rm = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.delete("/api/roles/r001")
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["success"] is True
    rm.delete_role.assert_called_once_with("r001")


def test_delete_role_not_found(app):
    a, rm = app
    rm.delete_role.return_value = False
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.delete("/api/roles/ghost")
    resp = _run(_req())
    assert resp.status_code == 404


# ── POST /api/roles/{role_id}/memory ──────────────────────────────────────────

def test_append_commitment_memory(app):
    a, rm = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles/r001/memory",
                                json={"content": "always be kind", "type": "commitment"})
    resp = _run(_req())
    assert resp.status_code == 200
    rm.add_commitment.assert_called_once_with("r001", "always be kind")


def test_append_long_term_memory(app):
    a, rm = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles/r001/memory",
                                json={"content": "user likes cats", "type": "long_term"})
    resp = _run(_req())
    assert resp.status_code == 200
    rm.add_memory.assert_called_once_with("r001", "user likes cats", memory_type="long_term")


def test_append_memory_empty_content(app):
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles/r001/memory",
                                json={"content": "  ", "type": "commitment"})
    resp = _run(_req())
    assert resp.status_code == 400


def test_append_memory_unsupported_type(app):
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles/r001/memory",
                                json={"content": "x", "type": "unknown"})
    resp = _run(_req())
    assert resp.status_code == 400


# ── GET /api/roles/activate ───────────────────────────────────────────────────

def test_get_active_role_none(app):
    import api.roles_router as rr
    rr._user_active_roles.clear()
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.get("/api/roles/activate")
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["role_id"] is None


# ── POST /api/roles/activate ──────────────────────────────────────────────────

def test_activate_role(app):
    a, rm = app
    rm.load_role.return_value = _make_role()
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles/activate", json={"role_id": "r001"})
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["role_id"] == "r001"


def test_activate_nonexistent_role(app):
    a, rm = app
    rm.load_role.return_value = None
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.post("/api/roles/activate", json={"role_id": "ghost"})
    resp = _run(_req())
    assert resp.status_code == 404


# ── DELETE /api/roles/activate ────────────────────────────────────────────────

def test_deactivate_role(app):
    import api.roles_router as rr
    rr._user_active_roles["test_user"] = "r001"
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.delete("/api/roles/activate")
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["deactivated"] == "r001"
    assert "test_user" not in rr._user_active_roles


def test_deactivate_when_none_active(app):
    import api.roles_router as rr
    rr._user_active_roles.clear()
    a, _ = app
    async def _req():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=a), base_url="http://test") as c:
            return await c.delete("/api/roles/activate")
    resp = _run(_req())
    assert resp.status_code == 200
    assert resp.json()["deactivated"] is None
