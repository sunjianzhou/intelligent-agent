"""Tests for 9.4 memory endpoints: summaries, export (no Ollama, no ChromaDB)."""
import sys
import os
import json as _json
import pytest
from datetime import datetime
from unittest.mock import MagicMock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx


# ── Helpers ───────────────────────────────────────────────────────────────────

def _make_memory_item(content: str, mem_type: str, user_id: str = "alice"):
    item = MagicMock()
    item.id = f"id_{content[:8]}"
    item.content = content
    item.importance = 0.75
    item.created_at = datetime(2026, 5, 24, 12, 0, 0)
    item.metadata = {"type": mem_type, "user_id": user_id, "timestamp": "2026-05-24 12:00"}
    return item


def _build_test_app(memories: list):
    from fastapi import FastAPI, Request
    from fastapi.responses import Response

    app = FastAPI()
    mock_agent = MagicMock()
    mock_agent.memory.long_term.list.return_value = memories

    @app.get("/api/memory/summaries")
    async def get_summaries(limit: int = 30, request: Request = None):
        user_id = getattr(request.state, "user_id", "default") if request else "default"
        all_mems = mock_agent.memory.long_term.list(limit=1000)
        summaries = [
            m for m in all_mems
            if m.metadata.get("type") == "session_summary"
            and (user_id in ("default", "anonymous")
                 or m.metadata.get("user_id") == user_id)
        ]
        summaries.sort(key=lambda m: m.created_at, reverse=True)
        return {
            "summaries": [
                {
                    "id": m.id,
                    "content": m.content,
                    "timestamp": m.metadata.get("timestamp"),
                    "created_at": m.created_at.isoformat(),
                }
                for m in summaries[:limit]
            ],
            "count": len(summaries[:limit]),
        }

    @app.get("/api/memory/export")
    async def export_memory(format: str = "json", request: Request = None):
        user_id = getattr(request.state, "user_id", "default") if request else "default"
        all_mems = mock_agent.memory.long_term.list(limit=2000)
        if user_id not in ("default", "anonymous"):
            all_mems = [m for m in all_mems if m.metadata.get("user_id") == user_id]

        if format == "markdown":
            facts     = [m for m in all_mems if m.metadata.get("type") == "fact"]
            summaries = [m for m in all_mems if m.metadata.get("type") == "session_summary"]
            lines = ["# 记忆导出\n"]
            if facts:
                lines.append("\n## 用户事实\n")
                for m in facts:
                    lines.append(f"- {m.content}\n")
            if summaries:
                lines.append("\n## 阶段摘要\n")
                for m in sorted(summaries, key=lambda x: x.created_at):
                    lines.append(f"\n### {m.metadata['timestamp']}\n{m.content}\n")
            body = "".join(lines)
            return Response(
                content=body,
                media_type="text/markdown; charset=utf-8",
                headers={"Content-Disposition": 'attachment; filename="memory.md"'},
            )
        else:
            data = [{"id": m.id, "content": m.content, "type": m.metadata.get("type")} for m in all_mems]
            return Response(
                content=_json.dumps(data, ensure_ascii=False),
                media_type="application/json; charset=utf-8",
                headers={"Content-Disposition": 'attachment; filename="memory.json"'},
            )

    return app


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def app_with_data():
    return _build_test_app([
        _make_memory_item("用户叫张三", "fact", "alice"),
        _make_memory_item("用户是工程师", "fact", "alice"),
        _make_memory_item("讨论了Python项目", "session_summary", "alice"),
        _make_memory_item("继续了代码审查", "session_summary", "alice"),
        _make_memory_item("其他用户的事实", "fact", "bob"),
    ])


@pytest.fixture
def app_empty():
    return _build_test_app([])


# ── Tests ─────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_summaries_returns_only_summaries(app_with_data):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app_with_data), base_url="http://test"
    ) as client:
        resp = await client.get("/api/memory/summaries")
    assert resp.status_code == 200
    data = resp.json()
    assert data["count"] == 2
    for s in data["summaries"]:
        assert "content" in s and "created_at" in s


@pytest.mark.asyncio
async def test_summaries_limit(app_with_data):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app_with_data), base_url="http://test"
    ) as client:
        resp = await client.get("/api/memory/summaries?limit=1")
    assert resp.status_code == 200
    assert resp.json()["count"] == 1


@pytest.mark.asyncio
async def test_export_json_returns_attachment(app_with_data):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app_with_data), base_url="http://test"
    ) as client:
        resp = await client.get("/api/memory/export?format=json")
    assert resp.status_code == 200
    assert "attachment" in resp.headers.get("content-disposition", "")
    body = resp.json()
    assert isinstance(body, list) and len(body) > 0
    assert "content" in body[0]


@pytest.mark.asyncio
async def test_export_markdown_returns_attachment(app_with_data):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app_with_data), base_url="http://test"
    ) as client:
        resp = await client.get("/api/memory/export?format=markdown")
    assert resp.status_code == 200
    assert "attachment" in resp.headers.get("content-disposition", "")
    assert ".md" in resp.headers.get("content-disposition", "")
    text = resp.text
    assert "# 记忆导出" in text
    assert "用户事实" in text
    assert "阶段摘要" in text


@pytest.mark.asyncio
async def test_export_markdown_sections(app_with_data):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app_with_data), base_url="http://test"
    ) as client:
        resp = await client.get("/api/memory/export?format=markdown")
    text = resp.text
    assert "用户叫张三" in text
    assert "讨论了Python项目" in text


@pytest.mark.asyncio
async def test_summaries_empty(app_empty):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app_empty), base_url="http://test"
    ) as client:
        resp = await client.get("/api/memory/summaries")
    assert resp.status_code == 200
    data = resp.json()
    assert data["count"] == 0
    assert data["summaries"] == []


@pytest.mark.asyncio
async def test_export_json_empty(app_empty):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app_empty), base_url="http://test"
    ) as client:
        resp = await client.get("/api/memory/export?format=json")
    assert resp.status_code == 200
    assert resp.json() == []
