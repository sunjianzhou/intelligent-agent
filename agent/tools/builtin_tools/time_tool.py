"""时间工具"""
from datetime import datetime
import time as time_module
from tools.base_tool import BaseTool, ToolResult, ToolParameter


class TimeTool(BaseTool):
    """时间工具

    获取当前时间、日期、执行时间计算等
    """

    def __init__(self, name: str = "TimeTool", description: str = None, category: str = "utility"):
        """初始化时间工具"""
        description = description or "获取当前时间。参数: action(操作类型, 默认为'current_time')"
        super().__init__(name=name, description=description, category=category)

    def execute(self, action: str = "current_time", **kwargs) -> dict:
        """执行时间工具

        Args:
            action: 操作类型
                - "current_time": 获取当前时间
                - "formatted": 获取格式化的当前时间
                - "timestamp": 获取时间戳
            **kwargs: 其他参数

        Returns:
            时间信息
        """
        now = datetime.now()

        if action == "current_time":
            return {
                "timestamp": now.isoformat(),
                "formatted": now.strftime("%Y-%m-%d %H:%M:%S"),
                "date": now.strftime("%Y-%m-%d"),
                "time": now.strftime("%H:%M:%S"),
                "timezone": "UTC+8"  # 假设东八区
            }
        elif action == "formatted":
            format_str = kwargs.get("format", "%Y-%m-%d %H:%M:%S")
            return {
                "formatted": now.strftime(format_str),
                "format": format_str
            }
        elif action == "timestamp":
            return {
                "timestamp": now.timestamp(),
                "iso": now.isoformat()
            }
        else:
            # 默认返回完整信息
            return {
                "timestamp": now.timestamp(),
                "iso": now.isoformat(),
                "formatted": now.strftime("%Y-%m-%d %H:%M:%S"),
                "year": now.year,
                "month": now.month,
                "day": now.day,
                "hour": now.hour,
                "minute": now.minute,
                "second": now.second,
                "weekday": now.strftime("%A")
            }

    def _get_parameters(self):
        """获取工具参数"""
        return [
            ToolParameter(
                name="action",
                type="str",
                description="操作类型: current_time(默认), formatted, timestamp",
                required=False,
                default="current_time"
            )
        ]