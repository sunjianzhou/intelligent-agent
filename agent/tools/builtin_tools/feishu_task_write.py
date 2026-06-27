"""飞书任务创建/完成工具（user_access_token 必须）。

写操作要求用户已完成 OAuth 授权，无授权时直接抛出 OAuthNotAuthorizedError，
不提供 tenant fallback（避免以应用身份静默创建/完成他人任务）。
"""
import json
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import FEISHU_BASE
from services.feishu_oauth import get_valid_token  # noqa: F401 — module-level so tests can patch


class FeishuTaskWriteTool(BaseTool):
    """创建或完成飞书任务（需要用户 OAuth 授权）。"""

    def __init__(self):
        super().__init__(name="feishu_task_write", category="im")
        self.parameters = [
            ToolParameter(
                name="open_id",
                type="string",
                description="用户 open_id（必须已完成飞书 OAuth 授权）",
                required=True,
            ),
            ToolParameter(
                name="action",
                type="string",
                description="操作类型：create（创建任务）或 complete（完成任务）",
                required=True,
            ),
            ToolParameter(
                name="summary",
                type="string",
                description="任务标题（action=create 时必填）",
                required=False,
                default="",
            ),
            ToolParameter(
                name="task_id",
                type="string",
                description="任务 GUID（action=complete 时必填）",
                required=False,
                default="",
            ),
            ToolParameter(
                name="due_time",
                type="string",
                description="截止时间，Unix 秒级时间戳（可选）",
                required=False,
                default="",
            ),
        ]

    def execute(
        self,
        open_id: str,
        action: str,
        summary: str = "",
        task_id: str = "",
        due_time: str = "",
    ) -> Any:
        if action not in ("create", "complete"):
            raise ValueError(f"不支持的 action={action!r}，只支持 create 或 complete")

        token = get_valid_token(open_id)  # 无授权时直接抛出，不 fallback
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

        if action == "create":
            payload: dict = {"summary": summary}
            if due_time:
                payload["due"] = {"timestamp": due_time}
            resp = requests.post(
                f"{FEISHU_BASE}/open-apis/task/v2/tasks",
                headers=headers,
                data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                timeout=15,
            )
        else:  # complete
            resp = requests.post(
                f"{FEISHU_BASE}/open-apis/task/v2/tasks/{task_id}/complete",
                headers=headers,
                data=b"{}",
                timeout=15,
            )

        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书任务操作失败(action={action}): {result}")
        return result
