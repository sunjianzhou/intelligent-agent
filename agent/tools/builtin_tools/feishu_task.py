"""飞书任务只读工具——查询任务列表。

open_id 不为空时优先用 user_access_token（个人任务）；否则 fallback 到
tenant_access_token（应用可见任务）。
"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import _get_tenant_access_token, FEISHU_BASE
from services.feishu_oauth import get_valid_token, OAuthNotAuthorizedError


class FeishuTaskTool(BaseTool):
    """查询飞书任务列表（只读）。"""

    def __init__(self):
        super().__init__(name="feishu_task_list", category="im")
        self.parameters = [
            ToolParameter(
                name="open_id",
                type="string",
                description="查询个人任务时传入用户 open_id；留空则使用应用身份",
                required=False,
                default="",
            ),
            ToolParameter(
                name="tasklist_guid",
                type="string",
                description="任务清单 GUID；留空则查询所有可见任务",
                required=False,
                default="",
            ),
            ToolParameter(
                name="page_size",
                type="int",
                description="每页返回任务数量，默认 50",
                required=False,
                default=50,
            ),
        ]

    def execute(self, open_id: str = "", tasklist_guid: str = "", page_size: int = 50) -> Any:
        token = self._resolve_token(open_id)
        params: dict = {"page_size": page_size}
        if tasklist_guid:
            params["tasklist_guid"] = tasklist_guid
        resp = requests.get(
            f"{FEISHU_BASE}/open-apis/task/v2/tasks",
            params=params,
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书任务查询失败: {result}")
        return result

    def _resolve_token(self, open_id: str) -> str:
        if open_id:
            try:
                return get_valid_token(open_id)
            except OAuthNotAuthorizedError as e:
                logger.warning(f"user_access_token 获取失败，fallback 到 tenant token: {e}")
        return _get_tenant_access_token()
