"""记忆管理 API（/api/memory/*）。"""
from datetime import datetime

from fastapi import APIRouter, Request
from fastapi.responses import Response
from loguru import logger

import api.state as _state

router = APIRouter(prefix="/api/memory")


@router.get("")
async def get_memory():
    if _state.agent:
        return {"stats": _state.agent.get_memory_info()}
    return {"stats": {"long_term": {"count": 0}, "short_term": {"count": 0}}}


@router.delete("")
async def clear_memory():
    if _state.agent:
        _state.agent.clear_history()
        return {"message": "记忆已清空"}
    return {"message": "Agent 未初始化"}


@router.get("/list")
async def list_memories(memory_type: str = "long_term", limit: int = 50):
    if not _state.agent:
        return {"memories": [], "count": 0}
    items = (
        _state.agent.memory.short_term if memory_type == "short_term"
        else _state.agent.memory.long_term
    ).list(limit=limit)
    memories = [
        {
            "id": m.id,
            "content": m.content,
            "importance": round(m.importance, 2),
            "category": m.metadata.get("category") or m.metadata.get("type") or "unknown",
            "role": m.metadata.get("role", ""),
            "type": m.metadata.get("type", ""),
            "created_at": m.created_at.isoformat(),
            "access_count": m.access_count,
        }
        for m in items
    ]
    return {"memories": memories, "count": len(memories), "type": memory_type}


@router.delete("/{memory_id}")
async def delete_memory(memory_id: str):
    if not _state.agent:
        return {"success": False, "message": "Agent 未初始化"}
    ok = _state.agent.memory.long_term.delete(memory_id)
    return (
        {"success": True,  "message": f"已删除记忆 {memory_id}"}
        if ok else
        {"success": False, "message": f"记忆 {memory_id} 不存在"}
    )


@router.patch("/{memory_id}/importance")
async def update_memory_importance(memory_id: str, body: dict):
    if not _state.agent:
        return {"success": False, "message": "Agent 未初始化"}
    importance = max(0.0, min(1.0, float(body.get("importance", 0.5))))
    try:
        lt = _state.agent.memory.long_term
        if lt.collection is None:
            return {"success": False, "message": "ChromaDB 不可用"}
        existing = lt.collection.get(ids=[memory_id], include=["metadatas"])
        if existing and existing["ids"]:
            meta = (existing["metadatas"] or [{}])[0] or {}
            meta["importance"] = importance
            lt.collection.update(ids=[memory_id], metadatas=[meta])
        if memory_id in lt.memories:
            lt.memories[memory_id].importance = importance
        return {"success": True, "memory_id": memory_id, "importance": importance}
    except Exception as e:
        logger.warning(f"更新记忆重要性失败: {e}")
        return {"success": False, "message": str(e)}


@router.get("/search")
async def search_memory(q: str, limit: int = 10):
    if not _state.agent:
        return {"results": []}
    from memory.base import MemoryQuery
    results = _state.agent.memory.long_term.retrieve(
        MemoryQuery(text=q, limit=limit, threshold=0.2)
    )
    return {
        "results": [
            {
                "id": r.memory.id,
                "content": r.memory.content,
                "similarity": round(r.similarity, 3),
                "importance": round(r.memory.importance, 2),
                "category": r.memory.metadata.get("category") or r.memory.metadata.get("type") or "unknown",
                "created_at": r.memory.created_at.isoformat(),
            }
            for r in results
        ]
    }


@router.get("/summaries")
async def get_memory_summaries(request: Request, limit: int = 30):
    if not _state.agent:
        return {"summaries": [], "count": 0}
    user_id = getattr(request.state, "user_id", "default") if request else "default"
    lt = _state.agent.memory.long_term

    if lt.collection is not None:
        try:
            if user_id in ("default", "anonymous"):
                where_clause = {"type": {"$eq": "session_summary"}}
            else:
                where_clause = {
                    "$and": [
                        {"type":    {"$eq": "session_summary"}},
                        {"user_id": {"$eq": user_id}},
                    ]
                }
            results = lt.collection.get(
                where=where_clause, limit=limit,
                include=["documents", "metadatas"],
            )
            ids   = results.get("ids", []) or []
            docs  = results.get("documents", []) or []
            metas = results.get("metadatas", []) or []
            summaries = [
                {
                    "id":         ids[i],
                    "content":    docs[i] if i < len(docs) else "",
                    "timestamp":  (metas[i] or {}).get("timestamp", ""),
                    "created_at": (metas[i] or {}).get("timestamp", ""),
                }
                for i in range(len(ids))
            ]
            summaries.sort(key=lambda s: s["timestamp"], reverse=True)
            return {"summaries": summaries, "count": len(summaries)}
        except Exception as e:
            logger.warning(f"ChromaDB summaries 查询失败，回退全量扫描: {e}")

    all_mems = lt.list(limit=500)
    summaries = [
        m for m in all_mems
        if m.metadata.get("type") == "session_summary"
        and (user_id in ("default", "anonymous") or m.metadata.get("user_id") == user_id)
    ]
    summaries.sort(key=lambda m: m.created_at, reverse=True)
    return {
        "summaries": [
            {
                "id":         m.id,
                "content":    m.content,
                "timestamp":  m.metadata.get("timestamp", m.created_at.strftime("%Y-%m-%d %H:%M")),
                "created_at": m.created_at.isoformat(),
            }
            for m in summaries[:limit]
        ],
        "count": len(summaries[:limit]),
    }


@router.get("/export")
async def export_memory(request: Request, format: str = "json"):
    if not _state.agent:
        return Response(content="[]", media_type="application/json")
    user_id = getattr(request.state, "user_id", "default") if request else "default"
    all_mems = _state.agent.memory.long_term.list(limit=2000)
    if user_id not in ("default", "anonymous"):
        all_mems = [m for m in all_mems if m.metadata.get("user_id") == user_id]

    if format == "markdown":
        facts     = [m for m in all_mems if m.metadata.get("type") == "fact"]
        summaries = [m for m in all_mems if m.metadata.get("type") == "session_summary"]
        others    = [m for m in all_mems if m.metadata.get("type") not in ("fact", "session_summary")]
        lines = [f"# 记忆导出 — {user_id}\n\n生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M')}\n"]
        if facts:
            lines.append("\n## 用户事实\n")
            for m in facts:
                lines.append(f"- {m.content}\n")
        if summaries:
            lines.append("\n## 阶段摘要\n")
            for m in sorted(summaries, key=lambda x: x.created_at):
                ts = m.metadata.get("timestamp", m.created_at.strftime("%Y-%m-%d %H:%M"))
                lines.append(f"\n### {ts}\n{m.content}\n")
        if others:
            lines.append("\n## 其他记忆\n")
            for m in others:
                lines.append(f"- [{m.metadata.get('type', '?')}] {m.content}\n")
        filename = f"memory_{user_id}_{datetime.now().strftime('%Y%m%d')}.md"
        return Response(
            content="".join(lines),
            media_type="text/markdown; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )
    else:
        import json as _json
        data = [
            {
                "id": m.id,
                "content": m.content,
                "type": m.metadata.get("type", "unknown"),
                "importance": round(m.importance, 2),
                "created_at": m.created_at.isoformat(),
                "metadata": {k: v for k, v in m.metadata.items() if k != "user_id"},
            }
            for m in all_mems
        ]
        filename = f"memory_{user_id}_{datetime.now().strftime('%Y%m%d')}.json"
        return Response(
            content=_json.dumps(data, ensure_ascii=False, indent=2),
            media_type="application/json; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )


@router.post("/batch-import")
async def batch_import_memory(body: dict, http_req: Request):
    _state._require_admin(http_req)
    if not _state.agent:
        return {"success": False, "message": "Agent 未初始化"}
    items = body.get("items", [])
    if not items:
        return {"success": False, "message": "items 不能为空"}
    imported, errors = 0, []
    for idx, item in enumerate(items[:200]):
        try:
            content = str(item.get("content", "")).strip()
            if not content:
                continue
            importance = max(0.0, min(1.0, float(item.get("importance", 0.6))))
            category   = item.get("category", "knowledge")
            _state.agent.memory.long_term.store(
                content,
                metadata={"category": category, "type": "imported"},
                importance=importance,
            )
            imported += 1
        except Exception as e:
            errors.append(f"第{idx+1}条: {e}")
    return {"success": True, "imported": imported, "errors": errors}


@router.post("/distill")
async def trigger_distill(http_req: Request):
    _state._require_admin(http_req)
    if not _state.agent:
        return {"success": False, "message": "Agent 未初始化"}
    try:
        await _state.agent._cleanup_memories()
        return {"success": True, "message": "记忆整理完成"}
    except Exception as e:
        return {"success": False, "message": str(e)}
