"""基础健康检查 + 工具列表 + 通知轮询端点。"""
from datetime import datetime

from fastapi import APIRouter
from loguru import logger

import api.state as _state
from config.settings import settings

router = APIRouter()


@router.get("/")
async def root():
    return {
        "status": "running",
        "ollama_available": _state.OLLAMA_AVAILABLE,
        "model": _state.provider.current_model if _state.provider else "unavailable",
        "agent_ready": _state.agent is not None,
    }


@router.get("/health")
async def health():
    current = _state.provider.current_model if _state.provider else "unavailable"
    configured_cloud = bool(settings.cloud_provider and settings.cloud_api_key and settings.cloud_model)
    return {
        "status": "connected",
        "service": "intelligent-agent",
        "ollama_available": _state.OLLAMA_AVAILABLE,
        "model": current,
        "agent_model": current,
        "agent_ready": _state.agent is not None,
        "cloud_mode": _state.CLOUD_MODE,
        "cloud_model":    settings.cloud_model    if configured_cloud else "",
        "cloud_base_url": settings.cloud_base_url if configured_cloud else "",
        "timestamp": datetime.now().isoformat(),
    }


@router.get("/api/tools/list")
async def list_tools():
    if _state.agent:
        name_to_cat = {
            n: cat
            for cat, names in _state.agent.tool_manager.tool_categories.items()
            for n in names
        }
        tools = [
            {
                "name": name,
                "description": tool.description,
                "category": name_to_cat.get(name, "general"),
                "enabled": True,
            }
            for name, tool in _state.agent.tool_manager.get_all_tools().items()
        ]
        return {"tools": tools, "count": len(tools)}

    return {
        "tools": [
            {"name": "calculator",  "description": "数学计算",  "category": "math",    "enabled": True},
            {"name": "time_tool",   "description": "时间查询",  "category": "utility", "enabled": True},
            {"name": "file_tool",   "description": "文件读写",  "category": "file",    "enabled": True},
            {"name": "web_search",  "description": "网络搜索",  "category": "web",     "enabled": False},
            {"name": "shell_tool",  "description": "Shell执行", "category": "system",  "enabled": False},
        ],
        "count": 5,
    }


@router.get("/api/notifications/poll")
async def poll_notifications():
    """前端轮询：取出并返回所有待推送通知（执行后清空队列）。"""
    try:
        from scheduler.simple_scheduler import pop_notifications
        items = pop_notifications()
        return {"notifications": items, "count": len(items)}
    except Exception as e:
        logger.debug(f"notifications poll: {e}")
        return {"notifications": [], "count": 0}
