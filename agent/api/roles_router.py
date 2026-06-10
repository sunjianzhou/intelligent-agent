"""
角色配置 REST API — Python Agent 侧。

数据持久化：
  角色 JSON → data/roles/{role_id}.json
  长期记忆   → ChromaDB  role_{id}_memory
  日记       → ChromaDB  role_{id}_journal
  知识库     → ChromaDB  role_{id}_knowledge
  激活状态   → data/user_active_roles.json（重启后自动恢复）

/api/roles/{role_id}/activate  — 设置用户当前激活角色（per-user，持久化）
/api/roles/activate            — GET 当前激活角色，DELETE 取消激活
"""
from __future__ import annotations

import json as _json
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Request
from loguru import logger
from pydantic import BaseModel

from personas.role_models import RoleConfig
from personas.role_manager import RoleManager

router = APIRouter(prefix="/api/roles", tags=["roles"])

# ── 全局单例 ──────────────────────────────────────────────────────────────────

_role_manager: Optional[RoleManager] = None

# user_id → role_id（运行时；由 fastapi_app.py lifespan 从文件恢复）
_user_active_roles: dict = {}

_USER_ACTIVE_ROLES_FILE = Path(__file__).parent.parent / "data" / "user_active_roles.json"


def _load_user_active_roles() -> dict:
    try:
        if _USER_ACTIVE_ROLES_FILE.exists():
            return _json.loads(_USER_ACTIVE_ROLES_FILE.read_text(encoding="utf-8"))
    except Exception as _e:
        logger.warning(f"读取用户激活角色文件失败: {_e}")
    return {}


def _save_user_active_roles() -> None:
    try:
        _USER_ACTIVE_ROLES_FILE.parent.mkdir(parents=True, exist_ok=True)
        _USER_ACTIVE_ROLES_FILE.write_text(
            _json.dumps(_user_active_roles, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception as _e:
        logger.warning(f"保存用户激活角色失败: {_e}")


def get_role_manager() -> RoleManager:
    global _role_manager
    if _role_manager is None:
        _role_manager = RoleManager()
    return _role_manager


# ── 请求体 ────────────────────────────────────────────────────────────────────

class ActivateRequest(BaseModel):
    role_id: str


# ── 激活管理（路径必须在 /{role_id} 之前，否则被变量段吞掉） ──────────────────

@router.get("/activate")
async def get_active_role(req: Request):
    user_id = getattr(req.state, "user_id", "default")
    role_id = _user_active_roles.get(user_id)
    return {"success": True, "role_id": role_id}


@router.post("/activate")
async def activate_role(body: ActivateRequest, req: Request):
    user_id = getattr(req.state, "user_id", "default")
    rm = get_role_manager()
    if not rm.load_role(body.role_id):
        raise HTTPException(404, f"角色不存在: {body.role_id}")
    _user_active_roles[user_id] = body.role_id
    _save_user_active_roles()
    logger.info(f"用户 {user_id} 激活角色: {body.role_id}")
    return {"success": True, "role_id": body.role_id}


@router.delete("/activate")
async def deactivate_role(req: Request):
    user_id = getattr(req.state, "user_id", "default")
    old = _user_active_roles.pop(user_id, None)
    _save_user_active_roles()
    logger.info(f"用户 {user_id} 取消激活角色: {old}")
    return {"success": True, "deactivated": old}


# ── 角色 CRUD ─────────────────────────────────────────────────────────────────

@router.get("")
async def list_roles():
    rm = get_role_manager()
    roles = []
    for role_id in rm.list_roles():
        role = rm.load_role(role_id)
        if role:
            roles.append(role.model_dump())
    return {"success": True, "roles": roles, "count": len(roles)}


@router.get("/{role_id}/card")
async def get_role_card(role_id: str):
    rm = get_role_manager()
    role = rm.load_role(role_id)
    if not role:
        raise HTTPException(404, f"角色不存在: {role_id}")
    return {"success": True, "card": role.role_card.model_dump()}


@router.get("/{role_id}")
async def get_role(role_id: str):
    rm = get_role_manager()
    role = rm.load_role(role_id)
    if not role:
        raise HTTPException(404, f"角色不存在: {role_id}")
    return {"success": True, "role": role.model_dump()}


@router.post("")
async def create_role(body: dict, req: Request):
    rm = get_role_manager()
    try:
        role = RoleConfig.model_validate(body)
    except Exception as e:
        raise HTTPException(400, f"角色数据格式错误: {e}")
    rm.save_role(role)
    logger.info(f"角色已创建: {role.role_id}")
    return {"success": True, "role_id": role.role_id, "role": role.model_dump()}


@router.put("/{role_id}")
async def update_role(role_id: str, body: dict):
    rm = get_role_manager()
    body["role_id"] = role_id
    try:
        role = RoleConfig.model_validate(body)
    except Exception as e:
        raise HTTPException(400, f"角色数据格式错误: {e}")
    rm.save_role(role)
    return {"success": True, "role_id": role_id, "role": role.model_dump()}


@router.patch("/{role_id}")
async def patch_role(role_id: str, body: dict):
    rm = get_role_manager()
    existing = rm.load_role(role_id)
    if not existing:
        raise HTTPException(404, f"角色不存在: {role_id}")
    data = existing.model_dump()
    _deep_merge(data, body)
    data["role_id"] = role_id
    try:
        role = RoleConfig.model_validate(data)
    except Exception as e:
        raise HTTPException(400, f"角色数据格式错误: {e}")
    rm.save_role(role)
    return {"success": True, "role_id": role_id, "role": role.model_dump()}


@router.post("/{role_id}/memory")
async def append_memory(role_id: str, body: dict):
    rm = get_role_manager()
    content = (body.get("content") or "").strip()
    memory_type = body.get("type", "commitment")
    if not content:
        raise HTTPException(400, "content 不能为空")
    if memory_type == "commitment":
        role = rm.add_commitment(role_id, content)
        if not role:
            raise HTTPException(404, f"角色不存在: {role_id}")
        return {"success": True, "role_id": role_id}
    elif memory_type == "long_term":
        rm.add_memory(role_id, content, memory_type="long_term")
        return {"success": True, "role_id": role_id}
    else:
        raise HTTPException(400, f"不支持的 memory type: {memory_type}")


@router.delete("/{role_id}")
async def delete_role_endpoint(role_id: str):
    rm = get_role_manager()
    deleted = rm.delete_role(role_id)
    if not deleted:
        raise HTTPException(404, f"角色不存在: {role_id}")
    for uid in [u for u, r in _user_active_roles.items() if r == role_id]:
        del _user_active_roles[uid]
    _save_user_active_roles()
    return {"success": True}


# ── 辅助 ──────────────────────────────────────────────────────────────────────

def _deep_merge(base: dict, overlay: dict) -> None:
    for k, v in overlay.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _deep_merge(base[k], v)
        elif v is not None:
            base[k] = v
