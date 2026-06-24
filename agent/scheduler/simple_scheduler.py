"""简化版任务调度器"""
import asyncio
import json
import os
import threading
import time
from collections import deque
from pathlib import Path
from typing import Dict, Any, List, Optional, Callable
from datetime import datetime, timedelta
from loguru import logger
from scheduler.simple_models import SimpleTask, SimpleTaskStatus

# ── 全局通知队列（scheduler → 前端推送）────────────────────────
_notification_lock = threading.Lock()
_notifications: deque = deque(maxlen=50)  # 最多保留50条未读通知


def _push_notification(message: str, timestamp: str = None, role: str = "system") -> None:
    """向通知队列写入一条消息（线程安全）。role='assistant' 时前端渲染为 AI 气泡。"""
    with _notification_lock:
        _notifications.append({
            "message": message,
            "timestamp": timestamp or datetime.now().isoformat(),
            "role": role,
        })


def pop_notifications() -> list:
    """取出并清空所有待推送通知（供 API 端点调用）。"""
    with _notification_lock:
        items = list(_notifications)
        _notifications.clear()
        return items

# 默认持久化文件路径（相对于 agent/ 目录）
_DEFAULT_TASKS_FILE = Path(__file__).parent.parent / "data" / "tasks.json"


class SimpleTaskScheduler:
    """简化版任务调度器"""

    # 全局 LLM 调用限流：保证同时只有 1 个定时任务在做 LLM 推理，
    # 防止多个 llm_generate 任务同时送 Ollama 造成队列积压和超时。
    # 使用 asyncio.Semaphore（在首次事件循环可用时延迟初始化）。
    _llm_sem:  "asyncio.Semaphore | None" = None
    _llm_loop: "asyncio.AbstractEventLoop | None" = None  # _llm_sem 所属的事件循环

    def __init__(self, check_interval: float = 1.0,
                 tasks_file: Path = _DEFAULT_TASKS_FILE,
                 tool_manager=None):
        """初始化调度器

        Args:
            check_interval: 检查任务间隔（秒）
            tasks_file: 任务持久化文件路径
            tool_manager: 工具管理器实例（None 时回退到全局单例）
        """
        self.tasks: Dict[str, SimpleTask] = {}
        self.running = False
        self.check_interval = check_interval
        self.check_thread: Optional[threading.Thread] = None

        # 每 Agent 独立的工具管理器引用（None → 回退到全局单例）
        self._tool_manager = tool_manager
        # Agent 引用，供 llm_generate action 调用 LLM（由 agent.py 在初始化后注入）
        self._agent = None
        # H-7: 可注入依赖，解除对 api.fastapi_app 的循环 import
        # 由 fastapi_app.py 在 agent 创建后注入；CLI/测试环境保持 None
        self._provider_getter = None   # callable(user_id) -> provider | None
        self._inference_slot = None    # async context manager

        # 动作注册表
        self.actions: Dict[str, Callable] = {}

        # 主线程事件循环引用
        self.main_loop: Optional[asyncio.AbstractEventLoop] = None

        # 持久化
        self._tasks_file = Path(tasks_file)
        self._file_lock = threading.Lock()
        # 任务字典锁：保护 self.tasks 的并发读写（调度线程读、asyncio 协程写）
        self._tasks_lock = threading.Lock()
        # dirty-flag：有变更时置 True，_check_tasks_loop 每 10s 批量写入一次
        self._tasks_dirty = False
        self._last_save_ts = 0.0
        self._tasks_file.parent.mkdir(parents=True, exist_ok=True)

        # 注册内置动作
        self._register_builtin_actions()

        # 从文件恢复任务（在动作注册之后）
        self._load_tasks()

        logger.info(f"简化任务调度器已初始化，检查间隔: {check_interval}秒，持久化文件: {self._tasks_file}")

    def _load_tasks(self) -> None:
        """从 JSON 文件恢复任务（服务重启后调用）"""
        if not self._tasks_file.exists():
            return
        try:
            with open(self._tasks_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            for task_dict in data:
                task = SimpleTask.from_dict(task_dict)
                # running 状态说明上次被中断，重置为 pending 重新调度
                if task.status.value == "running":
                    task.status = SimpleTaskStatus.PENDING
                self.tasks[task.id] = task
            logger.info(f"从文件恢复 {len(self.tasks)} 个任务")
        except Exception as e:
            logger.error(f"加载任务文件失败（跳过恢复）: {e}")

    def _save_tasks(self) -> None:
        """标记 dirty；实际写盘由 _flush_tasks_if_dirty() 批量执行（每10s最多一次）。
        立即写场景（任务创建/删除/手动操作）直接调 _flush_tasks_now()。"""
        self._tasks_dirty = True

    def _flush_tasks_now(self) -> None:
        """原子写入 JSON 文件（立即执行，用于任务增删等需要即时持久化的场景）。"""
        try:
            data = [task.to_dict() for task in self.tasks.values()]
            tmp = str(self._tasks_file) + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            os.replace(tmp, str(self._tasks_file))
            self._tasks_dirty = False
            self._last_save_ts = time.time()
        except Exception as e:
            logger.error(f"保存任务文件失败: {e}")

    def _flush_tasks_if_dirty(self) -> None:
        """每10s检查一次：有 dirty 变更时才写盘，减少高频 interval 任务的 I/O。"""
        if self._tasks_dirty and (time.time() - self._last_save_ts) >= 10.0:
            with self._file_lock:
                self._flush_tasks_now()

    def _register_builtin_actions(self):
        """注册内置动作"""
        # 日志动作（同时推送到通知队列，让前端聊天页能显示）
        def log_action(message: str, level: str = "INFO", role: str = "system"):
            """记录日志并推送到通知队列。role='assistant' 时前端显示为 AI 气泡。"""
            log_func = getattr(logger, level.lower(), logger.info)
            log_func(f"[定时任务] {message}")
            ts = datetime.now().isoformat()
            _push_notification(message, ts, role=role)
            _ag = self._agent
            if _ag is not None:
                try:
                    _ag.memory.store(message, category="task", metadata={"source": "scheduler", "role": role, "timestamp": ts})
                except Exception as _me:
                    logger.warning(f"[log_action] 写入短期记忆失败: {_me}")
            return {"message": message, "level": level, "timestamp": ts}

        # 系统信息
        def system_info_action():
            """获取系统信息"""
            import platform
            import psutil

            return {
                "timestamp": datetime.now().isoformat(),
                "platform": platform.platform(),
                "cpu_percent": psutil.cpu_percent(interval=0.1),
                "memory_percent": psutil.virtual_memory().percent,
            }

        # 测试动作
        def test_action(message: str = "测试任务"):
            """测试任务"""
            return {
                "message": message,
                "timestamp": datetime.now().isoformat(),
                "success": True
            }

        async def llm_generate_action(
            prompt: str,
            role: str = "assistant",
            user_id: str = "java-service",
            model: str = "",
            persona: str = "",
        ):
            """调用 LLM 生成内容，结果推送到聊天窗口（作为 AI 气泡显示）。

            user_id/model/persona 用于还原任务创建时的用户上下文（模型+角色），
            使定时任务与主对话保持相同的人格和模型。
            """
            agent = self._agent
            if agent is None:
                _push_notification(f"[llm_generate] agent 未初始化: {prompt[:60]}", role="system")
                return {"success": False, "error": "agent not initialized"}
            try:
                # 还原用户的 provider（模型）
                provider_override = None
                if self._provider_getter:
                    try:
                        _uid = user_id or "java-service"
                        provider_override = self._provider_getter(_uid)
                        if provider_override:
                            logger.debug(f"[llm_generate] 还原用户 {_uid} 的 provider: {provider_override.current_model}")
                    except Exception as _prov_err:
                        logger.warning(f"[llm_generate] 还原用户 provider 失败（使用默认）: {_prov_err}")

                # 限制并发 LLM 调用：
                #   _llm_sem(1) 防止多个 llm_generate 任务互相碰撞；
                #   _inference_slot 让定时任务和用户 HTTP 请求共享同一个推理队列，
                #   避免 llm_generate 绕过 INFERENCE_CONCURRENCY 限制直接打爆 Ollama。
                # 每次检测当前 event loop：loop 换了（降级路径/热重载）就重建 Semaphore，
                # 避免 "Semaphore bound to a different event loop" RuntimeError。
                _cur_loop = asyncio.get_running_loop()
                if (SimpleTaskScheduler._llm_sem is None
                        or SimpleTaskScheduler._llm_loop is not _cur_loop):
                    SimpleTaskScheduler._llm_sem = asyncio.Semaphore(1)
                    SimpleTaskScheduler._llm_loop = _cur_loop

                _slot = self._inference_slot
                _use_slot = _slot is not None

                _chat_kwargs = dict(
                    use_tools=False,
                    use_memory=False,
                    provider_override=provider_override,
                    skip_cache=True,
                )
                async with SimpleTaskScheduler._llm_sem:
                    if _use_slot:
                        try:
                            async with _slot(queue_timeout=60.0):
                                result = await agent.chat(prompt, **_chat_kwargs)
                        except RuntimeError as _re:
                            if "bound to a different event loop" in str(_re):
                                # Semaphore created in a different loop (fallback path); skip slot
                                logger.warning("[llm_generate] _inference_slot 跨循环，降级直接调用")
                                result = await agent.chat(prompt, **_chat_kwargs)
                            else:
                                raise
                    else:
                        result = await agent.chat(prompt, **_chat_kwargs)
                content = result.get("content", result.get("message", "")) if isinstance(result, dict) else str(result)
                _ERR_PREFIXES = ("请求失败", "模型调用失败", "处理请求时出错", "AI 定时生成失败")
                if any(content.startswith(p) for p in _ERR_PREFIXES):
                    logger.error(f"[llm_generate] 模型返回错误内容，不推送给用户: {content[:80]}")
                    return {"success": False, "error": content}
                ts = datetime.now().isoformat()
                _push_notification(content, ts, role=role)
                try:
                    agent.memory.store(content, category="task", metadata={"source": "scheduler", "role": role, "timestamp": ts})
                except Exception as _me:
                    logger.warning(f"[llm_generate] 写入短期记忆失败: {_me}")
                logger.info(f"[llm_generate] 生成完成，长度 {len(content)}")
                return {"success": True, "length": len(content), "timestamp": ts}
            except Exception as e:
                logger.error(f"[llm_generate] 调用失败: {e}")
                _push_notification(f"AI 定时生成失败: {str(e)[:100]}", role="system")
                return {"success": False, "error": str(e)}

        _HEARTBEAT_DECISION_PROMPT = (
            "现在是一次心跳巡检时刻，不是用户主动发来的消息。"
            "基于你对这位用户近期对话和长期记忆的了解，判断现在是否有必要主动联系他。"
            "只有在确实有值得说的事（重要提醒、明显的关心时机、超过较长时间未联系等）时才选择联系；"
            "没有合适理由就保持沉默，不要为了说话而说话。\n\n"
            "严格按以下格式输出，不要寒暄，不要多余说明：\n"
            "- 不需要联系：只输出 SILENT\n"
            "- 需要联系：输出 SPEAK: 后接你要发送的完整消息内容"
        )
        _QUIET_HOUR_START = 23  # [23:00, 次日08:00) 默认安静时段，不主动联系
        _QUIET_HOUR_END = 8

        async def heartbeat_check_action(
            receiver_id: str,
            receive_id_type: str = "open_id",
            user_id: str = "java-service",
            quiet_hour_start: int = _QUIET_HOUR_START,
            quiet_hour_end: int = _QUIET_HOUR_END,
        ):
            """心跳巡检：让 LLM 判断当前是否需要主动联系用户，需要才通过 im_message 发送。

            与 llm_generate 的区别：llm_generate 总是把结果推给用户；heartbeat_check 默认沉默，
            只有 LLM 明确给出 SPEAK 判定时才真正发送消息，避免无意义的主动打扰。
            生成判定文本时固定使用 channel="feishu_im"，保证一旦决定发送，内容已经是 IM 克制风格
            （不会带出私密档案段），无需在发送前二次过滤。
            """
            _now_hour = datetime.now().hour
            _in_quiet = (
                _now_hour >= quiet_hour_start or _now_hour < quiet_hour_end
                if quiet_hour_start > quiet_hour_end
                else quiet_hour_start <= _now_hour < quiet_hour_end
            )
            if _in_quiet:
                logger.debug(f"[heartbeat_check] 安静时段（{quiet_hour_start}:00-{quiet_hour_end}:00），跳过")
                return {"success": True, "sent": False, "reason": "quiet_hours"}

            agent = self._agent
            if agent is None:
                return {"success": False, "error": "agent not initialized"}
            try:
                result = await agent.chat(
                    _HEARTBEAT_DECISION_PROMPT,
                    use_tools=False,
                    use_memory=True,
                    skip_cache=True,
                    user_id=user_id,
                    channel="feishu_im",
                )
                content = (result.get("content", "") if isinstance(result, dict) else str(result)).strip()
            except Exception as e:
                logger.error(f"[heartbeat_check] LLM 判定调用失败: {e}")
                return {"success": False, "error": str(e)}

            if not content.upper().startswith("SPEAK:"):
                if content.upper() != "SILENT":
                    logger.warning(f"[heartbeat_check] LLM 输出格式不符合约定，按沉默处理: {content[:80]}")
                return {"success": True, "sent": False, "reason": "silent"}

            message = content.split(":", 1)[1].strip()
            if not message:
                return {"success": True, "sent": False, "reason": "empty_speak"}

            _tm = self._tool_manager
            if _tm is None:
                from tools.tool_manager import tool_manager as _global_tm
                _tm = _global_tm
            im_tool = _tm.get_tool("im_message")
            if im_tool is None:
                logger.warning("[heartbeat_check] im_message 工具未注册，无法发送，判定结果丢弃")
                return {"success": False, "error": "im_message tool not registered", "message": message}

            try:
                _send_result = im_tool(
                    receiver_id=receiver_id,
                    msg_type="text",
                    content={"text": message},
                    receive_id_type=receive_id_type,
                )
                # BaseTool.__call__ 内部吞掉异常，包装成 ToolResult(success=False, error=...)，
                # 不会向外抛异常，必须显式检查 success 字段才能感知发送失败。
                if hasattr(_send_result, "success") and not _send_result.success:
                    raise RuntimeError(getattr(_send_result, "error", "未知错误"))
            except Exception as e:
                logger.error(f"[heartbeat_check] im_message 发送失败: {e}")
                return {"success": False, "error": str(e), "message": message}

            ts = datetime.now().isoformat()
            try:
                agent.memory.store(message, category="task", metadata={"source": "heartbeat_check", "role": "assistant", "timestamp": ts})
            except Exception as _me:
                logger.warning(f"[heartbeat_check] 写入短期记忆失败: {_me}")
            logger.info(f"[heartbeat_check] 主动联系已发送，长度 {len(message)}")
            return {"success": True, "sent": True, "message": message, "timestamp": ts}

        self.register_action("log", log_action)
        self.register_action("log_action", log_action)   # backward-compat alias
        self.register_action("llm_generate", llm_generate_action)
        self.register_action("heartbeat_check", heartbeat_check_action)
        self.register_action("system_info", system_info_action)
        self.register_action("test", test_action)

    def register_action(self, name: str, func: Callable):
        """注册动作"""
        self.actions[name] = func
        logger.debug(f"注册任务动作: {name}")

    def create_task(self,
                   name: str,
                   action: str,
                   args: Dict[str, Any] = None,
                   description: str = "",
                   schedule_type: str = "immediate",
                   **schedule_kwargs) -> SimpleTask:
        """创建任务"""

        task = SimpleTask(
            name=name,
            description=description,
            action=action,
            args=args or {},
            schedule_type=schedule_type,
        )

        # 设置调度参数
        if schedule_type == "interval":
            task.interval_seconds = schedule_kwargs.get("interval_seconds", 60)
        elif schedule_type == "delay":
            task.delay_seconds = schedule_kwargs.get("delay_seconds", 60)
        elif schedule_type == "datetime":
            run_at = schedule_kwargs.get("run_at")
            if isinstance(run_at, str):
                run_at = datetime.fromisoformat(run_at)
            task.run_at = run_at
        elif schedule_type == "cron":
            task.cron_expression = schedule_kwargs.get("cron_expression")

        task.max_runs = schedule_kwargs.get("max_runs")
        task.max_retries = schedule_kwargs.get("max_retries", 3)
        task.tags = schedule_kwargs.get("tags", [])
        task.metadata = schedule_kwargs.get("metadata", {})

        # 计算下次运行时间
        task.next_run = task.calculate_next_run()

        # 存储任务并立即持久化（任务创建是显式操作，需即时写盘）
        self.tasks[task.id] = task
        with self._file_lock:
            self._flush_tasks_now()

        logger.info(f"创建任务: {name} ({task.id})，计划类型: {schedule_type}")
        return task

    def create_interval_task(self,
                           name: str,
                           action: str,
                           interval_seconds: int = 60,
                           **kwargs) -> SimpleTask:
        """创建间隔任务"""
        return self.create_task(
            name=name,
            action=action,
            schedule_type="interval",
            interval_seconds=interval_seconds,
            **kwargs
        )

    def create_delayed_task(self,
                          name: str,
                          action: str,
                          delay_seconds: int = 60,
                          **kwargs) -> SimpleTask:
        """创建延迟任务"""
        return self.create_task(
            name=name,
            action=action,
            schedule_type="delay",
            delay_seconds=delay_seconds,
            **kwargs
        )

    def create_scheduled_task(self,
                            name: str,
                            action: str,
                            run_at: datetime,
                            **kwargs) -> SimpleTask:
        """创建定时任务"""
        return self.create_task(
            name=name,
            action=action,
            schedule_type="datetime",
            run_at=run_at,
            **kwargs
        )

    def create_cron_task(self,
                         name: str,
                         action: str,
                         cron_expression: str,
                         **kwargs) -> SimpleTask:
        """创建 Cron 定时任务。

        Args:
            name: 任务名称
            action: 执行动作
            cron_expression: 标准 5 字段 cron 表达式，如 "0 9 * * 1-5"（工作日 9:00）
        """
        return self.create_task(
            name=name,
            action=action,
            schedule_type="cron",
            cron_expression=cron_expression,
            **kwargs
        )

    async def execute_task(self, task_id: str) -> Dict[str, Any]:
        """执行任务。由 _run_with_cleanup 通过 _submitted_ids 机制保证不重复提交，
        此处无需再做 RUNNING 状态判断（避免 pre-mark 与 guard 互相干扰）。"""
        if task_id not in self.tasks:
            return {"success": False, "error": f"任务不存在: {task_id}"}

        with self._tasks_lock:
            task = self.tasks.get(task_id)
        if task is None:
            return {"success": False, "error": f"任务不存在: {task_id}"}

        # 更新状态（持锁写，调度线程的 should_run() 读也受同一锁保护）
        with self._tasks_lock:
            task.status = SimpleTaskStatus.RUNNING
            task.started_at = datetime.now()
            task.last_run = datetime.now()

        try:
            # 获取动作
            if task.action in self.actions:
                action_func = self.actions[task.action]
            else:
                # 尝试从工具管理器获取（优先使用 per-agent 实例，回退到全局）
                _tm = self._tool_manager
                if _tm is None:
                    from tools.tool_manager import tool_manager as _global_tm
                    _tm = _global_tm
                tool = _tm.get_tool(task.action)
                if tool:
                    action_func = tool
                else:
                    raise ValueError(f"未找到动作: {task.action}")

            # 执行动作（释放锁后执行，避免长时间持锁阻塞调度线程）
            logger.info(f"执行任务: {task.name} ({task_id})")

            if asyncio.iscoroutinefunction(action_func):
                result = await action_func(**task.args)
            elif hasattr(action_func, '__call__'):
                result = action_func(**task.args)
            else:
                raise ValueError(f"无法执行动作: {task.action}")

            # 更新任务状态（持锁写）
            with self._tasks_lock:
                task.completed_at = datetime.now()
                task.run_count += 1
                task.last_result = result
                task.last_error = None
                task.retry_count = 0

                if task.schedule_type == "interval":
                    task.status = SimpleTaskStatus.PENDING
                    task.next_run = datetime.now() + timedelta(seconds=task.interval_seconds)
                    logger.info(f"周期任务完成，下次执行: {task.next_run}, 任务: {task.name}")
                elif task.schedule_type == "cron":
                    task.status = SimpleTaskStatus.PENDING
                    task.next_run = task.calculate_next_run()
                    logger.info(f"Cron 任务完成，下次执行: {task.next_run}, 任务: {task.name}")
                else:
                    task.status = SimpleTaskStatus.COMPLETED
                    logger.info(f"任务执行成功: {task.name} (运行次数: {task.run_count})")

            self._save_tasks()  # sets dirty flag only; actual write batched by _flush_tasks_if_dirty

            return {
                "success": True,
                "task_id": task_id,
                "task_name": task.name,
                "result": result,
                "run_count": task.run_count,
                "duration": (task.completed_at - task.started_at).total_seconds()
            }

        except asyncio.CancelledError:
            # CancelledError 继承 BaseException 不是 Exception，必须单独捕获。
            # asyncio 取消协程（服务重启/外部 cancel()）时会抛此异常，若不处理则
            # task.status 永久卡在 RUNNING，调度器永远不会再触发该任务。
            logger.warning(f"任务被取消: {task.name}")
            with self._tasks_lock:
                task.completed_at = datetime.now()
                task.last_error = "任务被取消"
                # interval/cron 保持可调度；一次性任务标为 FAILED
                if task.schedule_type in ("interval", "cron"):
                    task.status = SimpleTaskStatus.PENDING
                    task.next_run = task.calculate_next_run()
                else:
                    task.status = SimpleTaskStatus.FAILED
            self._save_tasks()  # sets dirty flag only; actual write batched by _flush_tasks_if_dirty
            raise  # 必须重新抛出，让 asyncio 正确完成取消流程

        except Exception as e:
            logger.error(f"任务执行失败: {task.name} - {e}")

            with self._tasks_lock:
                task.status = SimpleTaskStatus.FAILED
                task.completed_at = datetime.now()
                task.last_error = str(e)
                task.retry_count += 1

            # 检查是否需要重试
            if task.retry_count < task.max_retries:
                logger.info(f"准备重试任务: {task.name} (重试 {task.retry_count}/{task.max_retries})")
                with self._tasks_lock:
                    task.status = SimpleTaskStatus.PENDING
                    task.next_run = datetime.now() + timedelta(seconds=10)
            else:
                logger.error(f"任务达到最大重试次数: {task.name}")

            self._save_tasks()  # sets dirty flag only; actual write batched by _flush_tasks_if_dirty

            return {
                "success": False,
                "task_id": task_id,
                "task_name": task.name,
                "error": str(e),
                "retry_count": task.retry_count
            }
        finally:
            # interval/cron 已在成功路径里设置了 next_run，其他 PENDING 状态（重试）计算一次
            if task.status == SimpleTaskStatus.PENDING and task.schedule_type not in ("interval", "cron"):
                task.next_run = task.calculate_next_run()

    @staticmethod
    async def _make_semaphore(n: int) -> asyncio.Semaphore:
        """在当前事件循环中创建 Semaphore（run_coroutine_threadsafe 需要此辅助）。"""
        return asyncio.Semaphore(n)

    async def _run_with_cleanup(self, task_id: str, sem: asyncio.Semaphore,
                               submitted_ids: set) -> None:
        """持有信号量执行任务，完成后从 submitted_ids 移除，允许下次再提交。"""
        try:
            async with sem:
                await self.execute_task(task_id)
        finally:
            submitted_ids.discard(task_id)

    def _check_tasks_loop(self):
        """检查任务循环（含并发度限制）。

        使用 _submitted_ids 集合防止双重提交：
        - 调度器线程：add(task_id) → 标记"已提交尚未执行"
        - 事件循环执行完成后：discard(task_id) → 允许下次再提交
        这样即使事件循环存在短暂延迟，任务也不会被重复提交。
        同时不修改 task.status（避免与 execute_task 内部状态管理冲突）。
        """
        _task_sem = None
        _submitted_ids: set = set()  # 已提交到 event loop 但尚未开始的任务 ID

        while self.running:
            try:
                # 检查需要运行的任务（持锁快照，避免与 execute_task 写操作竞争）
                tasks_to_run = []
                with self._tasks_lock:
                    # llm_generate 任务串行：已有 LLM 任务 RUNNING 时，跳过其他 LLM 任务，
                    # 避免多个 LLM 任务同时显示 RUNNING（实际上只有一个能执行推理）
                    llm_is_running = any(
                        t.action == 'llm_generate' and t.status == SimpleTaskStatus.RUNNING
                        for t in self.tasks.values()
                    )
                    for task_id, task in list(self.tasks.items()):
                        if task_id not in _submitted_ids and task.should_run():
                            if task.action == 'llm_generate' and llm_is_running:
                                continue  # 等当前 LLM 任务完成后再提交
                            tasks_to_run.append(task_id)

                # 执行任务（带并发度限制）
                for task_id in tasks_to_run:
                    _submitted_ids.add(task_id)   # 立即标记，防止本次循环内重复

                    if self.main_loop and self.main_loop.is_running():
                        # 延迟初始化信号量（必须在目标事件循环中创建）
                        if _task_sem is None:
                            max_concurrent = getattr(
                                self, '_max_concurrent_tasks',
                                int(getattr(__import__('config.settings', fromlist=['settings']).settings,
                                            'scheduler_max_concurrent_tasks', 5))
                            )
                            fut = asyncio.run_coroutine_threadsafe(
                                self._make_semaphore(max_concurrent), self.main_loop
                            )
                            _task_sem = fut.result(timeout=5)

                        asyncio.run_coroutine_threadsafe(
                            self._run_with_cleanup(task_id, _task_sem, _submitted_ids),
                            self.main_loop
                        )
                    else:
                        _submitted_ids.discard(task_id)
                        loop = asyncio.new_event_loop()
                        # 不调 set_event_loop：在多线程环境下会污染其他线程的 event loop
                        try:
                            loop.run_until_complete(self.execute_task(task_id))
                        finally:
                            loop.close()

                # 清理已完成的任务
                self._cleanup_completed_tasks()

                # 恢复卡在 RUNNING 状态超时的任务（防止任务永久死锁）
                self._recover_stuck_tasks()

                # 批量写盘（每10s最多一次，减少高频 interval 任务的磁盘 I/O）
                self._flush_tasks_if_dirty()

                # 等待下次检查
                time.sleep(self.check_interval)

            except Exception as e:
                logger.error(f"调度器检查循环错误: {e}")
                time.sleep(self.check_interval)

    def _cleanup_completed_tasks(self, max_completed_age: int = 3600):
        """清理已完成的任务（超过1小时）"""
        cutoff_time = datetime.now() - timedelta(seconds=max_completed_age)

        with self._tasks_lock:
            tasks_to_remove = [
                task_id for task_id, task in self.tasks.items()
                if (task.status == SimpleTaskStatus.COMPLETED and
                    task.completed_at and
                    task.completed_at < cutoff_time)
            ]
            for task_id in tasks_to_remove:
                del self.tasks[task_id]
                logger.debug(f"清理已完成的任务: {task_id}")

    def _recover_stuck_tasks(self, max_running_seconds: int = 600):
        """检测并恢复卡在 RUNNING 状态超时的任务（第二道防线）。

        正常情况下 execute_task 的 CancelledError 处理会重置状态；
        但若发生更底层的异常（如 asyncio 内部错误、进程信号）导致
        finally 未运行，状态会永久卡在 RUNNING。
        此方法每个调度周期调用一次，超时后强制恢复。
        """
        now = datetime.now()
        recovered = []
        with self._tasks_lock:
            for task in self.tasks.values():
                if task.status != SimpleTaskStatus.RUNNING:
                    continue
                if not task.started_at:
                    continue
                elapsed = (now - task.started_at).total_seconds()
                if elapsed < max_running_seconds:
                    continue
                logger.warning(
                    f"任务 {task.name!r} 已运行 {elapsed:.0f}s（超过 {max_running_seconds}s），强制恢复"
                )
                task.last_error = f"执行超时（{elapsed:.0f}s），已自动恢复"
                if task.schedule_type in ("interval", "cron"):
                    task.status = SimpleTaskStatus.PENDING
                    task.next_run = task.calculate_next_run()
                else:
                    task.status = SimpleTaskStatus.FAILED
                    task.completed_at = now
                recovered.append(task.name)
        if recovered:
            self._tasks_dirty = True
            logger.info(f"已恢复 {len(recovered)} 个卡死任务: {recovered}")

    def start(self):
        """启动调度器"""
        if self.running:
            logger.warning("调度器已经在运行")
            return

        self.running = True
        # 保存主线程的事件循环
        try:
            self.main_loop = asyncio.get_event_loop()
        except RuntimeError:
            # 如果没有事件循环，创建一个新的
            self.main_loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.main_loop)

        self.check_thread = threading.Thread(target=self._check_tasks_loop, daemon=True)
        self.check_thread.start()

        logger.info("任务调度器已启动")

    def stop(self):
        """停止调度器"""
        self.running = False
        if self.check_thread:
            self.check_thread.join(timeout=5)

        logger.info("任务调度器已停止")

    def get_task(self, task_id: str) -> Optional[SimpleTask]:
        """获取任务"""
        return self.tasks.get(task_id)

    def get_tasks(self,
                  status: Optional[SimpleTaskStatus] = None,
                  tag: Optional[str] = None,
                  limit: int = 100) -> List[SimpleTask]:
        """获取任务列表"""
        tasks = list(self.tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]

        if tag:
            tasks = [t for t in tasks if tag in t.tags]

        # 按下次运行时间排序
        tasks.sort(key=lambda t: t.next_run or datetime.max)

        return tasks[:limit]

    def delete_task(self, task_id: str) -> bool:
        """删除任务"""
        with self._tasks_lock:
            if task_id not in self.tasks:
                return False
            del self.tasks[task_id]
        with self._file_lock:
            self._flush_tasks_now()
        logger.info(f"删除任务: {task_id}")
        return True

    def cancel_task(self, task_id: str) -> bool:
        """取消任务"""
        with self._tasks_lock:
            task = self.tasks.get(task_id)
            if task is None:
                return False
            task.status = SimpleTaskStatus.CANCELLED
        with self._file_lock:
            self._flush_tasks_now()
        logger.info(f"取消任务: {task_id}")
        return True

    def update_task(self, task_id: str, **kwargs) -> Optional[SimpleTask]:
        """更新任务属性。支持修改名称、描述、消息内容、执行间隔等，下次执行时间自动重算。"""
        task = self.tasks.get(task_id)
        if not task:
            return None

        if "name" in kwargs:
            task.name = str(kwargs["name"])
        if "description" in kwargs:
            task.description = str(kwargs["description"])
        if "action" in kwargs and str(kwargs["action"]):
            task.action = str(kwargs["action"])
        if "args" in kwargs and isinstance(kwargs["args"], dict):
            task.args = kwargs["args"]  # 切换 action 类型时整体替换 args，而不是 update

        schedule_changed = False
        if task.schedule_type == "interval" and "interval_seconds" in kwargs:
            v = int(kwargs["interval_seconds"])
            if v > 0:
                task.interval_seconds = v
                schedule_changed = True
        if task.schedule_type == "delay" and "delay_seconds" in kwargs:
            v = int(kwargs["delay_seconds"])
            if v > 0:
                task.delay_seconds = v
                task.status = SimpleTaskStatus.PENDING
                schedule_changed = True
        cron_expr = kwargs.get("cron_expression") or kwargs.get("cron_expr")
        if task.schedule_type == "cron" and cron_expr:
            task.cron_expression = str(cron_expr)
            schedule_changed = True

        if schedule_changed:
            task.next_run = task.calculate_next_run()

        with self._file_lock:
            self._flush_tasks_now()
        logger.info(f"更新任务: {task_id}，字段: {list(kwargs.keys())}")
        return task

    def get_upcoming_tasks(self, limit: int = 10) -> List[Dict[str, Any]]:
        """获取即将执行的任务"""
        pending_tasks = [t for t in self.tasks.values()
                        if t.status == SimpleTaskStatus.PENDING and t.next_run]

        # 按下次运行时间排序
        pending_tasks.sort(key=lambda t: t.next_run or datetime.max)

        upcoming = []
        for task in pending_tasks[:limit]:
            upcoming.append({
                "id": task.id,
                "name": task.name,
                "next_run": task.next_run.isoformat() if task.next_run else None,
                "schedule_type": task.schedule_type,
                "action": task.action
            })

        return upcoming

    def get_stats(self) -> Dict[str, Any]:
        """获取统计信息"""
        total = len(self.tasks)
        by_status = {}

        for task in self.tasks.values():
            status = task.status.value
            by_status[status] = by_status.get(status, 0) + 1

        total_runs = sum(t.run_count for t in self.tasks.values())

        return {
            "total_tasks": total,
            "tasks_by_status": by_status,
            "total_runs": total_runs,
            "upcoming_tasks": len(self.get_upcoming_tasks(100)),
            "scheduler_running": self.running
        }