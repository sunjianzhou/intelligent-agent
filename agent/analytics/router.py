"""统计分析相关路由"""
from fastapi import APIRouter
from pydantic import BaseModel
from typing import Optional, List
from analytics.feedback_store import feedback_store
from analytics.skill_log import skill_log_store
from analytics.tool_call_store import tool_call_store

router = APIRouter(prefix="/api/analytics", tags=["analytics"])


class FeedbackRequest(BaseModel):
    username:        str
    message:         str
    response:        str
    rating:          str
    response_time:   Optional[float] = None
    tools_used:      List[str]       = []
    skill_triggered: Optional[str]   = None
    request_id:      Optional[str]   = None


# ── 反馈接口 ──────────────────────────────────────────────

@router.post("/feedback")
async def add_feedback(req: FeedbackRequest):
    record = feedback_store.add(req.username, req.dict())
    return {"success": True, "id": record["id"]}


@router.get("/stats/{username}")
async def get_stats(username: str):
    stats = feedback_store.get_stats(username)
    return {"success": True, "stats": stats}


@router.get("/records/{username}")
async def get_records(
    username: str,
    limit:    int            = 50,
    rating:   Optional[str] = None
):
    records = feedback_store.list_records(username, limit=limit, rating=rating)
    return {"success": True, "records": records, "count": len(records)}


# ── Skill 日志接口 ────────────────────────────────────────

@router.get("/skill-logs/{username}")
async def get_skill_logs(
    username:   str,
    limit:      int            = 100,
    skill_name: Optional[str] = None
):
    records = skill_log_store.list_records(username, limit=limit, skill_name=skill_name)
    return {"success": True, "records": records, "count": len(records)}


@router.get("/skill-stats/{username}")
async def get_skill_stats(username: str):
    stats = skill_log_store.get_stats(username)
    return {"success": True, "stats": stats}


# ── 工具调用历史接口 ──────────────────────────────────────

@router.get("/tool-calls")
async def get_tool_calls(
    limit:     int            = 50,
    tool_name: Optional[str] = None,
    success:   Optional[bool] = None
):
    records = tool_call_store.list_calls(limit=limit, tool_name=tool_name, success=success)
    return {"success": True, "records": records, "count": len(records)}


@router.get("/tool-stats")
async def get_tool_stats():
    stats = tool_call_store.get_stats()
    return {"success": True, "stats": stats}