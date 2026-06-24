"""ConversationFlowMixin — 消息构建、chat、chat_stream 主流程。

作为 Mixin 基类，通过 Python MRO 拼接到 IntelligentAgent，
所有 self.* 属性在运行时由 IntelligentAgent.__init__ 提供。
"""
import asyncio
import json
import re
import uuid
from datetime import datetime
from typing import Optional, List, Dict, Any

from loguru import logger

from config.settings import settings
from services.base_provider import LLMConfig, ChatMessage, MULTIMODAL_IMAGE_PREFIX
from api.metrics import cache_hits_total, cache_misses_total
from core._context_vars import (
    _request_provider_ctx,
    _request_persona_ctx,
    _last_message_vec_ctx,
    _request_image_b64_ctx,
    _request_channel_ctx,
)
from prompts.prompt_manager import prompt_manager

_SPEC_REVIEW_EVERY = 5  # inject spec reminder every N turns per project


class ConversationFlowMixin:
    """对话主流程：消息构建 + ReAct chat/chat_stream。"""

    # ═══════════════════════════════════════════════════════════════
    # 系统提示词
    # ═══════════════════════════════════════════════════════════════

    @property
    def system_prompt(self) -> str:
        """灵魂层驱动的 system prompt：SOUL→USER→MEMORY→HEARTBEAT→persona→whisper→tool_overlay。"""
        _, eff_model = self._get_eff_provider()
        persona_content = self._get_eff_persona()
        role_ctx = self._get_role_ctx_for_prompt(persona_content)
        tool_overlay = prompt_manager.get_overlay(eff_model or "")
        return self.prompt_builder.build(
            soul=self.soul,
            role_ctx=role_ctx,
            tool_overlay=tool_overlay,
            channel=self._get_eff_channel(),
        )

    def _get_role_ctx_for_prompt(
        self, persona_content: Optional[str]
    ) -> Optional[Dict[str, Any]]:
        """有激活角色时返回 role_ctx，否则 None。多角色接入时扩展此方法。"""
        if not persona_content:
            return None
        return {"persona_md": persona_content}

    # ═══════════════════════════════════════════════════════════════
    # 消息构建
    # ═══════════════════════════════════════════════════════════════

    async def _build_messages_async(self, message: str, use_memory: bool,
                                    user_id: str = "default",
                                    project_id: Optional[str] = None,
                                    pending_tasks: Optional[List[Dict[str, Any]]] = None,
                                    image_base64: Optional[str] = None,
                                    message_id: Optional[str] = None,
                                    scene_chat_type: Optional[str] = None,
                                    scene_mentioned: bool = False) -> List[Dict[str, str]]:
        """异步版 _build_messages：超预算时先尝试 LLM 摘要压缩，再兜底截断。"""
        if use_memory:
            self.memory.store_conversation(
                "user", message, user_id=user_id,
                metadata={"message_id": message_id} if message_id else None,
            )
            self._encode_message_for_intent(message)

        msgs = [{"role": "system", "content": self.system_prompt}]

        if use_memory:
            ctx = self.memory.build_context(
                query=message,
                current_user_message=message,
                recent_conversations=10,
                relevant_memories=3,
                user_id=user_id,
            )
            history_pairs = ctx["recent_conversations"]
            if history_pairs:
                history_text = "\n".join(
                    f"{'用户' if m.metadata.get('role') == 'user' else '助手'}: "
                    f"{m.content[:300]}{'...' if len(m.content) > 300 else ''}"
                    for m in history_pairs
                )
                msgs.append({
                    "role": "system",
                    "content": (
                        "以下是与当前用户的历史对话，其中包含用户透露的个人信息，"
                        "回答问题时必须参考（如用户姓名、偏好等）：\n"
                        f"{history_text}"
                    )
                })
            relevant = ctx["relevant_knowledge"]
            if relevant:
                mem_text = "\n".join(
                    f"[{r.memory.metadata.get('category', '?')}] {r.memory.content}"
                    for r in relevant
                )
                msgs.append({"role": "system", "content": f"【相关背景知识】:\n{mem_text}"})

        # 注入项目上下文（Phase 1 — 上下文持久化）
        if project_id:
            nuggets = await self._get_project_context(project_id, message)
            if nuggets:
                nugget_text = "\n".join(f"- {n}" for n in nuggets)
                msgs.append({
                    "role": "system",
                    "content": (
                        f"[PROJECT CONTEXT] 以下是本项目的历史关键决策和状态，"
                        f"请在回答时参考：\n{nugget_text}"
                    ),
                })

        # 注入项目规格（Phase 2 — 规格驱动开发）
        if project_id and user_id:
            spec_content = await self._inject_spec_if_due(user_id, project_id)
            if spec_content:
                msgs.append({
                    "role": "system",
                    "content": (
                        "[SPEC] 以下是本项目的原始需求规格文档，"
                        "请确保你的回答符合这些规格要求：\n"
                        f"{spec_content}"
                    ),
                })

        # 注入任务列表（Phase 3 — 自主任务分解）
        if project_id and pending_tasks:
            def _fmt_task(t: Dict[str, Any], depth: int = 0) -> str:
                indent = "  " * depth
                status_map = {"pending": "待处理", "in_progress": "进行中",
                              "done": "已完成", "blocked": "已阻塞"}
                status = status_map.get(t.get("status", "pending"), t.get("status", ""))
                tid = t.get("id", "")
                line = f"{indent}- id={tid} [{status}] {t.get('title', '')}"
                result = [line]
                for sub in t.get("subtasks", []):
                    result.append(_fmt_task(sub, depth + 1))
                return "\n".join(result)

            active = [t for t in pending_tasks if t.get("status") in ("pending", "in_progress")]
            if active:
                task_text = "\n".join(_fmt_task(t) for t in active)
                msgs.append({
                    "role": "system",
                    "content": (
                        "[TASK LIST] 以下是本项目当前待处理或进行中的任务（id 字段是唯一标识）：\n"
                        f"{task_text}"
                    ),
                })

        # 任务状态 sentinel 指令（Phase 3 — 自主任务分解）
        if project_id:
            msgs.append({
                "role": "system",
                "content": (
                    "[PROJECT RULES] 当你在本次对话中完成了某项分配给你的项目任务，"
                    "请在回答最末尾单独一行写 [TASK_DONE:<task_id>]，"
                    "其中 <task_id> 替换为任务列表中对应任务的 id 值（例如 [TASK_DONE:task-1748123-abc]）。"
                    "当某项任务因缺少信息或外部依赖而无法继续时，写 [TASK_BLOCKED:<task_id>]。"
                    "若无法确定具体任务 id，写 [TASK_DONE] 或 [TASK_BLOCKED]（不带 id）。"
                    "这两个标记会被系统自动处理并从显示内容中移除，不会显示给用户。"
                ),
            })

        # 群聊场景规则（飞书等多人会话）：未被 @ 时默认沉默，避免刷屏打扰
        if scene_chat_type == "group":
            msgs.append({
                "role": "system",
                "content": (
                    "[GROUP SCENE] 当前消息来自一个多人群聊，你是参与者之一，不是代言人。"
                    + ("你被直接 @ 提及或被问了问题。" if scene_mentioned else
                       "你没有被 @ 提及。除非消息中有需要你纠正的明显错误、"
                       "明确向你提的问题，或被要求做总结，否则不要主动发言。")
                    + "若判断当前不需要你发言，将完整回复内容替换为唯一一行 NO_REPLY"
                      "（不要附加任何其他文字、标点或解释）；其余情况按正常风格作答。"
                ),
            })

        # 多模态：若有图片，在用户消息中加提示文字，并将 images 存入消息 dict 供后续转为 ChatMessage 时使用
        if image_base64:
            msgs.append({
                "role": "user",
                "content": MULTIMODAL_IMAGE_PREFIX + message,
                "_images": [image_base64],
            })
        else:
            msgs.append({"role": "user", "content": message})

        max_context = getattr(settings, 'max_context_tokens', 0)
        if max_context > 0:
            current_tokens = self._estimate_messages_tokens(msgs)
            if current_tokens > max_context:
                msgs = await self._compress_context(msgs, max_context)

        return msgs

    # ═══════════════════════════════════════════════════════════════
    # 项目上下文（上下文持久化 Phase 1 & 2）
    # ═══════════════════════════════════════════════════════════════

    async def _get_project_context(self, project_id: str, query: str,
                                   limit: int = 5) -> List[str]:
        """Query the per-project ChromaDB collection for relevant context nuggets."""
        collection_name = f"project_{project_id.replace('-', '_')}_context"
        try:
            chroma_client = self.memory.long_term.vector_db
            if chroma_client is None:
                return []
            try:
                collection = chroma_client.get_collection(name=collection_name)
            except Exception:
                return []  # Collection doesn't exist yet
            results = collection.query(
                query_texts=[query],
                n_results=limit,
                include=["documents", "distances"],
            )
            docs = results.get("documents", [[]])[0]
            dists = results.get("distances", [[]])[0]
            # Filter by minimum similarity (cosine distance threshold: dist < 1.4 ≈ sim > 0.3)
            return [doc for doc, dist in zip(docs, dists) if dist < 1.4]
        except Exception as e:
            logger.warning(f"_get_project_context 查询失败 (project={project_id}): {e}")
            return []

    async def _inject_spec_if_due(self, user_id: str, project_id: str) -> Optional[str]:
        """Return spec content for periodic injection (every _SPEC_REVIEW_EVERY turns), else None."""
        key = f"{user_id}:{project_id}"
        self._spec_turn_counts[key] = self._spec_turn_counts.get(key, 0) + 1
        if self._spec_turn_counts[key] < _SPEC_REVIEW_EVERY:
            return None
        self._spec_turn_counts[key] = 0
        try:
            collection_name = f"project_{project_id.replace('-', '_')}_spec"
            collection = self.memory.long_term.vector_db.get_collection(name=collection_name)
            results = collection.get(limit=1, include=["documents"])
            docs = results.get("documents", [])
            return docs[0] if docs else None
        except Exception as e:
            logger.debug(f"_inject_spec_if_due: no spec for project={project_id}: {e}")
            return None

    async def _maybe_extract_context(self, user_id: str, project_id: str) -> None:
        """Fire-and-forget context extraction task."""
        if not self._context_extractor.record_turn(user_id, project_id):
            return
        try:
            items = self.memory.short_term.list(limit=self._context_extractor.interval * 4)
            await self._context_extractor.extract(
                project_id=project_id,
                user_id=user_id,
                short_term_items=items,
                call_model_fn=self._call_model,
                chroma_client=self.memory.long_term.vector_db,
                embedding_model=self.memory.long_term.embedding_model,
                persist_dir=self.memory.long_term.persist_dir,
            )
        except Exception as exc:
            logger.warning(f"_maybe_extract_context 异常 (user={user_id}, project={project_id}): {exc}")

    # ═══════════════════════════════════════════════════════════════
    # ReAct 主循环 — chat()
    # ═══════════════════════════════════════════════════════════════

    async def chat(self, message: str,
                   use_tools: bool = True,
                   use_memory: bool = True,
                   max_iterations: int = 5,
                   user_id: str = "default",
                   provider_override=None,
                   persona_override: Optional[str] = None,
                   project_id: Optional[str] = None,
                   pending_tasks: Optional[List[Dict[str, Any]]] = None,
                   skip_cache: bool = False,
                   image_base64: Optional[str] = None,
                   message_id: Optional[str] = None,
                   assistant_message_id: Optional[str] = None,
                   channel: str = "web",
                   scene_chat_type: Optional[str] = None,
                   scene_mentioned: bool = False,
                   allowed_tool_categories: Optional[List[str]] = None) -> dict:
        """非流式聊天（ReAct 循环）。
        provider_override: 若传入，则本次请求使用该 provider（per-user 隔离）。
        persona_override:  若传入，则本次请求使用该角色内容（per-user 角色隔离）。
        skip_cache:        True 时跳过 L1/L2 缓存查询（适用于需要每次新鲜结果的场景，如定时 AI 生成任务）。
        channel:           请求来源渠道（"web"/"feishu_im"/...），决定 system prompt 是否注入私密档案段。
        scene_chat_type:   多人会话场景标记（如飞书 "group"/"p2p"），group 时注入静默规则。
        scene_mentioned:   group 场景下是否被显式 @ 提及。
        allowed_tool_categories: 非 None 时硬限制本次对话可用的工具分类（代码层强制），
                          用于受限的内部自动化场景（如记忆归并只允许 file 分类）。
        """
        if provider_override is not None:
            _request_provider_ctx.set(provider_override)
        if persona_override is not None:
            _request_persona_ctx.set(persona_override)
        _request_image_b64_ctx.set(image_base64)
        _request_channel_ctx.set(channel)

        _trace_id = str(uuid.uuid4())[:8]
        _, eff_model = self._get_eff_provider()
        logger.debug(json.dumps({
            "event": "chat_start", "trace_id": _trace_id,
            "use_tools": use_tools, "msg_preview": message[:60],
        }, ensure_ascii=False))

        # ── 缓存命中检查（仅纯知识类：use_tools=False，且未要求跳过缓存）────────────
        if not use_tools and not skip_cache:
            # L1：精确匹配
            cached = self._cache_get(message)
            if cached is not None:
                cache_hits_total.labels(level="L1").inc()
                logger.debug(f"[L1-cache] 命中: {message[:40]}")
                return {"content": cached, "tool_calls": [], "_from_cache": "L1"}
            cache_misses_total.labels(level="L1").inc()

            # L2：语义相似（按模型过滤，防止跨模型缓存污染）
            if self._semantic_cache is not None:
                sem_hit = self._semantic_cache.get(message, model=eff_model)
                if sem_hit is not None:
                    cache_hits_total.labels(level="L2").inc()
                    self._cache_put(message, sem_hit)   # 回填 L1
                    return {"content": sem_hit, "tool_calls": [], "_from_cache": "L2"}
                cache_misses_total.labels(level="L2").inc()

        # 重置请求级 embedding 缓存（per-request ContextVar，自动隔离并发请求）
        _last_message_vec_ctx.set(None)

        messages = await self._build_messages_async(message, use_memory, user_id=user_id,
                                                     project_id=project_id,
                                                     pending_tasks=pending_tasks,
                                                     image_base64=image_base64,
                                                     message_id=message_id,
                                                     scene_chat_type=scene_chat_type,
                                                     scene_mentioned=scene_mentioned)
        tool_call_log = []

        if not use_tools:
            full_response = await self._call_model(messages)
            if use_memory and full_response:
                # 短期记忆存储完整响应（in-process deque，无存储压力）
                # 长文本在 _build_messages 注入时截取前 300 字符，平衡上下文长度
                self.memory.store_conversation(
                    "assistant", full_response, user_id=user_id,
                    metadata={"message_id": assistant_message_id} if assistant_message_id else None,
                )
                asyncio.create_task(self._maybe_distill(user_id))
                asyncio.create_task(self._maybe_summarize(user_id))
                if project_id:
                    asyncio.create_task(self._maybe_extract_context(user_id, project_id))
            # 写入 L1 + L2 缓存（skip_cache=True 时跳过写入，如定时 llm_generate 任务）
            if (not skip_cache
                    and full_response
                    and not self._is_error_response(full_response)):
                self._cache_put(message, full_response)
                if self._semantic_cache is not None:
                    self._semantic_cache.put(message, full_response, model=eff_model)
            return {"content": full_response, "tool_calls": []}

        # ── ReAct 工具循环 ──────────────────────────────
        executed_tool_keys: set = set()
        response_set = False
        full_response = ""

        for i in range(max_iterations):
            result = await self._call_model_with_tools(
                messages,
                intent_message=message if i == 0 else "",
                _trace_id=f"{_trace_id}:{i}",
                allowed_tool_categories=allowed_tool_categories,
            )

            tool_calls = result.get("tool_calls", [])
            content = result.get("content", "")

            if not tool_calls:
                full_response = content
                response_set = True
                break

            messages.append({"role": "assistant", "content": content})

            round_log, should_abort = await self._execute_tool_round(
                messages, tool_calls, executed_tool_keys
            )
            tool_call_log.extend(round_log)

            if should_abort:
                messages.append({
                    "role": "system",
                    "content": "工具已执行完毕，请直接基于已有的工具结果用中文回答用户问题，不要再调用工具。"
                })
                full_response = await self._call_model(messages)
                response_set = True
                break

        # ── 工具执行后强制指定回答格式 ───────────────────
        if tool_call_log and not response_set:
            messages.append({
                "role": "system",
                "content": (
                    "工具已执行完毕，结果已在上方。"
                    "请直接用中文回答用户的问题，"
                    "不要再输出任何工具调用格式，直接给出自然语言回答。"
                )
            })

        if not response_set:
            final_messages = []
            for m in messages:
                if m["role"] == "tool":
                    final_messages.append({
                        "role": "user",
                        "content": f"[工具执行结果]\n{m['content']}\n\n请基于以上结果，用中文直接回答用户的问题。"
                    })
                else:
                    final_messages.append(m)
            full_response = await self._call_model(final_messages)

        if use_memory and full_response:
            self.memory.store_conversation(
                "assistant", full_response, user_id=user_id,
                metadata={"message_id": assistant_message_id} if assistant_message_id else None,
            )
            asyncio.create_task(self._maybe_distill(user_id))
            if project_id:
                asyncio.create_task(self._maybe_extract_context(user_id, project_id))

        return {"content": full_response, "tool_calls": tool_call_log}

    # ═══════════════════════════════════════════════════════════════
    # 任务 sentinel 解析
    # ═══════════════════════════════════════════════════════════════

    def _strip_task_sentinels(
        self, full_response: str, project_id: Optional[str]
    ) -> tuple:
        """扫描 full_response 中的所有 [TASK_DONE] / [TASK_BLOCKED] sentinel（支持多条）。

        返回 (cleaned_response, events)，events 是 [(event_type, event_data), ...] 列表。
        """
        if not project_id:
            return full_response, []
        events = []
        now_iso = datetime.now().isoformat()
        for m in re.finditer(r'\[TASK_DONE(?::([^\]]*))?\]', full_response):
            events.append(('task_update', {
                'project_id': project_id,
                'task_id':    (m.group(1) or '').strip() or None,
                'status':     'done',
                'ts':         now_iso,
            }))
        for m in re.finditer(r'\[TASK_BLOCKED(?::([^\]]*))?\]', full_response):
            events.append(('task_blocked', {
                'project_id': project_id,
                'task_id':    (m.group(1) or '').strip() or None,
                'status':     'blocked',
                'ts':         now_iso,
            }))
        if events:
            cleaned = re.sub(
                r'\[TASK_DONE(?::([^\]]*))?\]|\[TASK_BLOCKED(?::([^\]]*))?\]',
                '', full_response
            ).strip()
        else:
            cleaned = full_response
        return cleaned, events

    # ═══════════════════════════════════════════════════════════════
    # CoT 流分离器
    # ═══════════════════════════════════════════════════════════════

    async def _stream_with_cot(self, chat_messages, config, cancel_event):
        """包装 _stream_tokens_async，将 <think>…</think> 块拆分为 'thinking_chunk' 事件。

        支持跨 token 的部分标签（guard 机制：在 buffer 末尾保留可能是标签前缀的字符，
        等待后续 token 确认再 flush，避免把 '<thi' 误当正文输出）。
        没有 <think> 标签的模型（如 dolphin）不受影响，行为与原来完全相同。
        """
        _OPEN  = '<think>'
        _CLOSE = '</think>'
        _GUARD = max(len(_OPEN), len(_CLOSE)) - 1  # 末尾保留字符数

        buf      = ""
        in_think = False

        async for raw in self._stream_tokens_async(chat_messages, config, cancel_event):
            buf += raw
            while True:
                tag = _CLOSE if in_think else _OPEN
                idx = buf.find(tag)
                if idx >= 0:
                    head = buf[:idx]
                    if head:
                        yield ('thinking_chunk' if in_think else 'token'), head
                    in_think = not in_think
                    buf = buf[idx + len(tag):]
                else:
                    safe = max(0, len(buf) - _GUARD)
                    if safe:
                        yield ('thinking_chunk' if in_think else 'token'), buf[:safe]
                        buf = buf[safe:]
                    break

        if buf:
            yield ('thinking_chunk' if in_think else 'token'), buf

    # ═══════════════════════════════════════════════════════════════
    # ReAct 主循环 — chat_stream()
    # ═══════════════════════════════════════════════════════════════

    async def chat_stream(self, message: str,
                          use_tools: bool = True,
                          use_memory: bool = True,
                          max_iterations: int = 5,
                          cancel_event: Optional[asyncio.Event] = None,
                          user_id: str = "default",
                          provider_override=None,
                          persona_override: Optional[str] = None,
                          project_id: Optional[str] = None,
                          pending_tasks: Optional[List[Dict[str, Any]]] = None,
                          image_base64: Optional[str] = None,
                          message_id: Optional[str] = None,
                          assistant_message_id: Optional[str] = None,
                          channel: str = "web",
                          scene_chat_type: Optional[str] = None,
                          scene_mentioned: bool = False):
        """SSE 流式聊天（ReAct 循环 + 流式最终回答）。
        cancel_event：客户端断连时由 FastAPI 端点设置，通知底层停止生产。
        provider_override: 若传入，则本次请求使用该 provider（per-user 隔离）。
        persona_override:  若传入，则本次请求使用该角色内容（per-user 角色隔离）。
        channel:           请求来源渠道（"web"/"feishu_im"/...），决定 system prompt 是否注入私密档案段。
        scene_chat_type:   多人会话场景标记（如飞书 "group"/"p2p"），group 时注入静默规则。
        scene_mentioned:   group 场景下是否被显式 @ 提及。
        """
        if provider_override is not None:
            _request_provider_ctx.set(provider_override)
        if persona_override is not None:
            _request_persona_ctx.set(persona_override)
        _request_image_b64_ctx.set(image_base64)
        _request_channel_ctx.set(channel)

        # 重置请求级 embedding 缓存（per-request ContextVar，自动隔离并发请求）
        _last_message_vec_ctx.set(None)

        messages = await self._build_messages_async(message, use_memory, user_id=user_id,
                                                     project_id=project_id,
                                                     pending_tasks=pending_tasks,
                                                     image_base64=image_base64,
                                                     message_id=message_id,
                                                     scene_chat_type=scene_chat_type,
                                                     scene_mentioned=scene_mentioned)
        tool_call_log = []

        if not use_tools:
            config = LLMConfig(
                temperature=settings.ollama_temperature,
                max_tokens=settings.ollama_max_tokens,
                top_p=settings.ollama_top_p,
                top_k=settings.ollama_top_k,
                repeat_penalty=settings.ollama_repeat_penalty,
                num_ctx=settings.ollama_num_ctx,
            )
            chat_messages = [
                ChatMessage(role=m["role"], content=m.get("content", ""),
                            images=m.get("_images"))
                for m in messages
            ]
            full_response = ""
            async for etype, chunk in self._stream_with_cot(chat_messages, config, cancel_event):
                if etype == 'token':
                    full_response += chunk
                yield (etype, chunk)
            if use_memory and full_response:
                # 短期记忆存储完整响应（in-process deque，无存储压力）
                # 长文本在 _build_messages 注入时截取前 300 字符，平衡上下文长度
                self.memory.store_conversation(
                    "assistant", full_response, user_id=user_id,
                    metadata={"message_id": assistant_message_id} if assistant_message_id else None,
                )
                asyncio.create_task(self._maybe_distill(user_id))
                asyncio.create_task(self._maybe_summarize(user_id))
                if project_id:
                    asyncio.create_task(self._maybe_extract_context(user_id, project_id))
            # [TASK_DONE] / [TASK_BLOCKED] 检测（D1=B：全结束后扫描）
            full_response, _sentinel_events = self._strip_task_sentinels(full_response, project_id)
            for _s_type, _s_data in _sentinel_events:
                yield (_s_type, _s_data)
            yield ('done', {"content": full_response})
            return

        # ── ReAct 工具循环 ──────────────────────────────
        executed_tool_keys: set = set()
        content = ""

        for i in range(max_iterations):
            result = await self._call_model_with_tools(
                messages,
                intent_message=message if i == 0 else ""
            )
            tool_calls = result.get("tool_calls", [])
            content = result.get("content", "")

            if not tool_calls:
                break

            messages.append({"role": "assistant", "content": content})

            # 工具执行前广播每个工具的启动事件，让前端实时显示进度
            for tc in tool_calls:
                func = tc.get("function", {})
                tc_name = func.get("name", "")
                tc_args = func.get("arguments", {})
                if isinstance(tc_args, str):
                    try:
                        tc_args = json.loads(tc_args)
                    except Exception:
                        tc_args = {}
                args_str = str(tc_args)
                yield ('tool_call_start', {
                    "tool_name": tc_name,
                    "args_summary": args_str[:80] + "…" if len(args_str) > 80 else args_str,
                })

            round_log, should_abort = await self._execute_tool_round(
                messages, tool_calls, executed_tool_keys
            )
            for entry in round_log:
                yield ('tool_call', entry)
            tool_call_log.extend(round_log)

            if should_abort:
                messages.append({
                    "role": "system",
                    "content": "工具已执行完毕，请直接基于已有的工具结果用中文回答用户问题，不要再调用工具。"
                })
                break

        if tool_call_log:
            yield ('tool_calls_done', tool_call_log)

        # ── 流式最终回答 ──────────────────────────────────
        # 文本工具模式优化：若首轮无工具调用，直接复用非流式返回的内容，
        # 避免对 dolphin 等模型进行第二次 LLM 调用（节省 100-300s CPU 推理）。
        _, eff_model_stream = self._get_eff_provider()
        model_lower = (eff_model_stream or "").lower()
        use_text_tools = any(p in model_lower for p in self._TEXT_TOOL_CALLING_PATTERNS)
        if use_text_tools and not tool_call_log and content:
            # 清除残留的 <tool_call> 标签及其内容
            cleaned = re.sub(r'<tool_call[^>]*>.*?</tool_call>', '', content, flags=re.DOTALL)
            cleaned = re.sub(r'<tool_call[^>]*>', '', cleaned)
            cleaned = re.sub(r'</tool_call>', '', cleaned)
            # 清理多余空行
            cleaned = re.sub(r'\n{3,}', '\n\n', cleaned).strip()
            if cleaned:
                if use_memory:
                    self.memory.store_conversation(
                        "assistant", cleaned, user_id=user_id,
                        metadata={"message_id": assistant_message_id} if assistant_message_id else None,
                    )
                    asyncio.create_task(self._maybe_distill(user_id))
                asyncio.create_task(self._maybe_summarize(user_id))
                if project_id:
                    asyncio.create_task(self._maybe_extract_context(user_id, project_id))
                yield ('token', cleaned)
                yield ('done', {"content": cleaned})
                return

        final_messages = []
        for m in messages:
            if m["role"] == "tool":
                final_messages.append({
                    "role": "user",
                    "content": f"[工具执行结果]\n{m['content']}\n\n请基于以上结果，用中文直接回答用户的问题，不要再调用任何工具。"
                })
            else:
                final_messages.append(m)

        config = LLMConfig(
            temperature=settings.ollama_temperature,
            max_tokens=settings.ollama_max_tokens,
            top_p=settings.ollama_top_p,
            top_k=settings.ollama_top_k,
            repeat_penalty=settings.ollama_repeat_penalty,
            num_ctx=settings.ollama_num_ctx,
        )
        chat_messages = [
            ChatMessage(role=m["role"], content=m.get("content", ""),
                        images=m.get("_images"))
            for m in final_messages
        ]

        full_response = ""
        try:
            async for etype, chunk in self._stream_with_cot(chat_messages, config, cancel_event):
                if etype == 'token':
                    full_response += chunk
                yield (etype, chunk)
        except Exception as e:
            logger.warning(f"流式输出异常（已收到部分内容）: {e}")

        if use_memory and full_response:
            self.memory.store_conversation(
                "assistant", full_response, user_id=user_id,
                metadata={"message_id": assistant_message_id} if assistant_message_id else None,
            )
            asyncio.create_task(self._maybe_distill(user_id))
            if project_id:
                asyncio.create_task(self._maybe_extract_context(user_id, project_id))

        # [TASK_DONE] / [TASK_BLOCKED] 检测（D1=B：全结束后扫描）
        full_response, _sentinel_events = self._strip_task_sentinels(full_response, project_id)
        for _s_type, _s_data in _sentinel_events:
            yield (_s_type, _s_data)

        yield ('done', {"content": full_response})
