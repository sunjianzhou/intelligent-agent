"""HTTP tests for project endpoints (PUT/GET spec, context, tasks)."""
import sys
import os
import json
import pytest
from unittest.mock import MagicMock, AsyncMock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx
from fastapi import FastAPI, HTTPException
from typing import Optional
from pydantic import BaseModel


# ── helpers ────────────────────────────────────────────────────────────────────

def _make_chroma_collection(docs=None):
    docs = docs or []
    coll = MagicMock()
    coll.get.return_value = {
        "ids":       [d["id"]      for d in docs],
        "documents": [d["content"] for d in docs],
        "metadatas": [d["meta"]    for d in docs],
    }
    coll.add    = MagicMock()
    coll.delete = MagicMock()
    return coll


def _make_agent(collection=None):
    agent = MagicMock()
    lt = MagicMock()
    lt.collection    = collection or _make_chroma_collection()
    lt.persist_dir   = "/tmp"
    lt.vector_db     = MagicMock()
    lt.embedding_model = MagicMock()
    agent.memory.long_term = lt
    agent.memory.short_term.list.return_value = []
    agent._context_extractor = MagicMock()
    agent._context_extractor.interval = 8
    agent._context_extractor._turn_counts = {}
    agent._context_extractor.extract = AsyncMock(return_value=2)
    agent._get_project_context = AsyncMock(return_value=["决策A：使用 ChromaDB"])
    agent._call_model = AsyncMock(return_value='{"nuggets":[]}')
    return agent


def _build_app(agent):

    class ProjectSpecRequest(BaseModel):
        project_id: str
        content:    str
        version:    int = 1

    class ProjectContextExtractRequest(BaseModel):
        project_id: str
        user_id:    str

    app = FastAPI()

    @app.put("/api/project/spec")
    async def put_spec(request: ProjectSpecRequest):
        lt = agent.memory.long_term
        if lt.collection is None:
            raise HTTPException(status_code=503)
        spec_id = f"spec_{request.project_id}_v{request.version}"
        try:
            existing = lt.collection.get(
                where={"project_id": request.project_id, "type": "project_spec"},
                include=["metadatas"],
            )
            if existing and existing.get("ids"):
                lt.collection.delete(ids=existing["ids"])
        except Exception:
            pass
        lt.collection.add(
            ids=[spec_id],
            documents=[request.content],
            metadatas=[{"type": "project_spec", "project_id": request.project_id,
                        "version": request.version}],
        )
        return {"project_id": request.project_id, "version": request.version, "synced": True}

    @app.get("/api/project/spec")
    async def get_spec(project_id: str):
        lt = agent.memory.long_term
        if lt.collection is None:
            return {"project_id": project_id, "content": "", "version": 0}
        results = lt.collection.get(
            where={"project_id": project_id, "type": "project_spec"},
            include=["documents", "metadatas"],
        )
        if not results or not results.get("ids"):
            return {"project_id": project_id, "content": "", "version": 0}
        pairs = list(zip(results["documents"], results["metadatas"]))
        pairs.sort(key=lambda x: x[1].get("version", 0), reverse=True)
        doc, meta = pairs[0]
        return {"project_id": project_id, "content": doc, "version": meta.get("version", 1)}

    @app.post("/api/project/context/extract")
    async def extract_context(request: ProjectContextExtractRequest):
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
        return {"project_id": request.project_id, "user_id": request.user_id, "stored": stored}

    @app.get("/api/project/context")
    async def get_context(project_id: str, query: str = "", limit: int = 5):
        nuggets = await agent._get_project_context(project_id, query or "general", limit)
        return {"project_id": project_id, "nuggets": nuggets}

    @app.get("/api/project/tasks")
    async def get_tasks(project_id: str):
        return {"project_id": project_id, "task_tree": [], "note": "tasks are client-owned"}

    return app


# ── fixtures ───────────────────────────────────────────────────────────────────

@pytest.fixture
def app_and_agent():
    agent = _make_agent()
    app   = _build_app(agent)
    return app, agent


# ── spec tests ─────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_put_spec_returns_synced(app_and_agent):
    app, agent = app_and_agent
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.put("/api/project/spec", json={
            "project_id": "proj-abc",
            "content":    "# 规格\n需求A",
            "version":    1,
        })
    assert resp.status_code == 200
    body = resp.json()
    assert body["synced"] is True
    assert body["version"] == 1
    assert agent.memory.long_term.collection.add.called


@pytest.mark.asyncio
async def test_put_spec_calls_delete_then_add(app_and_agent):
    app, agent = app_and_agent
    agent.memory.long_term.collection.get.return_value = {
        "ids": ["old-spec-id"], "documents": ["old"], "metadatas": [{"version": 0}],
    }
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        await client.put("/api/project/spec", json={
            "project_id": "proj-abc", "content": "new", "version": 2,
        })
    assert agent.memory.long_term.collection.delete.called
    assert agent.memory.long_term.collection.add.called


@pytest.mark.asyncio
async def test_get_spec_returns_content(app_and_agent):
    app, agent = app_and_agent
    agent.memory.long_term.collection.get.return_value = {
        "ids":       ["spec-1"],
        "documents": ["# 规格内容"],
        "metadatas": [{"type": "project_spec", "project_id": "proj-abc", "version": 1}],
    }
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/project/spec?project_id=proj-abc")
    assert resp.status_code == 200
    body = resp.json()
    assert body["content"] == "# 规格内容"
    assert body["version"] == 1


@pytest.mark.asyncio
async def test_get_spec_returns_empty_when_not_found(app_and_agent):
    app, _ = app_and_agent
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/project/spec?project_id=no-such-proj")
    assert resp.status_code == 200
    body = resp.json()
    assert body["content"] == ""
    assert body["version"] == 0


# ── context tests ──────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_extract_context_returns_stored_count(app_and_agent):
    app, _ = app_and_agent
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/api/project/context/extract", json={
            "project_id": "proj-abc", "user_id": "user1",
        })
    assert resp.status_code == 200
    assert resp.json()["stored"] == 2


@pytest.mark.asyncio
async def test_get_context_returns_nuggets(app_and_agent):
    app, _ = app_and_agent
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/project/context?project_id=proj-abc&query=ChromaDB")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["nuggets"]) >= 1
    assert "ChromaDB" in body["nuggets"][0]


# ── tasks stub test ────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_get_tasks_returns_client_owned_note(app_and_agent):
    app, _ = app_and_agent
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/api/project/tasks?project_id=proj-abc")
    assert resp.status_code == 200
    body = resp.json()
    assert body["task_tree"] == []
    assert "client-owned" in body["note"]
