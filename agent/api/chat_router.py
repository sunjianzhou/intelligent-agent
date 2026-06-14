"""聊天推理 API（POST /api/chat, POST /api/chat/stream）。"""
import asyncio
import json as _json
import traceback
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse as _StreamingResponse
from loguru import logger
from pydantic import BaseModel

import api.state as _state
from api.conversations_router import append_messages as _append_messages
from api.roles_router import (
    _user_active_roles as _user_active_roles_state,
    get_role_manager as _get_role_manager,
)
from config.settings import settings
from personas.prompt_builder import PromptBuilder as _PromptBuilder
from services.base_provider import ChatMessage, LLMConfig, MULTIMODAL_IMAGE_PREFIX

router = APIRouter()

_prompt_builder = _PromptBuilder()


async def _get_user_role_persona_content(user_id: str, query: str) -> str | None:
    role_id = _user_active_roles_state.get(user_id)
    if not role_id:
        return None
    try:
        rm = _get_role_manager()
        loop = asyncio.get_event_loop()
        ctx = await loop.run_in_executor(None, rm.get_role_context, role_id, query)
        if not ctx:
            return None
        return _prompt_builder.build_system_prompt(ctx)
    except Exception as _e:
        logger.warning(f"构建角色提示失败 [user={user_id}, role={role_id}]: {_e}")
        return None


class ChatRequest(BaseModel):
    message: str
    use_tools: bool = True
    use_memory: bool = True
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    top_p: Optional[float] = None
    project_id: Optional[str] = None
    pending_tasks: Optional[List[Dict[str, Any]]] = None
    session_id: Optional[str] = None
    image_base64: Optional[str] = None  # 前端传来的单张图片 base64（不含 data URL 前缀）


@router.post("/api/chat")
async def chat(request: ChatRequest, http_req: Request):
    user_id = getattr(http_req.state, "user_id", "default")
    if not _state._user_rate_limiter.is_allowed(user_id):
        return JSONResponse(
            status_code=429,
            content={"success": False, "message": "请求过于频繁，请稍后重试"},
        )
    logger.info(f"收到聊天请求 [user={user_id}, len={len(request.message)}]")

    user_provider = _state._get_user_provider(user_id)
    user_persona_content = await _get_user_role_persona_content(user_id, request.message)

    async with _state._inference_slot():
        if _state.agent and _state.OLLAMA_AVAILABLE:
            try:
                result = await _state.agent.chat(
                    message=request.message,
                    use_tools=request.use_tools,
                    use_memory=request.use_memory,
                    user_id=user_id,
                    provider_override=user_provider,
                    persona_override=user_persona_content,
                    project_id=request.project_id,
                    pending_tasks=request.pending_tasks,
                    image_base64=request.image_base64,
                )
                _now = datetime.now().isoformat()
                _sid = request.session_id or user_id
                _user_msg: Dict[str, Any] = {"role": "user", "content": request.message, "timestamp": _now}
                if request.image_base64:
                    _user_msg["images_b64"] = [request.image_base64]
                _append_messages(user_id, _sid, [
                    _user_msg,
                    {"role": "assistant", "content": result["content"], "timestamp": _now},
                ])
                return {
                    "response":         result["content"],
                    "tool_calls":       result["tool_calls"],
                    "model":            user_provider.current_model if user_provider else "",
                    "agent_mode":       True,
                    "ollama_available": _state.OLLAMA_AVAILABLE,
                }
            except Exception as e:
                logger.error(f"Agent 调用异常: {e}\n{traceback.format_exc()}")

        if user_provider and _state.OLLAMA_AVAILABLE:
            try:
                config = LLMConfig(
                    temperature=request.temperature or settings.ollama_temperature,
                    max_tokens=request.max_tokens or settings.ollama_max_tokens,
                    top_p=request.top_p or settings.ollama_top_p,
                )
                _img_prefix = MULTIMODAL_IMAGE_PREFIX if request.image_base64 else ""
                messages = [
                    ChatMessage(role="system", content=(
                        f"你是一个有帮助的AI助手，请用中文回答。"
                        f"当前时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
                    )),
                    ChatMessage(
                        role="user",
                        content=_img_prefix + request.message,
                        images=[request.image_base64] if request.image_base64 else None,
                    ),
                ]
                resp = user_provider.chat(messages, config)
                return {
                    "response":         resp.content,
                    "tool_calls":       [],
                    "model":            resp.model,
                    "agent_mode":       False,
                    "ollama_available": _state.OLLAMA_AVAILABLE,
                }
            except Exception as e:
                logger.error(f"Provider 直连异常: {e}")

        return {
            "response":         "服务暂时不可用，请检查 Ollama 服务状态。",
            "tool_calls":       [],
            "model":            "unavailable",
            "agent_mode":       False,
            "ollama_available": _state.OLLAMA_AVAILABLE,
        }


@router.post("/api/chat/stream")
async def chat_stream_endpoint(request: ChatRequest, http_req: Request):
    user_id = getattr(http_req.state, "user_id", "default")
    if not _state._user_rate_limiter.is_allowed(user_id):
        return JSONResponse(
            status_code=429,
            content={"success": False, "message": "请求过于频繁，请稍后重试"},
        )
    user_provider = _state._get_user_provider(user_id)
    user_persona_content = await _get_user_role_persona_content(user_id, request.message)

    if not _state.OLLAMA_AVAILABLE:
        async def _err():
            yield f"data: {_json.dumps({'type': 'error', 'data': 'Provider 不可用'}, ensure_ascii=False)}\n\n"
        return _StreamingResponse(_err(), media_type="text/event-stream")

    cancel_ev = asyncio.Event()
    _session_id = request.session_id or user_id

    async def generate():
        try:
            async with _state._inference_slot():
                if _state.agent:
                    _full_reply = []
                    async for event_type, data in _state.agent.chat_stream(
                            message=request.message,
                            use_tools=request.use_tools,
                            use_memory=request.use_memory,
                            cancel_event=cancel_ev,
                            user_id=user_id,
                            provider_override=user_provider,
                            persona_override=user_persona_content,
                            project_id=request.project_id,
                            pending_tasks=request.pending_tasks,
                            image_base64=request.image_base64,
                    ):
                        yield f"data: {_json.dumps({'type': event_type, 'data': data}, ensure_ascii=False)}\n\n"
                        if event_type == "done" and isinstance(data, dict):
                            _full_reply.append(data.get("content", ""))
                    if _full_reply:
                        _now = datetime.now().isoformat()
                        _stream_user_msg: Dict[str, Any] = {
                            "role": "user", "content": request.message, "timestamp": _now
                        }
                        if request.image_base64:
                            _stream_user_msg["images_b64"] = [request.image_base64]
                        _append_messages(user_id, _session_id, [
                            _stream_user_msg,
                            {"role": "assistant", "content": _full_reply[0], "timestamp": _now},
                        ])

                elif user_provider:
                    logger.warning("Agent 不可用，降级为 Provider 流式直连")
                    config = LLMConfig(
                        temperature=request.temperature or settings.ollama_temperature,
                        max_tokens=request.max_tokens or settings.ollama_max_tokens,
                        top_p=request.top_p or settings.ollama_top_p,
                    )
                    _img_prefix_s = MULTIMODAL_IMAGE_PREFIX if request.image_base64 else ""
                    chat_messages = [
                        ChatMessage(role="system", content=(
                            f"你是一个有帮助的AI助手，请用中文回答。"
                            f"当前时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
                        )),
                        ChatMessage(
                            role="user",
                            content=_img_prefix_s + request.message,
                            images=[request.image_base64] if request.image_base64 else None,
                        ),
                    ]
                    loop = asyncio.get_running_loop()
                    queue: asyncio.Queue = asyncio.Queue(maxsize=100)
                    DONE = object()

                    def _producer():
                        try:
                            for token in user_provider.chat_stream_generator(chat_messages, config):
                                if cancel_ev.is_set():
                                    break
                                asyncio.run_coroutine_threadsafe(queue.put(token), loop).result(timeout=5)
                        except Exception as _e:
                            logger.warning(f"Provider 流式中断: {_e}")
                        finally:
                            asyncio.run_coroutine_threadsafe(queue.put(DONE), loop).result(timeout=5)

                    loop.run_in_executor(None, _producer)
                    full = ""
                    while True:
                        item = await queue.get()
                        if item is DONE:
                            break
                        full += item
                        yield f"data: {_json.dumps({'type': 'token', 'data': item}, ensure_ascii=False)}\n\n"
                    yield f"data: {_json.dumps({'type': 'done', 'data': {'content': full}}, ensure_ascii=False)}\n\n"

                else:
                    yield f"data: {_json.dumps({'type': 'error', 'data': '服务暂时不可用'}, ensure_ascii=False)}\n\n"

        except Exception as e:
            from fastapi import HTTPException
            if isinstance(e, HTTPException):
                yield f"data: {_json.dumps({'type': 'error', 'data': e.detail}, ensure_ascii=False)}\n\n"
            elif isinstance(e, (asyncio.CancelledError, GeneratorExit)):
                cancel_ev.set()
            else:
                logger.error(f"流式生成异常: {e}")
                yield f"data: {_json.dumps({'type': 'error', 'data': str(e)}, ensure_ascii=False)}\n\n"
        finally:
            cancel_ev.set()

    return _StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
