"""Skill 相关的 FastAPI 路由"""
from fastapi import APIRouter
from pydantic import BaseModel
from typing import Optional, List

from skills.manager import skill_manager
from skills.templates import BUILTIN_TEMPLATES, get_template

router = APIRouter(prefix="/api/skills", tags=["skills"])


class SkillCreateRequest(BaseModel):
    name:             str
    description:      str        = ""
    trigger_keywords: List[str]  = []
    tool_hints:       List[str]  = []
    forced_tools:     List[str]  = []
    scenario_tags:    List[str]  = []
    overall_strategy: str        = ""
    steps:            List[dict] = []
    enabled:          bool       = True


class SkillUpdateRequest(BaseModel):
    name:             Optional[str]        = None
    description:      Optional[str]        = None
    trigger_keywords: Optional[List[str]]  = None
    tool_hints:       Optional[List[str]]  = None
    forced_tools:     Optional[List[str]]  = None
    overall_strategy: Optional[str]        = None
    steps:            Optional[List[dict]] = None
    enabled:          Optional[bool]       = None


@router.get("")
async def list_skills(tag: Optional[str] = None, enabled_only: bool = False):
    skills = skill_manager.list_all(tag=tag, enabled_only=enabled_only)
    return {"skills": [s.to_dict() for s in skills], "count": len(skills)}


@router.post("")
async def create_skill(req: SkillCreateRequest):
    skill = skill_manager.create(**req.dict())
    return {"success": True, "skill": skill.to_dict()}


@router.put("/{skill_id}")
async def update_skill(skill_id: str, req: SkillUpdateRequest):
    updates = {k: v for k, v in req.dict().items() if v is not None}
    skill   = skill_manager.update(skill_id, **updates)
    if not skill:
        return {"success": False, "message": "Skill 不存在"}
    return {"success": True, "skill": skill.to_dict()}


@router.delete("/{skill_id}")
async def delete_skill(skill_id: str):
    ok = skill_manager.delete(skill_id)
    return {"success": ok, "message": "已删除" if ok else "不存在"}


@router.patch("/{skill_id}/toggle")
async def toggle_skill(skill_id: str):
    skill = skill_manager.get(skill_id)
    if not skill:
        return {"success": False, "message": "不存在"}
    skill = skill_manager.update(skill_id, enabled=not skill.enabled)
    return {"success": True, "enabled": skill.enabled}


# ── 模板相关接口 ──────────────────────────────────────────

@router.get("/templates/list")
async def list_templates():
    """获取所有内置模板"""
    return {"templates": BUILTIN_TEMPLATES, "count": len(BUILTIN_TEMPLATES)}


@router.post("/templates/{template_id}/apply")
async def apply_template(template_id: str):
    """从模板创建 Skill"""
    tpl = get_template(template_id)
    if not tpl:
        return {"success": False, "message": f"模板 {template_id} 不存在"}

    # 检查是否已从该模板创建过（避免重复）
    existing = [
        s for s in skill_manager.list_all()
        if s.name == tpl["name"]
    ]
    if existing:
        return {"success": False, "message": f"已存在同名 Skill：{tpl['name']}，请先删除再导入"}

    # 去掉模板 id 字段，用新的 uuid
    data = {k: v for k, v in tpl.items() if k != "id"}
    skill = skill_manager.create(**data)
    return {"success": True, "skill": skill.to_dict()}