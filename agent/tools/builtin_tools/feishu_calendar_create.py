"""飞书日历事件创建工具（user_access_token 必须）。

写操作要求用户已完成 OAuth 授权，无授权时直接抛出 OAuthNotAuthorizedError，
不提供 tenant fallback（避免以应用身份静默写入他人日历）。
"""
import json
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import FEISHU_BASE
from services.feishu_oauth import get_valid_token  # noqa: F401 — module-level so tests can patch


class FeishuCalendarCreateTool(BaseTool):
    """在指定飞书日历中创建事件（需要用户 OAuth 授权）。"""

    def __init__(self):
        super().__init__(name="feishu_calendar_create", category="im")
        self.parameters = [
            ToolParameter(
                name="open_id",
                type="string",
                description="用户 open_id（必须已完成飞书 OAuth 授权）",
                required=True,
            ),
            ToolParameter(
                name="calendar_id",
                type="string",
                description="目标日历 ID",
                required=True,
            ),
            ToolParameter(
                name="summary",
                type="string",
                description="事件标题",
                required=True,
            ),
            ToolParameter(
                name="start_time",
                type="string",
                description="开始时间，RFC3339 格式，如 2026-07-01T10:00:00+08:00",
                required=True,
            ),
            ToolParameter(
                name="end_time",
                type="string",
                description="结束时间，RFC3339 格式",
                required=True,
            ),
            ToolParameter(
                name="description",
                type="string",
                description="事件描述（可选）",
                required=False,
                default="",
            ),
        ]

    def execute(
        self,
        open_id: str,
        calendar_id: str,
        summary: str,
        start_time: str,
        end_time: str,
        description: str = "",
    ) -> Any:
        token = get_valid_token(open_id)  # 无授权时直接抛出，不 fallback
        payload = {
            "summary": summary,
            "description": description,
            "start_time": {"timestamp": start_time},
            "end_time": {"timestamp": end_time},
        }
        resp = requests.post(
            f"{FEISHU_BASE}/open-apis/calendar/v4/calendars/{calendar_id}/events",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书日历创建事件失败: {result}")
        return result
