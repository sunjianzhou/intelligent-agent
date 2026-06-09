"""调度器管理器（集成到智能体）"""
from typing import Dict, Any, List, Optional
from datetime import datetime, timedelta
from loguru import logger
from scheduler.simple_scheduler import SimpleTaskScheduler
from scheduler.simple_models import SimpleTask, SimpleTaskStatus


class TaskManager:
    """任务管理器"""

    def __init__(self, tool_manager=None):
        """初始化任务管理器

        Args:
            tool_manager: 工具管理器实例。传入 IntelligentAgent 自己的实例可实现
                          per-user 隔离；不传则回退到全局单例（兼容旧调用方）。
        """
        self._tool_manager = tool_manager
        self.scheduler = SimpleTaskScheduler(check_interval=2.0, tool_manager=tool_manager)
        self.scheduler.start()

        # 注册工具
        self._register_tools()

        logger.info("任务管理器已初始化")

    def _register_tools(self):
        """注册工具到调度器 action 表"""
        if self._tool_manager is not None:
            tm = self._tool_manager
        else:
            from tools.tool_manager import tool_manager
            tm = tool_manager

        for tool_name, tool in tm.get_all_tools().items():
            self.scheduler.register_action(tool_name, tool)
            logger.debug(f"注册工具到调度器: {tool_name}")

    def create_reminder(self,
                        message: str,
                        remind_in_seconds: int = 60,
                        reminder_name: str = None) -> SimpleTask:
        """创建一次性提醒任务"""
        name = reminder_name or f"提醒: {message[:20]}..."
        return self.scheduler.create_task(
            name=name,
            action="log",
            args={"message": f"⏰ {message}", "level": "INFO", "role": "assistant"},
            schedule_type="delay",
            delay_seconds=remind_in_seconds,
            tags=["reminder", "notification"],
        )

    def create_interval_reminder(self,
                                  message: str,
                                  interval_seconds: int = 3600,
                                  reminder_name: str = None) -> "SimpleTask":
        """创建周期性提醒任务（每隔 interval_seconds 秒执行一次）"""
        name = reminder_name or f"周期提醒: {message[:20]}..."
        return self.scheduler.create_task(
            name=name,
            action="log",
            args={"message": f"⏰ {message}", "level": "INFO", "role": "assistant"},
            schedule_type="interval",
            interval_seconds=interval_seconds,
            tags=["reminder", "periodic", "notification"],
        )

    def create_periodic_task(self,
                             name: str,
                             action: str,
                             interval_seconds: int = 300,  # 5分钟
                             **kwargs) -> SimpleTask:
        """创建定期任务"""
        return self.scheduler.create_interval_task(
            name=name,
            action=action,
            interval_seconds=interval_seconds,
            tags=["periodic", "monitor"],
            **kwargs
        )

    def create_daily_task(self,
                          name: str,
                          action: str,
                          hour: int = 9,
                          minute: int = 0,
                          **kwargs) -> SimpleTask:
        """创建每日任务"""
        # 计算今天的时间
        now = datetime.now()
        run_at = now.replace(hour=hour, minute=minute, second=0, microsecond=0)

        # 如果今天的时间已过，设置为明天
        if run_at < now:
            run_at += timedelta(days=1)

        return self.scheduler.create_scheduled_task(
            name=name,
            action=action,
            run_at=run_at,
            tags=["daily"],
            **kwargs
        )

    def get_task_info(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取任务信息"""
        task = self.scheduler.get_task(task_id)
        if not task:
            return None

        return {
            "task": task.to_dict(),
            "is_upcoming": task.status == SimpleTaskStatus.PENDING and task.next_run
        }

    def list_tasks(self,
                   status: Optional[str] = None,
                   tag: Optional[str] = None,
                   limit: int = 20) -> List[Dict[str, Any]]:
        """列出任务"""
        status_enum = None
        if status:
            try:
                status_enum = SimpleTaskStatus(status)
            except ValueError:
                pass

        tasks = self.scheduler.get_tasks(status=status_enum, tag=tag, limit=limit)

        return [task.to_dict() for task in tasks]

    def get_upcoming_reminders(self, limit: int = 10) -> List[Dict[str, Any]]:
        """获取即将到来的提醒"""
        tasks = self.scheduler.get_tasks(tag="reminder", limit=limit)

        reminders = []
        for task in tasks:
            if task.next_run:
                reminders.append({
                    "id": task.id,
                    "name": task.name,
                    "message": task.args.get("message", "").replace("🔔 提醒: ", ""),
                    "time": task.next_run.isoformat(),
                    "in_seconds": int((task.next_run - datetime.now()).total_seconds())
                })

        # 按时间排序
        reminders.sort(key=lambda r: r["time"])

        return reminders[:limit]

    async def execute_task_now(self, task_id: str) -> Dict[str, Any]:
        """立即执行任务"""
        return await self.scheduler.execute_task(task_id)

    def delete_task(self, task_id: str) -> Dict[str, Any]:
        """删除任务"""
        success = self.scheduler.delete_task(task_id)

        return {
            "success": success,
            "task_id": task_id,
            "message": "任务已删除" if success else "任务删除失败"
        }

    def cancel_task(self, task_id: str) -> Dict[str, Any]:
        """取消任务"""
        success = self.scheduler.cancel_task(task_id)

        return {
            "success": success,
            "task_id": task_id,
            "message": "任务已取消" if success else "任务取消失败"
        }

    def get_stats(self) -> Dict[str, Any]:
        """获取统计信息"""
        return self.scheduler.get_stats()

    def stop(self):
        """停止任务管理器"""
        self.scheduler.stop()
        logger.info("任务管理器已停止")


# 全局默认实例（供 CLI / 测试等单用户场景使用）
# ⚠️ 重要：FastAPI / IntelligentAgent 场景下绝对不要用这个全局实例！
# Agent 会创建自己的 TaskManager(tool_manager=self.tool_manager) 专属实例。
# 若此处自动创建，会产生两个 Scheduler 同时读写同一个 tasks.json，导致：
#   1. 每个任务被执行两次（两个 Scheduler 各触发一次）
#   2. 删除无效（只删 agent Scheduler 内存，全局 Scheduler 仍持有并继续跑）
#   3. 文件竞写（两个 _save_tasks() 互相覆盖）
# 因此这里改为 None，CLI/测试按需调用 _get_global_task_manager() 懒初始化。
task_manager = None

def _get_global_task_manager() -> "TaskManager":
    """CLI / 单元测试专用：懒加载全局 TaskManager，首次调用时创建。"""
    global task_manager
    if task_manager is None:
        task_manager = TaskManager()
    return task_manager