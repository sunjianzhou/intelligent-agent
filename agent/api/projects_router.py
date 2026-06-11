"""
项目 CRUD API — 将项目元数据持久化到服务端 JSON 文件。
存储路径：agent/data/projects/{user_id}/{project_id}.json
"""
from __future__ import annotations

import json
import os
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from loguru import logger

router = APIRouter()

_PROJECTS_BASE = os.path.join(os.path.dirname(__file__), "..", "data", "projects")


# ── 存储辅助 ──────────────────────────────────────────────────────────────────

def _user_dir(user_id: str) -> str:
    d = os.path.join(_PROJECTS_BASE, user_id)
    os.makedirs(d, exist_ok=True)
    return d


def _project_path(user_id: str, project_id: str) -> str:
    return os.path.join(_user_dir(user_id), f"{project_id}.json")


def _load_project(user_id: str, project_id: str) -> Optional[Dict[str, Any]]:
    path = _project_path(user_id, project_id)
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        logger.warning(f"读取项目文件失败 [{project_id}]: {e}")
        return None


def _save_project(user_id: str, project: Dict[str, Any]) -> None:
    path = _project_path(user_id, project["id"])
    with open(path, "w", encoding="utf-8") as f:
        json.dump(project, f, ensure_ascii=False, indent=2)


def _list_projects(user_id: str) -> List[Dict[str, Any]]:
    d = _user_dir(user_id)
    projects = []
    for fname in os.listdir(d):
        if not fname.endswith(".json"):
            continue
        project_id = fname[:-5]
        p = _load_project(user_id, project_id)
        if p:
            projects.append(p)
    projects.sort(key=lambda x: x.get("updated_at", ""), reverse=True)
    return projects


def _delete_project(user_id: str, project_id: str) -> bool:
    path = _project_path(user_id, project_id)
    if os.path.exists(path):
        os.remove(path)
        return True
    return False


def _make_project(title: str, project_id: Optional[str] = None) -> Dict[str, Any]:
    import uuid
    now = datetime.now().isoformat()
    return {
        "id":              project_id or f"proj_{uuid.uuid4().hex[:12]}",
        "title":           title,
        "created_at":      now,
        "updated_at":      now,
        "session_ids":     [],
        "context_version": 0,
        "context_summary": "",
        "spec": {
            "content":            "",
            "version":            1,
            "last_updated":       "",
            "last_reviewed_turn": 0,
        },
        "task_tree": {
            "root_tasks":    [],
            "auto_decompose": True,
            "last_updated":  "",
        },
        "synced": True,
    }


# ── 端点 ──────────────────────────────────────────────────────────────────────

@router.get("/api/projects")
async def list_projects(request: Request):
    user_id = getattr(request.state, "user_id", "default")
    projects = _list_projects(user_id)
    return {"success": True, "projects": projects, "count": len(projects)}


@router.post("/api/projects")
async def create_project(request: Request):
    user_id = getattr(request.state, "user_id", "default")
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"success": False, "message": "请求体无效"})

    title = (body.get("title") or "").strip()
    if not title:
        return JSONResponse(status_code=400, content={"success": False, "message": "项目名称不能为空"})

    project_id = body.get("id")
    project = _make_project(title, project_id)
    # 如果请求中携带了完整字段（前端迁移），合并进来
    for key in ("session_ids", "context_version", "context_summary", "spec", "task_tree", "created_at"):
        if key in body:
            project[key] = body[key]

    _save_project(user_id, project)
    logger.info(f"项目已创建: user={user_id}, id={project['id']}, title={title}")
    return {"success": True, "project": project}


@router.get("/api/projects/{project_id}")
async def get_project(project_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    project = _load_project(user_id, project_id)
    if project is None:
        return JSONResponse(status_code=404, content={"success": False, "message": "项目不存在"})
    return {"success": True, "project": project}


@router.put("/api/projects/{project_id}")
async def update_project(project_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"success": False, "message": "请求体无效"})

    existing = _load_project(user_id, project_id)
    if existing is None:
        return JSONResponse(status_code=404, content={"success": False, "message": "项目不存在"})

    existing.update(body)
    existing["id"]         = project_id          # 防止 id 被覆盖
    existing["updated_at"] = datetime.now().isoformat()
    existing["synced"]     = True
    _save_project(user_id, existing)
    logger.debug(f"项目已更新: user={user_id}, id={project_id}")
    return {"success": True, "project": existing}


@router.delete("/api/projects/{project_id}")
async def delete_project(project_id: str, request: Request):
    user_id = getattr(request.state, "user_id", "default")
    deleted = _delete_project(user_id, project_id)
    if not deleted:
        return JSONResponse(status_code=404, content={"success": False, "message": "项目不存在"})
    logger.info(f"项目已删除: user={user_id}, id={project_id}")
    return {"success": True, "project_id": project_id}
