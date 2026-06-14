"""
图片生成 REST API

端点：
  GET  /api/image/provider-status  — 当前 Provider 状态（可用性/模型名）
  GET  /api/image/models           — 列出可切换模型（SD WebUI / ComfyUI）
  POST /api/image/switch-model     — 切换模型（SD WebUI）
  POST /api/image/generate         — REST 直接生成（返回图片 URL）
  GET  /api/images                 — 列出历史生成图片（元数据列表）
  DELETE /api/images/{filename}    — 删除指定生成图片
"""
from __future__ import annotations

import os
import re
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from loguru import logger

import api.state as _state
from config.settings import settings

router = APIRouter()

_SAFE_FILENAME = re.compile(r"^[\w\-]+\.(png|jpg|jpeg|webp)$", re.IGNORECASE)
_MAX_GALLERY_SIZE_GB = 5.0   # 超过此阈值自动清理最旧文件


def _image_dir() -> Path:
    if settings.image_gen_output_dir:
        p = Path(settings.image_gen_output_dir)
    else:
        p = Path(__file__).parent.parent / "data" / "images"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _get_provider():
    """实例化并返回当前配置的 Provider（每次调用均重新实例化，反映最新配置）。"""
    from services.image import create_image_provider
    return create_image_provider()


# ── 状态查询 ──────────────────────────────────────────────────────────────────

@router.get("/api/image/provider-status")
async def get_provider_status():
    provider = _get_provider()
    if provider is None:
        return {
            "success":    True,
            "available":  False,
            "provider":   settings.image_gen_provider,
            "model":      "",
            "message":    f"Provider '{settings.image_gen_provider}' 初始化失败，请检查配置",
        }
    available = provider.is_available()
    return {
        "success":   True,
        "available": available,
        "provider":  provider.provider_name,
        "model":     provider.current_model,
        "base_url":  settings.image_gen_base_url,
        "message":   "服务正常" if available else "服务不可达，请确认本地服务已启动",
    }


# ── 模型管理 ──────────────────────────────────────────────────────────────────

@router.get("/api/image/models")
async def list_image_models():
    """列出可用模型列表（目前支持 SD WebUI / ComfyUI）。"""
    provider_name = settings.image_gen_provider.lower()

    if provider_name == "sd_webui":
        from services.image.sd_webui_provider import SDWebUIProvider
        p = SDWebUIProvider(
            base_url=settings.image_gen_base_url,
            model=settings.image_gen_model,
            username=settings.image_gen_sd_user,
            password=settings.image_gen_sd_pass,
        )
        models = await p.list_models()
        return {"success": True, "provider": "sd_webui", "models": models, "current": settings.image_gen_model}

    if provider_name == "comfyui":
        from services.image.comfyui_provider import ComfyUIProvider
        p = ComfyUIProvider(
            base_url=settings.image_gen_base_url,
            workflow_path=settings.image_gen_comfyui_workflow,
            model=settings.image_gen_model,
        )
        models = await p.list_models()
        return {"success": True, "provider": "comfyui", "models": models, "current": settings.image_gen_model}

    if provider_name == "diffusers":
        return {
            "success":  True,
            "provider": "diffusers",
            "models":   [settings.image_gen_diffusers_model],
            "current":  settings.image_gen_diffusers_model,
            "note":     "diffusers 模型切换请修改 IMAGE_GEN_DIFFUSERS_MODEL 配置",
        }

    return {"success": True, "provider": provider_name, "models": [], "note": "云端 Provider 模型列表不支持"}


@router.get("/api/image/progress")
async def get_image_progress():
    """查询当前生成进度（SD WebUI / ComfyUI 均支持，其他返回 0）。"""
    provider_name = settings.image_gen_provider.lower()
    if provider_name == "sd_webui":
        from services.image.sd_webui_provider import SDWebUIProvider
        p = SDWebUIProvider(
            base_url=settings.image_gen_base_url,
            model=settings.image_gen_model,
            username=settings.image_gen_sd_user,
            password=settings.image_gen_sd_pass,
        )
        data = await p.get_progress()
        return {"success": True, "provider": "sd_webui", **data}
    if provider_name == "comfyui":
        from services.image.comfyui_provider import ComfyUIProvider
        p = ComfyUIProvider(
            base_url=settings.image_gen_base_url,
            workflow_path=settings.image_gen_comfyui_workflow,
            model=settings.image_gen_model,
        )
        data = p.get_progress()
        return {"success": True, "provider": "comfyui", **data}
    if provider_name == "diffusers":
        from services.image.diffusers_provider import DiffusersProvider
        p = DiffusersProvider(
            model_id=settings.image_gen_diffusers_model,
            device=settings.image_gen_diffusers_device,
        )
        data = p.get_progress()
        return {"success": True, "provider": "diffusers", **data}
    return {"success": True, "provider": provider_name, "progress": 0.0, "eta": 0.0,
            "note": f"{provider_name} 不支持进度查询"}


@router.post("/api/image/switch-model")
async def switch_image_model(request: Request):
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"success": False, "message": "请求体无效"})

    model_name = body.get("model", "").strip()
    if not model_name:
        return JSONResponse(status_code=400, content={"success": False, "message": "model 不能为空"})

    provider_name = settings.image_gen_provider.lower()

    if provider_name == "sd_webui":
        from services.image.sd_webui_provider import SDWebUIProvider
        p = SDWebUIProvider(
            base_url=settings.image_gen_base_url,
            model=model_name,
            username=settings.image_gen_sd_user,
            password=settings.image_gen_sd_pass,
        )
        ok, msg = await p.switch_model(model_name)
        if ok:
            settings.image_gen_model = model_name
        return {"success": ok, "model": model_name, "message": msg}

    if provider_name == "diffusers":
        from services.image.diffusers_provider import DiffusersProvider
        p = DiffusersProvider(
            model_id=settings.image_gen_diffusers_model,
            device=settings.image_gen_diffusers_device,
        )
        ok, msg = p.switch_model(model_name)
        if ok:
            settings.image_gen_diffusers_model = model_name
        return {"success": ok, "model": model_name, "message": msg}

    return JSONResponse(
        status_code=400,
        content={"success": False, "message": f"Provider '{provider_name}' 不支持运行时换模型"},
    )


# ── 生成 ──────────────────────────────────────────────────────────────────────

@router.post("/api/image/generate")
async def generate_image(request: Request):
    """REST 直接生成图片，返回可访问的图片 URL。"""
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"success": False, "message": "请求体无效"})

    prompt = (body.get("prompt") or "").strip()
    if not prompt:
        return JSONResponse(status_code=400, content={"success": False, "message": "prompt 不能为空"})

    provider = _get_provider()
    if provider is None or not provider.is_available():
        tip = _unavailable_tip()
        return JSONResponse(status_code=503, content={"success": False, "message": tip})

    from services.image.base_image_provider import ImageRequest
    req = ImageRequest(
        prompt             = prompt,
        size               = body.get("size")               or settings.image_gen_size,
        steps              = int(body.get("steps", 0))      or settings.image_gen_steps,
        guidance_scale     = float(body.get("cfg", 0))      or 7.5,
        negative_prompt    = body.get("negative_prompt")    or "",
        style              = body.get("style")              or None,
        sampler_name       = body.get("sampler_name")       or "DPM++ 2M Karras",
        init_image_base64  = body.get("init_image_base64")  or None,
        denoising_strength = float(body.get("denoising_strength", 0.75)),
    )

    logger.info(f"[image_router] 生成请求: prompt={prompt[:60]} size={req.size}")

    result = await provider.generate(req)
    if not result.success:
        return JSONResponse(status_code=500, content={"success": False, "message": result.error})

    # 持久化到本地
    import uuid
    from tools.builtin_tools.image_tool import _persist_image
    filename  = f"gen_{uuid.uuid4().hex[:12]}.png"
    local_url = await _persist_image(result, filename, _image_dir())

    # 目录大小检查（异步后台，不影响响应）
    import asyncio
    asyncio.create_task(_maybe_cleanup_old_images())

    return {
        "success":  True,
        "url":      local_url,
        "filename": filename,
        "provider": result.provider,
        "model":    result.model,
        "prompt":   prompt,
        "size":     req.size,
    }


# ── 历史图片 ─────────────────────────────────────────────────────────────────

@router.get("/api/images")
async def list_images():
    """列出 data/images/ 下所有已生成图片，按时间倒序。"""
    img_dir = _image_dir()
    files = []
    for f in img_dir.iterdir():
        if f.is_file() and _SAFE_FILENAME.match(f.name):
            stat = f.stat()
            files.append({
                "filename":   f.name,
                "url":        f"/api/images/{f.name}",
                "size_bytes": stat.st_size,
                "created_at": datetime.fromtimestamp(stat.st_mtime).isoformat(),
            })
    files.sort(key=lambda x: x["created_at"], reverse=True)
    return {"success": True, "images": files, "count": len(files)}


@router.delete("/api/images/{filename}")
async def delete_image(filename: str):
    if not _SAFE_FILENAME.match(filename):
        return JSONResponse(status_code=400, content={"success": False, "message": "非法文件名"})
    path = _image_dir() / filename
    if not path.exists():
        return JSONResponse(status_code=404, content={"success": False, "message": "图片不存在"})
    path.unlink()
    logger.info(f"已删除生成图片: {filename}")
    return {"success": True, "filename": filename}


# ── 辅助 ─────────────────────────────────────────────────────────────────────

def _unavailable_tip() -> str:
    prov = settings.image_gen_provider.lower()
    if prov == "sd_webui":
        return (
            f"SD WebUI 服务不可达（{settings.image_gen_base_url}）。\n"
            "请确认已启动 AUTOMATIC1111 并加上 --api 参数：\n"
            "  python launch.py --api --listen --port 7860"
        )
    if prov == "comfyui":
        return (
            f"ComfyUI 服务不可达（{settings.image_gen_base_url}）。\n"
            "请确认已启动 ComfyUI：\n"
            "  python main.py --listen 0.0.0.0 --port 8188"
        )
    if prov == "diffusers":
        return "diffusers 未安装，请执行：pip install diffusers transformers accelerate torch"
    return f"图片生成 Provider '{prov}' 不可用，请检查配置"


async def _maybe_cleanup_old_images():
    """若目录超过阈值则删除最旧文件（异步后台任务）。"""
    try:
        img_dir = _image_dir()
        files = sorted(
            [f for f in img_dir.iterdir() if f.is_file() and _SAFE_FILENAME.match(f.name)],
            key=lambda f: f.stat().st_mtime,
        )
        total_bytes = sum(f.stat().st_size for f in files)
        limit_bytes = int(_MAX_GALLERY_SIZE_GB * 1024 ** 3)
        while total_bytes > limit_bytes and files:
            oldest = files.pop(0)
            size   = oldest.stat().st_size
            oldest.unlink()
            total_bytes -= size
            logger.info(f"[图片清理] 已删除旧图片 {oldest.name}（总大小超出 {_MAX_GALLERY_SIZE_GB} GB）")
    except Exception as e:
        logger.warning(f"图片目录清理失败: {e}")
