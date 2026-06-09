"""简化版任务模型"""
from typing import Dict, Any, Optional, Callable
from datetime import datetime, timedelta
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict
from pydantic.functional_serializers import field_serializer
import uuid
import json


class SimpleTaskStatus(str, Enum):
    """任务状态"""
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class SimpleTask(BaseModel):
    """简化版任务"""

    id: str = Field(default_factory=lambda: f"task_{uuid.uuid4().hex[:8]}")
    name: str
    description: str = ""
    action: str  # 要执行的动作（函数名或工具名）
    args: Dict[str, Any] = Field(default_factory=dict)

    # 调度配置
    schedule_type: str = "immediate"  # immediate, interval, delay, datetime
    interval_seconds: Optional[int] = None
    delay_seconds: Optional[int] = None
    run_at: Optional[datetime] = None
    cron_expression: Optional[str] = None

    # 执行控制
    max_runs: Optional[int] = None
    retry_count: int = 0
    max_retries: int = 3

    # 状态
    status: SimpleTaskStatus = SimpleTaskStatus.PENDING
    created_at: datetime = Field(default_factory=datetime.now)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    last_run: Optional[datetime] = None
    next_run: Optional[datetime] = None
    run_count: int = 0

    # 结果
    last_result: Optional[Any] = None
    last_error: Optional[str] = None

    # 元数据
    tags: list = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict()

    @field_serializer('next_run', 'last_run', 'started_at', 'completed_at', when_used='json')
    def _serialize_dt(self, v: Optional[datetime]) -> Optional[str]:
        return v.isoformat() if v else None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典"""
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "action": self.action,
            "args": self.args,
            "schedule_type": self.schedule_type,
            "interval_seconds": self.interval_seconds,
            "delay_seconds": self.delay_seconds,
            "run_at": self.run_at.isoformat() if self.run_at else None,
            "cron_expression": self.cron_expression,
            "max_runs": self.max_runs,
            "retry_count": self.retry_count,
            "max_retries": self.max_retries,
            "status": self.status.value,
            "created_at": self.created_at.isoformat(),
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "last_run": self.last_run.isoformat() if self.last_run else None,
            "next_run": self.next_run.isoformat() if self.next_run else None,
            "run_count": self.run_count,
            "last_result": (self.last_result if isinstance(self.last_result, (str, int, float, bool, type(None)))
                           else __import__('json').dumps(self.last_result, ensure_ascii=False)),
            "last_error": self.last_error,
            "tags": self.tags,
            "metadata": self.metadata
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "SimpleTask":
        """从字典重建任务（用于从持久化文件恢复）"""
        def _parse_dt(val):
            return datetime.fromisoformat(val) if val else None

        return cls(
            id=data["id"],
            name=data["name"],
            description=data.get("description", ""),
            action=data["action"],
            args=data.get("args", {}),
            schedule_type=data.get("schedule_type", "immediate"),
            interval_seconds=data.get("interval_seconds"),
            delay_seconds=data.get("delay_seconds"),
            run_at=_parse_dt(data.get("run_at")),
            cron_expression=data.get("cron_expression"),
            max_runs=data.get("max_runs"),
            retry_count=data.get("retry_count", 0),
            max_retries=data.get("max_retries", 3),
            status=SimpleTaskStatus(data.get("status", "pending")),
            created_at=_parse_dt(data.get("created_at")) or datetime.now(),
            started_at=_parse_dt(data.get("started_at")),
            completed_at=_parse_dt(data.get("completed_at")),
            last_run=_parse_dt(data.get("last_run")),
            next_run=_parse_dt(data.get("next_run")),
            run_count=data.get("run_count", 0),
            last_result=data.get("last_result"),
            last_error=data.get("last_error"),
            tags=data.get("tags", []),
            metadata=data.get("metadata", {}),
        )

    def should_run(self) -> bool:
        """检查是否应该运行"""
        if self.status != SimpleTaskStatus.PENDING:
            return False

        if self.max_runs and self.run_count >= self.max_runs:
            return False

        if self.schedule_type == "immediate":
            return True

        if self.schedule_type == "delay" and self.delay_seconds:
            if not self.last_run:
                # 首次运行，检查创建时间
                time_since_creation = (datetime.now() - self.created_at).total_seconds()
                return time_since_creation >= self.delay_seconds
            else:
                # 后续运行，检查上次运行时间
                time_since_last = (datetime.now() - self.last_run).total_seconds()
                return time_since_last >= self.delay_seconds

        if self.schedule_type == "interval" and self.interval_seconds:
            if not self.last_run:
                return True
            else:
                time_since_last = (datetime.now() - self.last_run).total_seconds()
                return time_since_last >= self.interval_seconds

        if self.schedule_type == "datetime" and self.run_at:
            return datetime.now() >= self.run_at

        if self.schedule_type == "cron" and self.cron_expression:
            try:
                from croniter import croniter
                base = self.last_run or self.created_at
                next_run = croniter(self.cron_expression, base).get_next(datetime)
                return datetime.now() >= next_run
            except Exception:
                return False

        return False

    def calculate_next_run(self) -> Optional[datetime]:
        """计算下次运行时间"""
        if self.schedule_type == "immediate":
            return datetime.now()

        if self.schedule_type == "delay" and self.delay_seconds:
            if not self.last_run:
                return self.created_at + timedelta(seconds=self.delay_seconds)
            else:
                return self.last_run + timedelta(seconds=self.delay_seconds)

        if self.schedule_type == "interval" and self.interval_seconds:
            if not self.last_run:
                return datetime.now() + timedelta(seconds=self.interval_seconds)
            else:
                return self.last_run + timedelta(seconds=self.interval_seconds)

        if self.schedule_type == "datetime":
            return self.run_at

        if self.schedule_type == "cron" and self.cron_expression:
            try:
                from croniter import croniter
                base = self.last_run or self.created_at
                return croniter(self.cron_expression, base).get_next(datetime)
            except Exception:
                return None

        return None