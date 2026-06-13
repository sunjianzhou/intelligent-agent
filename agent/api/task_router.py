"""任务调度 API（/api/tasks/*）。"""
import asyncio
from datetime import datetime as _dt
from typing import Optional

from fastapi import APIRouter, Request
from loguru import logger
from pydantic import BaseModel

import api.state as _state

router = APIRouter(prefix="/api/tasks")


class CreateTaskRequest(BaseModel):
    name: str
    description: str = ""
    action: str = "log"
    args: dict = {}
    schedule_type: str = "delay"
    delay_seconds: int = 60
    interval_seconds: int = 300
    cron_expr: Optional[str] = None
    run_at: Optional[str] = None
    max_runs: Optional[int] = None
    tags: list = []


@router.get("/list")
async def list_tasks(status: Optional[str] = None, limit: int = 50):
    if not _state.agent or not _state.agent.task_manager:
        return {"tasks": [], "count": 0}
    tasks = _state.agent.task_manager.list_tasks(status=status, limit=limit)
    return {"tasks": tasks, "count": len(tasks)}


@router.post("/create")
async def create_task(request: CreateTaskRequest, http_req: Request):
    if not _state.agent or not _state.agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    try:
        scheduler = _state.agent.task_manager.scheduler
        user_id = getattr(http_req.state, "user_id", "java-service")
        args = dict(request.args)
        if request.action == "log" and "message" not in args:
            args["message"] = request.name
        if request.action == "llm_generate" and "user_id" not in args:
            args["user_id"] = user_id
        kwargs = dict(
            name=request.name, action=request.action,
            args=args, description=request.description, tags=request.tags,
        )
        if request.schedule_type == "delay":
            task = scheduler.create_task(schedule_type="delay",
                                         delay_seconds=request.delay_seconds, **kwargs)
        elif request.schedule_type == "interval":
            task = scheduler.create_task(schedule_type="interval",
                                         interval_seconds=request.interval_seconds, **kwargs)
        elif request.schedule_type == "datetime":
            run_at = _dt.fromisoformat(request.run_at) if request.run_at else _dt.now()
            task = scheduler.create_task(schedule_type="datetime", run_at=run_at, **kwargs)
        elif request.schedule_type == "cron":
            if not request.cron_expr:
                return {"success": False, "message": "Cron 任务需要 cron_expr 参数"}
            task = scheduler.create_task(schedule_type="cron",
                                         cron_expr=request.cron_expr, **kwargs)
        else:
            task = scheduler.create_task(schedule_type="immediate", **kwargs)
        return {"success": True, "task": task.to_dict()}
    except Exception as e:
        logger.error(f"创建任务失败: {e}")
        return {"success": False, "message": str(e)}


@router.delete("/{task_id}")
async def delete_task(task_id: str):
    if not _state.agent or not _state.agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    return _state.agent.task_manager.delete_task(task_id)


@router.patch("/{task_id}")
async def update_task(task_id: str, body: dict):
    if not _state.agent or not _state.agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    scheduler = _state.agent.task_manager.scheduler
    kwargs = dict(body)
    top_level_args = {k: kwargs.pop(k) for k in ("message", "role") if k in kwargs}
    if top_level_args:
        kwargs.setdefault("args", {})
        kwargs["args"].update(top_level_args)
    task = scheduler.update_task(task_id, **kwargs)
    return {"success": True, "task": task.to_dict()} if task else {"success": False, "message": "任务不存在"}


@router.post("/{task_id}/cancel")
async def cancel_task(task_id: str):
    if not _state.agent or not _state.agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    return _state.agent.task_manager.cancel_task(task_id)


@router.post("/{task_id}/execute")
async def execute_task_now(task_id: str):
    if not _state.agent or not _state.agent.task_manager:
        return {"success": False, "message": "调度器未初始化"}
    scheduler = _state.agent.task_manager.scheduler
    task = scheduler.get_task(task_id)
    if not task:
        return {"success": False, "message": "任务不存在"}
    from scheduler.simple_models import SimpleTaskStatus
    if task.status == SimpleTaskStatus.RUNNING:
        return {"success": False, "message": "任务正在执行中，请等待当前轮次完成"}
    asyncio.create_task(scheduler.execute_task(task_id))
    return {"success": True, "message": "任务已触发，后台执行中"}


@router.get("/stats")
async def task_stats():
    if not _state.agent or not _state.agent.task_manager:
        return {"total_tasks": 0, "scheduler_running": False}
    return _state.agent.task_manager.get_stats()


@router.get("/actions")
async def list_actions():
    if not _state.agent or not _state.agent.task_manager:
        return {"actions": ["log", "llm_generate"]}
    return {"actions": list(_state.agent.task_manager.scheduler.actions.keys())}
