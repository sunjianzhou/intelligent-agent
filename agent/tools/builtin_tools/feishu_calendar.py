"""飞书日历只读工具——查询指定日历在一段时间范围内的事件列表。

权限说明：本工具使用 tenant_access_token（应用身份），只能访问应用被授权访问的日历
（应用自己创建的、或被显式共享给应用的日历），**不能**直接读取某个普通用户的个人日历——
那需要 user_access_token（OAuth 用户授权流程），本工具未实现该授权流程，超出"只读巡检工具"
的范围（见 TODOS.md TODO-82/TODO-84）。
"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import _get_tenant_access_token, FEISHU_BASE


class FeishuCalendarTool(BaseTool):
    """查询指定飞书日历在给定时间范围内的事件列表（只读）。"""

    def __init__(self):
        super().__init__(name="feishu_calendar_list", category="im")
        self.parameters = [
            ToolParameter(
                name="calendar_id",
                type="string",
                description="日历 ID（通过飞书开放平台「日历列表」接口或管理后台获取；应用自建或被共享的日历）",
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
                name="page_size",
                type="int",
                description="每页返回事件数量，默认 50",
                required=False,
                default=50,
            ),
        ]

    def execute(
        self,
        calendar_id: str,
        start_time: str,
        end_time: str,
        page_size: int = 50,
    ) -> Any:
        token = _get_tenant_access_token()
        resp = requests.get(
            f"{FEISHU_BASE}/open-apis/calendar/v4/calendars/{calendar_id}/events",
            params={
                "start_time": start_time,
                "end_time": end_time,
                "page_size": page_size,
            },
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书日历查询失败: {result}")
        return result
