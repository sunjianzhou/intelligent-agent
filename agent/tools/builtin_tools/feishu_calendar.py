"""飞书日历只读工具——查询指定日历在一段时间范围内的事件列表。

open_id 不为空时优先用 user_access_token（个人日历）；否则 fallback 到
tenant_access_token（应用自建/共享日历）。
"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import _get_tenant_access_token, FEISHU_BASE
from services.feishu_oauth import get_valid_token, OAuthNotAuthorizedError


class FeishuCalendarTool(BaseTool):
    """查询飞书日历事件列表（只读）。"""

    def __init__(self):
        super().__init__(name="feishu_calendar_list", category="im")
        self.parameters = [
            ToolParameter(
                name="calendar_id",
                type="string",
                description="日历 ID（应用自建/共享日历，或通过 feishu_calendar_list_cals 获取的个人日历 ID）",
                required=True,
            ),
            ToolParameter(
                name="start_time",
                type="string",
                description="起始时间，Unix 秒级时间戳字符串",
                required=True,
            ),
            ToolParameter(
                name="end_time",
                type="string",
                description="结束时间，Unix 秒级时间戳字符串",
                required=True,
            ),
            ToolParameter(
                name="open_id",
                type="string",
                description="查询个人日历时传入用户 open_id，使用 user_access_token；留空则使用应用身份",
                required=False,
                default="",
            ),
            ToolParameter(
                name="page_size",
                type="int",
                description="每页返回事件数量，默认 50",
                required=False,
                default=50,
            ),
        ]

    def execute(self, calendar_id: str, start_time: str, end_time: str,
                open_id: str = "", page_size: int = 50) -> Any:
        token = self._resolve_token(open_id)
        resp = requests.get(
            f"{FEISHU_BASE}/open-apis/calendar/v4/calendars/{calendar_id}/events",
            params={"start_time": start_time, "end_time": end_time, "page_size": page_size},
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书日历查询失败: {result}")
        return result

    def _resolve_token(self, open_id: str) -> str:
        if open_id:
            try:
                return get_valid_token(open_id)
            except OAuthNotAuthorizedError as e:
                logger.warning(f"user_access_token 获取失败，fallback 到 tenant token: {e}")
        return _get_tenant_access_token()
