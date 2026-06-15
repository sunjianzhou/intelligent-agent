"""智能体核心类（门面）

IntelligentAgent 通过 Mixin 继承将职责拆分到三个专注模块：
- ConversationFlowMixin  (conversation_flow.py) — 消息构建、chat、chat_stream
- ToolDispatcherMixin    (tool_dispatcher.py)   — 工具初始化、解析、执行、LLM 工具调用
- MemoryWriterMixin      (memory_writer.py)     — 记忆写入、蒸馏、清理、预热

本文件只保留：__init__、provider 管理、token/context 预算、L1/L2 缓存、_call_model、工具方法。
"""
import asyncio
import hashlib
import threading
import time
from collections import OrderedDict
from typing import Dict, Any, Optional, List

from loguru import logger

from config.settings import settings
from tools.tool_manager import ToolManager
from memory.manager import MemoryManager
from services.ollama_provider import OllamaProvider
from services.base_provider import LLMConfig, ChatMessage
from skills import skill_manager, SkillApplicator
from prompts.prompt_manager import prompt_manager
from api.metrics import cache_hits_total, cache_misses_total

from core._context_vars import (
    _request_provider_ctx,
    _request_persona_ctx,
    _last_message_vec_ctx,
)
from core.memory_writer import MemoryWriterMixin
from core.tool_dispatcher import ToolDispatcherMixin
from core.conversation_flow import ConversationFlowMixin
from soul.loader import SoulLoader
from core.system_prompt_builder import SystemPromptBuilder


class IntelligentAgent(ConversationFlowMixin, ToolDispatcherMixin, MemoryWriterMixin):
    """智能体核心类（门面）。"""

    def __init__(self, provider: OllamaProvider = None):
        # provider/model 读写锁（保护并发模型切换与推理请求间的一致性）
        self._provider_lock = threading.RLock()

        # ── Provider 选择：云端优先，否则用 Ollama ────────────
        logger.info(
            f"云端配置: provider={settings.cloud_provider}, model={settings.cloud_model}, base_url={settings.cloud_base_url}")
        if provider:
            self.provider = provider
        elif settings.cloud_provider and settings.cloud_api_key and settings.cloud_model:
            from services.openai_provider import OpenAIProvider
            self.provider = OpenAIProvider(
                api_key=settings.cloud_api_key,
                base_url=settings.cloud_base_url,
                model=settings.cloud_model,
            )
            logger.info(f"使用云端模型: {settings.cloud_model} ({settings.cloud_base_url})")
        else:
            self.provider = OllamaProvider()
            logger.info(f"使用本地 Ollama 模型")

        self.model = self.provider.current_model
        self.tool_manager = ToolManager()  # per-agent 独立实例，隔离多用户工具状态

        # 初始化工具
        self._init_tools()

        # 初始化记忆
        self.memory = MemoryManager(
            short_term_config={
                "name": "agent_short_term",
                "max_size": settings.short_term_max_size,
                "ttl_hours": settings.short_term_ttl_hours,
            },
            long_term_config={
                "name": "agent_long_term",
                "vector_db_type": "chroma",
                "embedding_model": settings.embedding_model,
                "persist_dir": settings.chroma_persist_dir,
            }
        )

        # 灵魂层
        self.soul = SoulLoader(soul_dir=str(settings.soul_dir) if settings.soul_dir else None)
        self.prompt_builder = SystemPromptBuilder()

        # 调度器：初始化失败不影响主流程
        self.task_manager = None
        try:
            from scheduler.simple_manager import TaskManager
            self.task_manager = TaskManager(tool_manager=self.tool_manager)
            # 注入 agent 引用，使 llm_generate 动作可以调用 LLM
            self.task_manager.scheduler._agent = self
            self._register_task_tools()

            # C-1: MCP/cleanup/warmup 后台任务在 lifespan 中调度（事件循环运行后），CLI/测试环境跳过
            self._mcp_initialized = False

            logger.info("任务调度器已启动")
        except Exception as e:
            logger.warning(f"任务调度器初始化失败，跳过: {e}")

        # L1 精确响应缓存（OrderedDict LRU，并发读写需锁保护）
        self._response_cache: OrderedDict = OrderedDict()
        self._response_cache_lock = threading.Lock()
        self._cache_max_size: int = getattr(settings, 'response_cache_max_size', 500)
        self._cache_ttl_secs: int = getattr(settings, 'response_cache_ttl_secs', 3600)

        # L2 语义响应缓存（ChromaDB response_cache collection）
        self._semantic_cache = None
        try:
            from memory.semantic_cache import SemanticCache
            self._semantic_cache = SemanticCache(
                embedding_model=self.memory.long_term.embedding_model,
                persist_dir=settings.long_term_persist_dir,
                threshold=getattr(settings, 'semantic_cache_threshold', 0.92),
                ttl_secs=getattr(settings, 'semantic_cache_ttl_secs', 86400),
                max_entries=getattr(settings, 'semantic_cache_max_entries', 2000),
            )
        except Exception as _sc_err:
            logger.warning(f"L2 语义缓存初始化失败（将跳过）: {_sc_err}")

        self.skill_manager = skill_manager
        self.skill_applicator = SkillApplicator(skill_manager, self.tool_manager)

        # 9.3 自动记忆提炼
        from memory.distiller import MemoryDistiller
        self._distiller = MemoryDistiller(
            interval=getattr(settings, 'memory_distill_interval', 5),
            dedup_threshold=getattr(settings, 'memory_distill_dedup_threshold', 0.85),
        )

        # 9.4 阶段性摘要
        self._summary_turn_counts: Dict[str, int] = {}

        # 项目上下文提取器（上下文持久化 Phase 1）
        from memory.context_extractor import ContextExtractor
        self._context_extractor = ContextExtractor(
            interval=getattr(settings, 'context_extract_interval', 8),
            dedup_threshold=getattr(settings, 'context_extract_dedup_threshold', 0.85),
        )

        # 规格注入轮次计数（规格驱动开发 Phase 2），键为 f"{user_id}:{project_id}"
        self._spec_turn_counts: Dict[str, int] = {}

        logger.info(f"IntelligentAgent 初始化完成，模型: {self.model}")

    # ═══════════════════════════════════════════════════════════════
    # Provider 管理
    # ═══════════════════════════════════════════════════════════════

    def set_provider(self, provider, model: str) -> None:
        """原子地切换 provider 和 model，保证其他线程/协程读到一致的状态。"""
        with self._provider_lock:
            self.provider = provider
            self.model = model

    def _snapshot_provider(self):
        """返回 (provider, model) 的一致快照，供推理函数使用。"""
        with self._provider_lock:
            return self.provider, self.model

    def _get_eff_provider(self):
        """返回当前请求的有效 (provider, model)。
        若请求上下文中注入了 per-user 覆盖 provider，则优先使用；
        否则回退到全局 provider。
        """
        override = _request_provider_ctx.get()
        if override is not None:
            return override, override.current_model
        return self._snapshot_provider()

    def _get_eff_persona(self) -> Optional[str]:
        """返回当前请求的有效角色内容字符串，或 None（使用模型默认 template）。"""
        return _request_persona_ctx.get()

    # ═══════════════════════════════════════════════════════════════
    # Token 估算 & 上下文保护
    # ═══════════════════════════════════════════════════════════════

    @staticmethod
    def _estimate_tokens(text: str) -> int:
        """粗略估算 token 数：中英文混合按每 2.5 字符 ≈ 1 token"""
        if not text:
            return 0
        return max(1, int(len(text) / 2.5))

    def _msg_token_count(self, m: Dict[str, Any]) -> int:
        """返回单条消息的 token 数估算，并缓存到消息 dict 的 _token_count 字段。

        同一轮内 _build_messages_async → _compress_context → _trim_context 会对
        同一批消息 dict（同一对象引用）反复调用 _estimate_messages_tokens，
        缓存可避免重复对 content 做 len()/2.5 计算。"""
        cached = m.get("_token_count")
        if cached is None:
            cached = self._estimate_tokens(m.get("content", "")) + 4  # role overhead
            m["_token_count"] = cached
        return cached

    def _estimate_messages_tokens(self, messages: List[Dict[str, str]]) -> int:
        """估算消息列表的总 token 数"""
        return int(sum(self._msg_token_count(m) for m in messages))

    def _trim_context(self, messages: List[Dict[str, str]], max_tokens: int) -> List[Dict[str, str]]:
        """截断消息列表以保持在 token 预算内。

        始终保留 system 消息和最后一条 user 消息。
        从中间最旧的非 system 消息开始移除。
        """
        current = self._estimate_messages_tokens(messages)
        if current <= max_tokens:
            return messages

        # 分离 system / 最后一条 user / 其余消息
        system_msgs = [m for m in messages if m["role"] == "system"]
        last_user_idx = max(
            (i for i, m in enumerate(messages) if m["role"] == "user"),
            default=-1
        )
        last_user_msg = messages[last_user_idx] if last_user_idx >= 0 else None

        # 中间层消息（可被裁剪）
        middle = [
            m for i, m in enumerate(messages)
            if m["role"] != "system" and i != last_user_idx
        ]

        # 从最旧开始移除，直到满足预算
        budget = max_tokens - self._estimate_tokens(
            "".join(m.get("content", "") for m in system_msgs)
        ) - (self._estimate_tokens(last_user_msg.get("content", "")) if last_user_msg else 0) - 100

        kept = []
        kept_tokens = 0
        for m in reversed(middle):
            t = self._msg_token_count(m)
            if kept_tokens + t <= budget:
                kept.insert(0, m)
                kept_tokens += t
            else:
                break

        result = system_msgs + kept
        if last_user_msg:
            result.append(last_user_msg)
        if len(result) < len(messages):
            logger.info(f"上下文截断: {len(messages)} → {len(result)} 条消息 "
                       f"({current} → {self._estimate_messages_tokens(result)} tokens)")
        return result

    async def _compress_context(
        self,
        messages: List[Dict[str, str]],
        max_tokens: int,
        compress_ratio: float = 0.6,
    ) -> List[Dict[str, str]]:
        """动态压缩上下文：当 token 超预算时，用 LLM 将最旧的对话轮次摘要为一条 system 消息。

        压缩策略：
        - 保留所有原始 system 消息（prompt/memory 注入）
        - 保留最近 compress_ratio 比例的对话消息不压缩
        - 将更旧的对话轮次发送给模型摘要，结果作为新的 system 消息注入
        - 比 _trim_context 优先调用，能保留语义；压缩失败则回退到 _trim_context
        """
        current = self._estimate_messages_tokens(messages)
        if current <= max_tokens:
            return messages

        system_msgs = [m for m in messages if m["role"] == "system"]
        dialog_msgs = [m for m in messages if m["role"] != "system"]

        if len(dialog_msgs) < 4:
            # 对话太短，没有压缩价值，直接截断
            return self._trim_context(messages, max_tokens)

        # 将最旧的 (1 - compress_ratio) 比例对话轮次压缩
        split_idx = max(2, int(len(dialog_msgs) * (1 - compress_ratio)))
        to_compress = dialog_msgs[:split_idx]
        to_keep = dialog_msgs[split_idx:]

        # 构造摘要请求
        dialog_text = "\n".join(
            f"{'用户' if m['role'] == 'user' else '助手'}: {m.get('content', '')[:400]}"
            for m in to_compress
        )
        summary_prompt = [
            {"role": "system", "content": "你是对话摘要助手，请用简洁中文摘要以下对话的核心内容、关键信息和结论，控制在200字以内："},
            {"role": "user", "content": dialog_text},
        ]

        try:
            config = LLMConfig(
                temperature=0.3,
                max_tokens=300,
                top_p=0.9,
                top_k=40,
                num_ctx=2048,
            )
            loop = asyncio.get_running_loop()
            chat_messages = [ChatMessage(role=m["role"], content=m["content"]) for m in summary_prompt]
            resp = await asyncio.wait_for(
                loop.run_in_executor(None, lambda: self._get_eff_provider()[0].chat(chat_messages, config)),
                timeout=60,
            )
            if not resp.success:
                raise RuntimeError(resp.error)

            summary_text = resp.content.strip()
            logger.info(f"上下文压缩: {len(to_compress)} 轮对话 → 摘要({len(summary_text)}字符)")

            compressed_system = {
                "role": "system",
                "content": f"【早期对话摘要（已压缩）】:\n{summary_text}",
            }
            result = system_msgs + [compressed_system] + to_keep
            after_tokens = self._estimate_messages_tokens(result)
            logger.info(f"压缩后 token: {current} → {after_tokens}")

            # 如果压缩后仍超预算，再走截断兜底
            if after_tokens > max_tokens:
                return self._trim_context(result, max_tokens)
            return result

        except Exception as e:
            logger.warning(f"上下文压缩失败，回退到截断: {e}")
            return self._trim_context(messages, max_tokens)

    # ═══════════════════════════════════════════════════════════════
    # L1 精确响应缓存
    # ═══════════════════════════════════════════════════════════════

    def _cache_key(self, message: str) -> str:
        """生成精确匹配缓存键（sha256 of message + model + persona）。
        加入 persona 维度：同一问题在不同角色下响应不同，不应互相命中缓存。
        """
        _, eff_model = self._get_eff_provider()
        persona = self._get_eff_persona() or ""
        persona_sig = persona[:40] if persona else ""  # 取前40字符作为签名，避免超长
        raw = f"{message}|{eff_model or ''}|{persona_sig}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _cache_get(self, message: str) -> Optional[str]:
        """查询 L1 缓存，命中则返回响应字符串，过期或未命中返回 None。"""
        key = self._cache_key(message)
        with self._response_cache_lock:
            entry = self._response_cache.get(key)
            if entry is None:
                return None
            response, expire_ts = entry
            if time.time() > expire_ts:
                del self._response_cache[key]
                return None
            self._response_cache.move_to_end(key)
            return response

    def _cache_put(self, message: str, response: str) -> None:
        """写入 L1 缓存（含 LRU 淘汰）。"""
        key = self._cache_key(message)
        expire_ts = time.time() + self._cache_ttl_secs
        with self._response_cache_lock:
            self._response_cache[key] = (response, expire_ts)
            self._response_cache.move_to_end(key)
            while len(self._response_cache) > self._cache_max_size:
                self._response_cache.popitem(last=False)

    # ═══════════════════════════════════════════════════════════════
    # 核心模型调用
    # ═══════════════════════════════════════════════════════════════

    @staticmethod
    def _is_retryable_error(exc: Exception) -> bool:
        """判断异常是否属于可重试的瞬时错误（网络/连接/超时类）。"""
        if isinstance(exc, asyncio.TimeoutError):
            return True
        msg = str(exc).lower()
        return any(kw in msg for kw in (
            "connection", "connect", "network", "timeout",
            "refused", "reset", "unavailable", "502", "503", "504",
        ))

    @staticmethod
    def _merge_system_messages(messages: List[Dict[str, str]]) -> List[Dict[str, str]]:
        """将消息列表中所有 system role 的内容合并为一条，避免 Ollama 对多 system 消息的不确定行为。

        合并顺序：第一条 system（主 system prompt）在最前，后续 system 内容追加，
        其他角色消息保持原顺序不变。返回新列表，不修改原列表。
        """
        system_parts: List[str] = []
        non_system: List[Dict[str, str]] = []
        for m in messages:
            if m.get("role") == "system":
                content = (m.get("content") or "").strip()
                if content:
                    system_parts.append(content)
            else:
                non_system.append(m)
        if not system_parts:
            return list(messages)
        merged = {"role": "system", "content": "\n\n".join(system_parts)}
        return [merged] + non_system

    async def _call_model(self, messages: List[Dict[str, str]],
                          fallback_timeout: Optional[int] = None) -> str:
        """调用模型（非工具模式），带超时保护 + 指数退避重试（最多3次）。"""
        if fallback_timeout is None:
            fallback_timeout = getattr(settings, 'chat_timeout', 300)
        config = LLMConfig(
            temperature=settings.ollama_temperature,
            max_tokens=settings.ollama_max_tokens,
            top_p=settings.ollama_top_p,
            top_k=settings.ollama_top_k,
            repeat_penalty=settings.ollama_repeat_penalty,
            num_ctx=settings.ollama_num_ctx,
        )
        # 合并多 system 消息为一条，避免 Ollama 对多 system role 的不确定行为
        merged = self._merge_system_messages(messages)
        _, eff_model_name = self._get_eff_provider()
        _is_text_tool_model = any(p in (eff_model_name or "").lower() for p in self._TEXT_TOOL_CALLING_PATTERNS)
        # dolphin 等无审查模型：在 system 末尾注入防退化锚定，防止长 context 稀释人格
        if _is_text_tool_model and merged and merged[0]["role"] == "system":
            merged[0] = {**merged[0], "content": merged[0]["content"] + self._DOLPHIN_ANCHOR}
        chat_messages = [
            ChatMessage(role=m["role"], content=m["content"])
            for m in merged
        ]
        loop = asyncio.get_running_loop()
        last_err: str = ""
        eff_provider, _ = self._get_eff_provider()

        for attempt in range(3):
            try:
                resp = await asyncio.wait_for(
                    loop.run_in_executor(
                        None, lambda p=eff_provider: p.chat(chat_messages, config)
                    ),
                    timeout=fallback_timeout
                )
                if resp.success:
                    return resp.content
                # 模型返回业务错误——不可重试
                logger.error(f"模型调用失败: {resp.error}")
                return f"模型调用失败: {resp.error}"

            except Exception as exc:
                if self._is_retryable_error(exc):
                    last_err = str(exc) or type(exc).__name__
                    logger.warning(
                        f"_call_model 第 {attempt + 1}/3 次失败（可重试）: {last_err}"
                    )
                else:
                    logger.error(f"_call_model 不可重试异常: {exc}")
                    return f"处理请求时出错: {exc}"

            if attempt < 2:
                await asyncio.sleep(2 ** attempt)   # 1 s → 2 s

        logger.error(f"_call_model 重试 3 次后仍失败: {last_err}")
        return f"请求失败（已重试 3 次）: {last_err}"

    # 集中管理错误响应前缀，避免各处硬编码字符串做判断
    _LLM_ERROR_PREFIXES = ("模型调用失败", "请求失败", "处理请求时出错")

    def _is_error_response(self, response: str) -> bool:
        """检测 _call_model 返回的是否是错误消息（而非正常模型输出）。"""
        return bool(response and response.startswith(self._LLM_ERROR_PREFIXES))

    # ═══════════════════════════════════════════════════════════════
    # 辅助方法
    # ═══════════════════════════════════════════════════════════════

    def sync_model(self):
        self.model = self.provider.current_model

    def clear_history(self):
        _last_message_vec_ctx.set(None)
        self.memory.clear_all()
        logger.info("对话历史和记忆已清空")

    def get_available_tools(self) -> Dict[str, Any]:
        return {
            "tools": list(self.tool_manager.get_all_tools().keys()),
            "categories": self.tool_manager.get_categories(),
            "count": len(self.tool_manager.get_all_tools()),
        }

    def get_memory_info(self) -> Dict[str, Any]:
        return self.memory.get_stats()

    def stop(self):
        if self.task_manager:
            self.task_manager.stop()
        try:
            from services.mcp_client import mcp_manager
            asyncio.run(mcp_manager.close_all())
        except Exception:
            pass
        logger.info("智能体已停止")
