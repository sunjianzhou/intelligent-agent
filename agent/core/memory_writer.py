"""MemoryWriterMixin — 记忆写入、蒸馏、清理、预热等后台职责。

作为 Mixin 基类，通过 Python MRO 拼接到 IntelligentAgent，
所有 self.* 属性在运行时由 IntelligentAgent.__init__ 提供。
"""
import asyncio
from datetime import datetime
from typing import Optional, Dict, Any, List

from loguru import logger

from config.settings import settings


class MemoryWriterMixin:
    """记忆写入与维护。"""

    # ═══════════════════════════════════════════════════════════════
    # LLM / Embedding 预热
    # ═══════════════════════════════════════════════════════════════

    async def _warmup_embeddings(self):
        """预热 embedding 模型 + 缓存意图分类向量"""
        try:
            await asyncio.sleep(2)
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(
                None, self._match_categories_by_embedding, "warmup"
            )
            logger.info("意图分类向量预热完成")
        except Exception as e:
            logger.warning(f"embedding 预热失败: {e}")

    async def _warmup_llm(self):
        """启动时给 Ollama 发一个极短 prompt，把模型加载/编译开销提前到启动阶段
        （配合 keep_alive 常驻显存，避免用户首个请求承担冷启动延迟）"""
        from services.ollama_provider import OllamaProvider
        from services.base_provider import LLMConfig, ChatMessage
        if not isinstance(self.provider, OllamaProvider):
            return
        try:
            await asyncio.sleep(3)
            loop = asyncio.get_running_loop()
            response = await loop.run_in_executor(
                None,
                lambda: self.provider.chat(
                    [ChatMessage(role="user", content="hi")],
                    LLMConfig(
                        temperature=settings.ollama_temperature,
                        max_tokens=4,
                        top_p=settings.ollama_top_p,
                        top_k=settings.ollama_top_k,
                        repeat_penalty=settings.ollama_repeat_penalty,
                        num_ctx=settings.ollama_num_ctx,
                    )
                )
            )
            if response.success:
                logger.info(f"LLM 预热完成（模型 {self.provider.current_model} 已加载到显存）")
            else:
                logger.warning(f"LLM 预热请求未成功: {response.error}")
        except Exception as e:
            logger.warning(f"LLM 预热失败（不影响主流程）: {e}")

    # ═══════════════════════════════════════════════════════════════
    # MCP 工具初始化
    # ═══════════════════════════════════════════════════════════════

    async def _init_mcp_tools(self):
        """异步初始化 MCP 工具，不阻塞 Agent 启动"""
        try:
            from services.mcp_client import mcp_manager
            from config.settings import settings

            if settings.github_token and settings.github_mcp_enabled:
                tool_names = await mcp_manager.connect_server(
                    server_name="github",
                    command="npx",
                    args=["-y", "@modelcontextprotocol/server-github"],
                    env={"GITHUB_PERSONAL_ACCESS_TOKEN": settings.github_token},
                )
                count = mcp_manager.register_to_tool_manager(
                    "github", self.tool_manager, category="github"
                )
                logger.info(f"GitHub MCP 工具已注册: {count} 个 → {tool_names[:5]}")

            if settings.filesystem_mcp_enabled and settings.filesystem_allowed_dirs:
                dirs = [d.strip() for d in settings.filesystem_allowed_dirs.split(",") if d.strip()]
                tool_names = await mcp_manager.connect_server(
                    server_name="filesystem",
                    command="npx",
                    args=["-y", "@modelcontextprotocol/server-filesystem"] + dirs,
                )
                count = mcp_manager.register_to_tool_manager(
                    "filesystem", self.tool_manager, category="filesystem"
                )
                logger.info(f"文件系统 MCP 工具已注册: {count} 个")

            self._mcp_initialized = True
            logger.info(f"MCP 初始化完成，总工具数: {len(self.tool_manager.get_all_tools())}")

        except Exception as e:
            logger.warning(f"MCP 初始化失败（不影响主流程）: {e}")

    # ═══════════════════════════════════════════════════════════════
    # 定期记忆清理
    # ═══════════════════════════════════════════════════════════════

    async def _start_memory_cleanup(self):
        """每天凌晨2点执行记忆清理（每15分钟检查一次）"""
        last_cleanup_date = None
        while True:
            try:
                now = datetime.now()
                today = now.date()
                if now.hour == 2 and last_cleanup_date != today:
                    await self._cleanup_memories()
                    last_cleanup_date = today
            except Exception as e:
                logger.error(f"记忆清理异常: {e}")
            await asyncio.sleep(900)  # 15 分钟检查一次

    async def _cleanup_memories(self):
        """记忆整理：清理过期 + 压缩 + 蒸馏用户画像"""
        logger.info("开始执行定期记忆整理...")

        before = self.memory.short_term.count()
        self.memory.short_term._cleanup_expired()
        after = self.memory.short_term.count()
        logger.info(f"短期记忆清理: {before} → {after} 条")

        lt_count = self.memory.long_term.count()
        if lt_count > 500:
            all_mems = self.memory.long_term.list(limit=lt_count)
            sorted_mems = sorted(all_mems, key=lambda m: (m.importance, m.created_at.timestamp()))
            to_delete = sorted_mems[:lt_count - 400]
            for m in to_delete:
                self.memory.long_term.delete(m.id)
            logger.info(f"长期记忆压缩: {lt_count} → {self.memory.long_term.count()} 条")

        await self._distill_short_term_memories()
        await self._generate_daily_summary()

        logger.info("记忆整理完成")

    # ═══════════════════════════════════════════════════════════════
    # 增量蒸馏 & 阶段摘要
    # ═══════════════════════════════════════════════════════════════

    async def _maybe_distill(self, user_id: str) -> None:
        """Trigger incremental distillation when the turn counter reaches the interval.

        Runs as a fire-and-forget asyncio task so it never blocks the response path.
        """
        if not self._distiller.record_turn(user_id):
            return
        try:
            items = self.memory.short_term.list(limit=self._distiller.interval * 4)
            await self._distiller.distill(
                user_id=user_id,
                short_term_items=items,
                call_model_fn=self._call_model,
                long_term_memory=self.memory.long_term,
            )
        except Exception as exc:
            logger.warning(f"_maybe_distill 异常 (user={user_id}): {exc}")

    async def _maybe_summarize(self, user_id: str) -> None:
        """Generate a session summary every N turns and persist to long-term memory."""
        interval = getattr(settings, 'memory_summary_interval', 10)
        n = self._summary_turn_counts.get(user_id, 0) + 1
        self._summary_turn_counts[user_id] = n
        if n < interval:
            return
        self._summary_turn_counts[user_id] = 0

        items = self.memory.short_term.list(limit=interval * 2 + 10)
        user_items = [
            m for m in items
            if m.metadata.get("user_id") == user_id
            and m.metadata.get("role") in ("user", "assistant")
        ]
        if len(user_items) < 4:
            return

        window = user_items[-(interval * 2):]
        conv_text = "\n".join(
            f"{'用户' if m.metadata.get('role') == 'user' else '助手'}: {m.content[:150]}"
            for m in window
        )
        try:
            summary = await self._call_model([
                {"role": "system", "content": "你是摘要助手，用3-5句话总结对话核心内容，涵盖主要话题和重要结论。"},
                {"role": "user", "content": f"请总结以下对话片段：\n{conv_text}"},
            ])
            if summary:
                ts = datetime.now().strftime('%Y-%m-%d %H:%M')
                await self._store_knowledge_async(
                    content=summary,
                    importance=0.8,
                    metadata={
                        "type": "session_summary",
                        "user_id": user_id,
                        "timestamp": ts,
                    },
                )
                logger.info(f"阶段摘要已生成: user={user_id} — {summary[:60]}...")
        except Exception as exc:
            logger.warning(f"生成阶段摘要失败 (user={user_id}): {exc}")

    async def _store_knowledge_async(self, content: str, importance: float = 0.7,
                                      metadata: Optional[Dict[str, Any]] = None) -> None:
        """在线程池中写入长期记忆。

        长期记忆写入包含 embedding 编码（CPU 密集）和 ChromaDB upsert（磁盘 I/O），
        若在事件循环线程中同步执行会阻塞并发请求处理；蒸馏/摘要等后台任务对实时性
        无要求，挪到线程池异步执行即可解耦。"""
        loop = asyncio.get_running_loop()
        if metadata is not None:
            await loop.run_in_executor(
                None, lambda: self.memory.long_term.store(content=content, metadata=metadata, importance=importance)
            )
        else:
            await loop.run_in_executor(
                None, lambda: self.memory.store_knowledge(content, importance=importance)
            )

    async def _distill_short_term_memories(self):
        """把短期记忆蒸馏成结构化知识"""
        recent = self.memory.short_term.list(limit=50)
        if len(recent) < 5:
            return

        user_msgs = [
            m.content for m in recent
            if m.metadata.get("role") == "user"
        ]
        if not user_msgs:
            return

        conv_text = "\n".join(f"- {c[:100]}" for c in user_msgs[-20:])

        prompt = f"""从以下用户消息中提取关键信息，以JSON格式输出，包含以下字段：
                - preferences: 用户偏好列表（如使用习惯、喜好等）
                - personal_info: 用户个人信息列表（如姓名、职业、常用工具等）
                - frequent_topics: 高频话题列表
                - behavior_patterns: 行为模式描述

                用户消息：
                {conv_text}

                只输出JSON，不要其他内容。格式：
                {{"preferences":[],"personal_info":[],"frequent_topics":[],"behavior_patterns":[]}}"""
        try:
            result = await self._call_model([
                {"role": "system", "content": "你是一个信息提取助手，只输出JSON格式数据。"},
                {"role": "user", "content": prompt}
            ])

            import json as _json
            clean = result.strip().replace("```json", "").replace("```", "").strip()
            extracted = _json.loads(clean)

            stored = 0
            for pref in extracted.get("preferences", []):
                if pref:
                    await self._store_knowledge_async(f"[用户偏好] {pref}", importance=0.7)
                    stored += 1
            for info in extracted.get("personal_info", []):
                if info:
                    await self._store_knowledge_async(f"[用户信息] {info}", importance=0.9)
                    stored += 1
            for topic in extracted.get("frequent_topics", []):
                if topic:
                    await self._store_knowledge_async(f"[常用话题] {topic}", importance=0.6)
                    stored += 1
            for pattern in extracted.get("behavior_patterns", []):
                if pattern:
                    await self._store_knowledge_async(f"[行为模式] {pattern}", importance=0.6)
                    stored += 1

            if stored > 0:
                logger.info(f"记忆蒸馏完成，提取 {stored} 条结构化知识")
        except Exception as e:
            logger.warning(f"记忆蒸馏失败: {e}")

    async def _generate_daily_summary(self):
        """生成今日对话摘要"""
        recent = self.memory.get_recent_conversations(20)
        if not recent:
            return

        conv_text = "\n".join(
            f"{'用户' if m.metadata.get('role') == 'user' else '助手'}: {m.content[:100]}"
            for m in recent
        )
        try:
            summary = await self._call_model([
                {"role": "system", "content": "你是摘要助手，请用2-3句话总结对话核心内容。"},
                {"role": "user", "content": f"请总结：\n{conv_text}"}
            ])
            if summary:
                await self._store_knowledge_async(
                    f"[每日摘要 {datetime.now().strftime('%Y-%m-%d')}] {summary}",
                    importance=0.8
                )
                logger.info(f"每日摘要已存储: {summary[:50]}...")
        except Exception as e:
            logger.warning(f"生成摘要失败: {e}")
