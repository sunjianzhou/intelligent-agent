"""飞书待办（Task）只读工具——查询任务列表。

权限说明：同 feishu_calendar.py——使用 tenant_access_token（应用身份），只能看到应用
被授权访问的任务清单，不能直接读取某个普通用户的私人待办（需要 user_access_token OAuth
用户授权流程，本工具未实现，见 TODOS.md TODO-82/TODO-84）。
"""
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.feishu_client import _get_tenant_access_token, FEISHU_BASE


class FeishuTaskTool(BaseTool):
    """查询飞书任务列表（只读）。"""

    def __init__(self):
        super().__init__(name="feishu_task_list", category="im")
        self.parameters = [
            ToolParameter(
                name="tasklist_guid",
                type="string",
                description="任务清单 GUID；留空则查询应用可见的全部任务",
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

    def execute(self, tasklist_guid: str = "", page_size: int = 50) -> Any:
        token = _get_tenant_access_token()
        params = {"page_size": page_size}
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
