"""项目规格 / 上下文 / 任务分解 API（/api/project/*）。"""
import asyncio
import json as _json
import uuid as _uuid
from datetime import datetime as _dt
from typing import Optional

from fastapi import APIRouter, HTTPException
from loguru import logger
from pydantic import BaseModel

import api.state as _state
from services.base_provider import LLMConfig, ChatMessage

router = APIRouter(prefix="/api/project")


class ProjectSpecRequest(BaseModel):
    project_id: str
    content: str
    version: int = 1


class ProjectContextExtractRequest(BaseModel):
    project_id: str
    user_id: str = "default"


class TaskDecomposeRequest(BaseModel):
    project_id: str
    task_description: Optional[str] = None


@router.put("/spec")
async def put_project_spec(request: ProjectSpecRequest):
    if not _state.agent:
        raise HTTPException(status_code=503, detail="Agent 未初始化")
    try:
        lt = _state.agent.memory.long_term
        if lt.collection is None:
            raise HTTPException(status_code=503, detail="ChromaDB 不可用")
        spec_id = f"spec_{request.project_id}_v{request.version}"
        try:
            existing = lt.collection.get(
                where={"project_id": request.project_id, "type": "project_spec"},
                include=["metadatas"],
            )
            if existing and existing.get("ids"):
                lt.collection.delete(ids=existing["ids"])
        except Exception:
            pass
        lt.collection.add(
            ids=[spec_id],
            documents=[request.content],
            metadatas=[{
                "type":       "project_spec",
                "project_id": request.project_id,
                "version":    request.version,
            }],
        )
        return {"project_id": request.project_id, "version": request.version, "synced": True}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"保存规格失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/spec")
async def get_project_spec(project_id: str):
    if not _state.agent:
        return {"project_id": project_id, "content": "", "version": 0}
    try:
        lt = _state.agent.memory.long_term
        if lt.collection is None:
            return {"project_id": project_id, "content": "", "version": 0}
        results = lt.collection.get(
            where={"project_id": project_id, "type": "project_spec"},
            include=["documents", "metadatas"],
        )
        if not results or not results.get("ids"):
            return {"project_id": project_id, "content": "", "version": 0}
        pairs = list(zip(results["documents"], results["metadatas"]))
        pairs.sort(key=lambda x: x[1].get("version", 0), reverse=True)
        doc, meta = pairs[0]
        return {"project_id": project_id, "content": doc, "version": meta.get("version", 1)}
    except Exception as e:
        logger.warning(f"读取规格失败: {e}")
        return {"project_id": project_id, "content": "", "version": 0}


@router.post("/context/extract")
async def extract_project_context(request: ProjectContextExtractRequest):
    if not _state.agent:
        raise HTTPException(status_code=503, detail="Agent 未初始化")
    try:
        items = _state.agent.memory.short_term.list(
            limit=_state.agent._context_extractor.interval * 4
        )
        stored = await _state.agent._context_extractor.extract(
            project_id=request.project_id,
            user_id=request.user_id,
            short_term_items=items,
            call_model_fn=_state.agent._call_model,
            chroma_client=_state.agent.memory.long_term.vector_db,
            embedding_model=_state.agent.memory.long_term.embedding_model,
            persist_dir=_state.agent.memory.long_term.persist_dir,
        )
        version = _state.agent._context_extractor._turn_counts.get(
            f"{request.user_id}:{request.project_id}", 0
        )
        return {"extracted": stored, "version": version}
    except Exception as e:
        logger.error(f"上下文提取失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/context")
async def get_project_context(project_id: str, query: str = "", limit: int = 5):
    if not _state.agent:
        return {"nuggets": []}
    nuggets = await _state.agent._get_project_context(project_id, query or "general", limit)
    return {"project_id": project_id, "nuggets": nuggets}


@router.post("/tasks/decompose")
async def decompose_project_tasks(request: TaskDecomposeRequest):
    if not _state.agent:
        raise HTTPException(status_code=503, detail="Agent 未初始化")
    task_desc = request.task_description
    if not task_desc:
        try:
            lt = _state.agent.memory.long_term
            if lt.collection is not None:
                spec_res = lt.collection.get(
                    where={"project_id": request.project_id, "type": "project_spec"},
                    include=["documents"],
                )
                if spec_res and spec_res.get("documents"):
                    task_desc = f"（基于项目规格文档）\n{spec_res['documents'][0][:800]}"
        except Exception:
            pass
    if not task_desc:
        task_desc = "请根据项目目标合理规划任务"

    prompt = (
        "请分析以下任务并将其分解为子任务树，以JSON格式输出。"
        "要求：最多3层，每层最多5个节点，每个节点包含 id(task_xxx格式)、title、status(值固定为pending)、subtasks(数组)。"
        "只输出JSON，格式：{\"task_tree\": [{\"id\":\"task_001\",\"title\":\"...\","
        "\"status\":\"pending\",\"subtasks\":[],\"notes\":\"\","
        "\"created_at\":\"now\",\"completed_at\":null}]}\n\n"
        f"任务描述：{task_desc}"
    )
    try:
        decompose_msgs = [
            {"role": "system", "content": "你是任务分解助手，只输出JSON。"},
            {"role": "user",   "content": prompt},
        ]
        _fb = _state._fallback_ollama
        raw = None
        if _fb is not None:
            loop = asyncio.get_running_loop()
            _cfg = LLMConfig(temperature=0.3, max_tokens=2048)
            _chat_msgs = [ChatMessage(role=m["role"], content=m["content"]) for m in decompose_msgs]
            try:
                _resp = await asyncio.wait_for(
                    loop.run_in_executor(None, lambda: _fb.chat(_chat_msgs, _cfg)),
                    timeout=90,
                )
                if _resp.success:
                    raw = _resp.content
                else:
                    logger.warning(f"decompose Ollama 失败: {_resp.error}")
            except Exception as _fb_err:
                logger.warning(f"decompose Ollama 异常: {_fb_err}")
        if not raw:
            logger.info("decompose: 本地 Ollama 不可用，使用 agent provider")
            raw = await _state.agent._call_model(decompose_msgs, fallback_timeout=90)

        clean = raw.strip().replace("```json", "").replace("```", "").strip()
        parsed = _json.loads(clean)
        task_tree = parsed.get("task_tree", [])

        def _fill_ids(tasks, depth=0):
            for t in tasks:
                if not t.get("id"):
                    t["id"] = f"task_{_uuid.uuid4().hex[:8]}"
                t.setdefault("status",       "pending")
                t.setdefault("notes",        "")
                t.setdefault("created_at",   _dt.now().isoformat())
                t.setdefault("completed_at", None)
                if depth < 3:
                    _fill_ids(t.get("subtasks", []), depth + 1)

        _fill_ids(task_tree)
        return {
            "project_id": request.project_id,
            "task_tree":  {"root_tasks": task_tree, "auto_decompose": True},
        }
    except _json.JSONDecodeError:
        raise HTTPException(status_code=422, detail="LLM 返回格式无效，请重试")
    except Exception as e:
        logger.error(f"任务分解失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/tasks")
async def get_project_tasks(project_id: str):
    return {"project_id": project_id, "task_tree": [], "note": "tasks are client-owned"}
