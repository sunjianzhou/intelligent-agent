"""Tests for persona management (9.2 — custom persona layer)."""
import sys
import os
import json
import pytest
import pytest_asyncio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def personas_dir(tmp_path):
    """Create a temporary personas directory with two test personas."""
    pd = tmp_path / "personas"
    pd.mkdir()
    (pd / "default.md").write_text("# Default\nYou are a helpful assistant.", encoding="utf-8")
    (pd / "creative.md").write_text("# Creative Writer\nYou are a creative storyteller.", encoding="utf-8")
    return pd


@pytest.fixture
def prefs_file(tmp_path):
    return tmp_path / "prefs.json"


# ── personas_router unit tests (no HTTP, no FastAPI needed) ───────────────────

def test_list_persona_names(personas_dir, monkeypatch):
    import api.personas_router as pr
    monkeypatch.setattr(pr, "_PERSONAS_DIR", personas_dir)
    names = pr._list_persona_names()
    assert "default" in names
    assert "creative" in names
    assert len(names) == 2


def test_read_persona_content(personas_dir, monkeypatch):
    import api.personas_router as pr
    monkeypatch.setattr(pr, "_PERSONAS_DIR", personas_dir)
    content = pr._read_persona_content("default")
    assert content is not None
    assert "helpful assistant" in content


def test_read_persona_missing(personas_dir, monkeypatch):
    import api.personas_router as pr
    monkeypatch.setattr(pr, "_PERSONAS_DIR", personas_dir)
    assert pr._read_persona_content("nonexistent") is None


def test_save_persona_prefs(prefs_file, monkeypatch):
    import api.personas_router as pr
    monkeypatch.setattr(pr, "_PERSONA_PREFS_FILE", prefs_file)
    pr._user_personas.clear()
    pr._user_personas["alice"] = "creative"
    pr._save_persona_prefs()
    assert prefs_file.exists()
    data = json.loads(prefs_file.read_text())
    assert data["alice"] == "creative"


def test_persona_title_extracted_from_heading(personas_dir, monkeypatch):
    import api.personas_router as pr
    monkeypatch.setattr(pr, "_PERSONAS_DIR", personas_dir)
    content = pr._read_persona_content("creative")
    first_line = content.splitlines()[0]
    title = first_line.lstrip("#").strip()
    assert title == "Creative Writer"


# ── HTTP endpoint tests via httpx.AsyncClient + ASGITransport ─────────────────

@pytest.fixture
def asgi_app(personas_dir, prefs_file, monkeypatch):
    import api.personas_router as pr
    monkeypatch.setattr(pr, "_PERSONAS_DIR", personas_dir)
    monkeypatch.setattr(pr, "_PERSONA_PREFS_FILE", prefs_file)
    pr._user_personas.clear()

    from fastapi import FastAPI
    app = FastAPI()
    app.include_router(pr.router)
    return app


@pytest.mark.asyncio
async def test_list_personas_endpoint(asgi_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=asgi_app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/personas")
    assert resp.status_code == 200
    data = resp.json()
    assert data["count"] == 2
    names = [p["name"] for p in data["personas"]]
    assert "default" in names and "creative" in names


@pytest.mark.asyncio
async def test_list_personas_title_from_heading(asgi_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=asgi_app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/personas")
    by_name = {p["name"]: p for p in resp.json()["personas"]}
    assert by_name["creative"]["title"] == "Creative Writer"


@pytest.mark.asyncio
async def test_get_current_persona_default(asgi_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=asgi_app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/personas/current")
    assert resp.status_code == 200
    assert resp.json()["persona"] == "default"


@pytest.mark.asyncio
async def test_switch_persona(asgi_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=asgi_app), base_url="http://test"
    ) as client:
        resp = await client.post("/api/personas/switch", json={"persona": "creative"})
    assert resp.status_code == 200
    assert resp.json()["success"] is True
    assert resp.json()["persona"] == "creative"


@pytest.mark.asyncio
async def test_switch_persona_not_found(asgi_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=asgi_app), base_url="http://test"
    ) as client:
        resp = await client.post("/api/personas/switch", json={"persona": "ghost"})
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_get_persona_content(asgi_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=asgi_app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/personas/default/content")
    assert resp.status_code == 200
    assert "helpful assistant" in resp.json()["content"]


@pytest.mark.asyncio
async def test_get_persona_content_missing(asgi_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=asgi_app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/personas/ghost/content")
    assert resp.status_code == 404
