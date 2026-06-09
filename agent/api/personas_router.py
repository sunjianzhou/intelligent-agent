"""Persona 管理路由：列出可用角色、切换用户当前角色。

角色文件存储于 agent/personas/*.md，每个文件即一个角色。
"""
import json as _json
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from loguru import logger

router = APIRouter(prefix="/api/personas", tags=["personas"])

_PERSONAS_DIR = Path(__file__).parent.parent / "personas"
_PERSONA_PREFS_FILE = Path(__file__).parent.parent / "data" / "user_persona_prefs.json"

# 运行时 per-user 角色映射（user_id -> persona_name）
# 由 fastapi_app 共享引用并在启动时预填
_user_personas: dict = {}


def _save_persona_prefs() -> None:
    try:
        _PERSONA_PREFS_FILE.parent.mkdir(parents=True, exist_ok=True)
        _PERSONA_PREFS_FILE.write_text(
            _json.dumps(_user_personas, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception as _e:
        logger.warning(f"保存角色偏好失败: {_e}")


def _list_persona_names() -> list[str]:
    if not _PERSONAS_DIR.exists():
        return []
    return sorted(p.stem for p in _PERSONAS_DIR.glob("*.md"))


def _read_persona_content(name: str) -> Optional[str]:
    path = _PERSONAS_DIR / f"{name}.md"
    if not path.exists():
        return None
    try:
        return path.read_text(encoding="utf-8").strip()
    except Exception as e:
        logger.warning(f"读取角色文件失败 {path}: {e}")
        return None


class PersonaSwitchRequest(BaseModel):
    persona: str

class PersonaUpsertRequest(BaseModel):
    name: str          # 文件名（用于内部引用，仅允许 ASCII+中文）
    content: str
    display_name: str = ""  # 可选展示名；若提供，自动嵌入 Markdown 首行作为 # 标题


@router.get("")
async def list_personas():
    """列出所有可用角色（文件名 + 首行标题）。"""
    personas = []
    for name in _list_persona_names():
        content = _read_persona_content(name)
        title = name
        if content:
            first_line = content.splitlines()[0]
            if first_line.startswith("#"):
                title = first_line.lstrip("#").strip()
        personas.append({"name": name, "title": title})
    return {"personas": personas, "count": len(personas)}


@router.get("/current")
async def get_current_persona(http_req: Request):
    """获取当前用户的角色名。"""
    user_id = getattr(http_req.state, "user_id", "default")
    persona_name = _user_personas.get(user_id, "default")
    content = _read_persona_content(persona_name)
    title = persona_name
    if content:
        first_line = content.splitlines()[0]
        if first_line.startswith("#"):
            title = first_line.lstrip("#").strip()
    return {"persona": persona_name, "title": title, "content": content}


@router.post("/switch")
async def switch_persona(body: PersonaSwitchRequest, http_req: Request):
    """切换当前用户的角色。"""
    user_id = getattr(http_req.state, "user_id", "default")
    name = body.persona.strip()
    if not name:
        raise HTTPException(status_code=400, detail="persona 不能为空")

    content = _read_persona_content(name)
    if content is None:
        raise HTTPException(status_code=404, detail=f"角色 '{name}' 不存在")

    _user_personas[user_id] = name
    _save_persona_prefs()
    logger.info(f"用户 {user_id} 切换角色 → {name}")
    return {"success": True, "persona": name}


@router.post("/upsert")
async def upsert_persona(body: PersonaUpsertRequest):
    """创建或更新角色（写入 .md 文件）。"""
    import re
    name = body.name.strip()
    if not re.match(r'^[a-zA-Z0-9_\-一-鿿]+$', name):
        raise HTTPException(status_code=400, detail="角色名只能包含字母、数字、下划线、短横线或中文")
    if len(name) > 64:
        raise HTTPException(status_code=400, detail="角色名不能超过 64 个字符")
    content = body.content.strip()
    if not content:
        raise HTTPException(status_code=400, detail="角色内容不能为空")

    # 若提供了 display_name 且 content 首行不是 Markdown 标题，自动补充
    display = body.display_name.strip()
    if display and not content.startswith("#"):
        content = f"# {display}\n\n{content}"
    elif display and content.startswith("#"):
        # 替换已有的首行标题
        lines = content.splitlines()
        lines[0] = f"# {display}"
        content = "\n".join(lines)

    try:
        _PERSONAS_DIR.mkdir(parents=True, exist_ok=True)
        path = _PERSONAS_DIR / f"{name}.md"
        path.write_text(content, encoding="utf-8")
        # 计算实际展示名（首行 # 标题 或 文件名）
        actual_title = display or name
        if not display:
            first = content.splitlines()[0] if content else ""
            if first.startswith("#"):
                actual_title = first.lstrip("#").strip()
        logger.info(f"角色已保存: {name} (显示名: {actual_title})")
        return {"success": True, "name": name, "title": actual_title}
    except Exception as e:
        logger.error(f"保存角色失败 {name}: {e}")
        raise HTTPException(status_code=500, detail="保存失败")


@router.delete("/{name}")
async def delete_persona(name: str):
    """删除指定角色（default 不可删除）。"""
    if name == "default":
        raise HTTPException(status_code=400, detail="默认角色不可删除")
    path = _PERSONAS_DIR / f"{name}.md"
    if not path.exists():
        raise HTTPException(status_code=404, detail=f"角色 '{name}' 不存在")
    try:
        path.unlink()
        # 若当前用户正在使用该角色，重置为 default
        for uid, p in list(_user_personas.items()):
            if p == name:
                _user_personas[uid] = "default"
        _save_persona_prefs()
        logger.info(f"角色已删除: {name}")
        return {"success": True}
    except Exception as e:
        logger.error(f"删除角色失败 {name}: {e}")
        raise HTTPException(status_code=500, detail="删除失败")


@router.get("/{name}/content")
async def get_persona_content(name: str):
    """获取指定角色的 Markdown 内容。"""
    content = _read_persona_content(name)
    if content is None:
        raise HTTPException(status_code=404, detail=f"角色 '{name}' 不存在")
    return {"name": name, "content": content}
