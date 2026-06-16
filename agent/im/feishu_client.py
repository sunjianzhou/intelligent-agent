"""飞书 IM 消息发送工具（只发不收，7 种消息类型）。"""
import json
import os
import time
from typing import Any

import requests
from loguru import logger

from tools.base_tool import BaseTool, ToolParameter

FEISHU_BASE = "https://open.feishu.cn"

_token_cache: dict = {"token": None, "expiry": 0.0}


def _get_tenant_access_token() -> str:
    now = time.time()
    if _token_cache["token"] and now < _token_cache["expiry"] - 300:
        return _token_cache["token"]

    app_id     = os.environ.get("FEISHU_APP_ID", "")
    app_secret = os.environ.get("FEISHU_APP_SECRET", "")
    if not app_id or not app_secret:
        raise RuntimeError("FEISHU_APP_ID / FEISHU_APP_SECRET 未配置")

    resp = requests.post(
        f"{FEISHU_BASE}/open-apis/auth/v3/tenant_access_token/internal",
        json={"app_id": app_id, "app_secret": app_secret},
        timeout=10,
    )
    resp.raise_for_status()
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"获取 token 失败: {data}")

    _token_cache["token"]  = data["tenant_access_token"]
    _token_cache["expiry"] = now + data["expire"]
    return _token_cache["token"]


class FeishuIMTool(BaseTool):
    """向飞书用户/群组发送消息。"""

    def __init__(self):
        super().__init__(name="im_message", category="im")
        # 覆盖自动推导的参数，提供精确的描述和类型信息
        self.parameters = [
            ToolParameter(
                name="receiver_id",
                type="string",
                description="接收方ID",
                required=True,
            ),
            ToolParameter(
                name="msg_type",
                type="string",
                description="消息类型(text/post/interactive/image/file/sticker/emoji)",
                required=True,
            ),
            ToolParameter(
                name="content",
                type="object",
                description="消息内容dict",
                required=True,
            ),
            ToolParameter(
                name="receive_id_type",
                type="string",
                description="ID类型(open_id/union_id/user_id/chat_id)",
                required=False,
                default="open_id",
            ),
        ]

    def execute(
        self,
        receiver_id: str,
        msg_type: str,
        content: dict,
        receive_id_type: str = "open_id",
    ) -> Any:
        token = _get_tenant_access_token()
        resp  = requests.post(
            f"{FEISHU_BASE}/open-apis/im/v1/messages",
            params={"receive_id_type": receive_id_type},
            headers={"Authorization": f"Bearer {token}",
                     "Content-Type": "application/json; charset=utf-8"},
            json={
                "receive_id": receiver_id,
                "msg_type":   msg_type,
                "content":    json.dumps(content, ensure_ascii=False),
            },
            timeout=15,
        )
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") != 0:
            logger.warning(f"飞书发送消息失败: {result}")
        return result
