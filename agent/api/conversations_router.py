"""
对话历史 API — 将每轮对话持久化到 JSON 文件，供前端展示历史记录。

存储路径：agent/data/conversations/{user_id}/{session_id}.json
文件格式：{ session_id, user_id, created_at, updated_at, messages: [{role, content, timestamp}] }

端点：
  GET  /api/conversations              — 列出用户所有会话（只返回 metadata，不含消息体）
  GET  /api/conversations/{session_id} — 获取某会话完整消息
  DELETE /api/conversations/{session_id} — 删除会话
  DELETE /api/conversations             — 清空用户所有会话
  POST /api/conversations/append        — 追加一条消息到会话（由 chat 端点内部调用）
  POST /api/conversations/{session_id}/retract — 撤回（永久删除）指定消息，并级联清理短期/长期记忆
"""
from __future__ import annotations

import asyncio
import json
import os
import uuid
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from loguru import logger

import api.state as _state
from config.settings import settings

router = APIRouter()

_CONV_BASE = os.path.join(os.path.dirname(__file__), "..", "data", "conversations")
_MAX_SESSIONS_LISTED = 100


# ── 存储辅助 ──────────────────────────────────────────────────────────────────

def _user_dir(user_id: str) -> str:
    d = os.path.join(_CONV_BASE, user_id)
    os.makedirs(d, exist_ok=True)
    return d


def _session_path(user_id: str, session_id: str) -> str:
    return os.path.join(_user_dir(user_id), f"{session_id}.json")


def _load_session(user_id: str, session_id: str) -> Optional[Dict[str, Any]]:
    path = _session_path(user_id, session_id)
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        logger.warning(f"读取会话文件失败 [{session_id}]: {e}")
        return None


def _save_session(user_id: str, session: Dict[str, Any]) -> None:
    path = _session_path(user_id, session["session_id"])
    with open(path, "w", encoding="utf-8") as f:
        json.dump(session, f, ensure_ascii=False, indent=2)


def _list_sessions_meta(user_id: str) -> List[Dict[str, Any]]:
    d = _user_dir(user_id)
    sessions = []
    for fname in os.listdir(d):
        if not fname.endswith(".json"):
            continue
        sid = fname[:-5]
        path = os.path.join(d, fname)
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            # Return only metadata (first user message as preview, no full messages)
            first_user = next(
                (m["content"][:80] for m in data.get("messages", []) if m.get("role") == "user"),
                "",
            )
            entry: Dict[str, Any] = {
                "session_id":   sid,
                "created_at":   data.get("created_at", ""),
                "updated_at":   data.get("updated_at", ""),
                "message_count": len(data.get("messages", [])),
                "preview":      first_user,
            }
            if data.get("parent_session_id"):
                entry["parent_session_id"] = data["parent_session_id"]
            if data.get("project_id"):
                entry["project_id"] = data["project_id"]
            sessions.append(entry)
        except Exception as e:
            logger.warning(f"读取会话元数据失败 [{sid}]: {e}")
    sessions.sort(key=lambda x: x.get("updated_at", ""), reverse=True)
    return sessions[:_MAX_SESSIONS_LISTED]


# ── Public helpers (called from fastapi_app.py) ───────────────────────────────

def append_messages(
    user_id: str,
    session_id: str,
    messages: List[Dict[str, Any]],
    project_id: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """Called internally after each chat turn to persist messages.

    Returns the persisted message dicts (each guaranteed to have an "id"),
    so callers (chat_router.py) can read back the ids assigned this turn.
    """
    session = _load_session(user_id, session_id) or {
        "session_id": session_id,
        "user_id":    user_id,
        "created_at": datetime.now().isoformat(),
        "updated_at": datetime.now().isoformat(),
        "messages":   [],
    }
    # 写入 project_id（仅在首次传入或与已有值不同时更新）
    if project_id and session.get("project_id") != project_id:
        session["project_id"] = project_id
    for m in messages:
        if not m.get("id"):
            m["id"] = str(uuid.uuid4())
    session["messages"].extend(messages)
    # Trim to avoid unbounded growth
    session["messages"] = session["messages"][-settings.conversation_max_messages:]
    session["updated_at"] = datetime.now().isoformat()
    _save_session(user_id, session)
    return messages


# ── 端点 ──────────────────────────────────────────────────────────────────────

@router.get("/api/conversations")
async def list_conversations(request: Request):
    user_id = getattr(request.state, "user_id", "default")
    sessions = _list_sessions_meta(user_id)
    return {"success": True, "sessions": sessions, "count": len(sessions)}


@router.get("/api/conversations/{session_id}")
async def get_conversation(session_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    session = _load_session(user_id, session_id)
    if session is None:
        return JSONResponse(status_code=404, content={"success": False, "message": "会话不存在"})
    return {"success": True, "session": session}


@router.delete("/api/conversations/{session_id}")
async def delete_conversation(session_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    path = _session_path(user_id, session_id)
    if not os.path.exists(path):
        return JSONResponse(status_code=404, content={"success": False, "message": "会话不存在"})
    os.remove(path)
    logger.info(f"会话已删除: user={user_id}, session={session_id}")
    return {"success": True, "session_id": session_id}


@router.delete("/api/conversations")
async def clear_all_conversations(request: Request):
    user_id = getattr(request.state, "user_id", "default")
    d = _user_dir(user_id)
    count = 0
    for fname in os.listdir(d):
        if fname.endswith(".json"):
            os.remove(os.path.join(d, fname))
            count += 1
    logger.info(f"清空会话: user={user_id}, 删除 {count} 条")
    return {"success": True, "deleted": count}


@router.post("/api/conversations/branch")
async def branch_conversation(request: Request):
    """从指定消息列表创建分支会话，返回新的 session_id。"""
    user_id = getattr(request.state, "user_id", "default")
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"success": False, "message": "请求体无效"})

    messages          = body.get("messages", [])
    parent_session_id = body.get("parent_session_id")

    new_session_id = str(uuid.uuid4())
    now = datetime.now().isoformat()
    session: Dict[str, Any] = {
        "session_id":        new_session_id,
        "user_id":           user_id,
        "created_at":        now,
        "updated_at":        now,
        "parent_session_id": parent_session_id,
        "messages":          messages[-settings.conversation_max_messages:],
    }
    _save_session(user_id, session)
    logger.info(
        f"分支会话已创建: user={user_id}, new={new_session_id}, "
        f"parent={parent_session_id}, msgs={len(messages)}"
    )
    return {"success": True, "session_id": new_session_id}


@router.post("/api/conversations/append")
async def append_conversation(request: Request):
    """Internal endpoint for appending messages to a session."""
    user_id = getattr(request.state, "user_id", "default")
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"success": False, "message": "请求体无效"})

    session_id = body.get("session_id") or str(uuid.uuid4())
    messages   = body.get("messages", [])
    if not messages:
        return {"success": True, "session_id": session_id}

    append_messages(user_id, session_id, messages)
    return {"success": True, "session_id": session_id}


_MAX_RETRACT_BATCH = 50


def _suppress_distilled_memories(agent, retracted_message_ids: List[str]) -> int:
    """撤回后，把来源命中这些 message_id 的长期记忆标记为排除检索，不物理删除。

    一条摘要可能混合了多条消息的内容，物理删除有误伤其他未撤回消息的风险；
    硬过滤（excluded_from_retrieval）足以达到"不再污染上下文"的目标。
    """
    retracted = set(retracted_message_ids)
    count = 0
    for memory_id, item in list(agent.memory.long_term.memories.items()):
        source_ids = set(item.metadata.get("source_message_ids") or [])
        if source_ids & retracted:
            agent.memory.long_term.update(memory_id, metadata={"excluded_from_retrieval": True})
            count += 1
    return count


async def _suppress_distilled_memories_bg(agent, retracted_message_ids: List[str]) -> None:
    """后台异步执行：扫描长期记忆，命中来源 id 的条目标记排除检索。
    不放在 retract 请求主流程里同步跑，避免长期记忆条目较多时拖慢撤回响应。
    """
    try:
        count = await asyncio.to_thread(_suppress_distilled_memories, agent, retracted_message_ids)
        if count:
            logger.info(f"撤回级联：{count} 条长期记忆已标记排除检索")
    except Exception as e:
        logger.warning(f"长期记忆排除标记失败（不影响已完成的内部删除）: {e}")


@router.post("/api/conversations/{session_id}/retract")
async def retract_messages(session_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    body = await request.json()
    requested_ids = list(dict.fromkeys(body.get("message_ids") or []))  # 去重保序
    if not requested_ids:
        return {"success": True, "requested": 0, "deleted": 0, "deleted_ids": [], "memory_purged": 0}
    if len(requested_ids) > _MAX_RETRACT_BATCH:
        return JSONResponse(status_code=400, content={
            "success": False,
            "message": f"单次最多撤回 {_MAX_RETRACT_BATCH} 条，请分批操作",
        })

    session = _load_session(user_id, session_id)
    if session is None:
        return {"success": True, "requested": len(requested_ids), "deleted": 0, "deleted_ids": [], "memory_purged": 0}

    target_ids = set(requested_ids)
    kept, removed = [], []
    for m in session["messages"]:
        (removed if m.get("id") in target_ids else kept).append(m)
    session["messages"] = kept
    session["updated_at"] = datetime.now().isoformat()
    _save_session(user_id, session)

    removed_ids = [m["id"] for m in removed if m.get("id")]
    purged = 0
    if removed_ids and _state.agent:
        purged = _state.agent.memory.short_term.delete_by_ids(removed_ids)
        asyncio.create_task(_suppress_distilled_memories_bg(_state.agent, removed_ids))

    return {
        "success": True,
        "requested": len(requested_ids),
        "deleted": len(removed_ids),
        "deleted_ids": removed_ids,
        "memory_purged": purged,
    }
