"""统一 IM 消息发送工具（channel-aware，替代 FeishuIMTool）。

LLM 调用: send_message(channel="feishu_im", receiver_id="ou_xxx",
                      msg_type="text", content={"text": "Hello"})

通过 ChannelRouter 将消息路由到对应 channel 的 adapter。
"""
from __future__ import annotations

import asyncio
from typing import Any

from loguru import logger

from tools.base_tool import BaseTool, ToolParameter
from im.channel_adapter import ChannelType, SendResult


class ChannelMessageTool(BaseTool):
    """统一 IM 消息发送工具（channel-aware）

    LLM 可通过 channel 参数指定目标渠道，由 ChannelRouter 路由到对应 adapter。
    替代原 FeishuIMTool 作为唯一的 IM 发送工具。

    注意：FeishuIMTool（"im_message"）保留注册以向后兼容。
    """

    def __init__(self, router=None):
        super().__init__(name="send_message", category="im")
        # 延迟导入避免循环依赖
        if router is None:
            from im.channel_router import ChannelRouter
            router = ChannelRouter()
        self._router = router
        self.parameters = [
            ToolParameter(
                name="channel", type="string",
                description="目标渠道: feishu_im / wecom / web / telegram",
                required=True,
            ),
            ToolParameter(
                name="receiver_id", type="string",
                description="接收方ID（open_id / chat_id / user_id）",
                required=True,
            ),
            ToolParameter(
                name="msg_type", type="string",
                description="消息类型: text / card / image / file",
                required=True,
            ),
            ToolParameter(
                name="content", type="object",
                description="消息内容 dict（text 类型: {\"text\": \"...\"}）",
                required=True,
            ),
        ]

    def execute(self, channel: str, receiver_id: str, msg_type: str,
                content: dict) -> dict:
        """同步执行（BaseTool 接口要求），内部桥接异步 adapter。

        Args:
            channel: 目标渠道
            receiver_id: 接收方ID
            msg_type: text / card / image / file
            content: 消息内容

        Returns:
            {"success": bool, "message_id": str|None, "error": str|None}
        """
        try:
            ct = ChannelType(channel)
        except ValueError:
            return {"success": False, "error": f"未知 channel: {channel}"}

        adapter = self._router.get(ct)
        if not (adapter and adapter.enabled):
            return {"success": False, "error": f"channel {channel} 不可用"}

        try:
            if msg_type == "text":
                result = asyncio.run(
                    adapter.send_text(receiver_id,
                                      content.get("text", ""))
                )
            elif msg_type == "card":
                result = asyncio.run(
                    adapter.send_card(receiver_id, content)
                )
            elif msg_type == "image":
                result = asyncio.run(
                    adapter.send_image(receiver_id,
                                       content.get("data", b""))
                )
            elif msg_type == "file":
                result = asyncio.run(
                    adapter.send_file(receiver_id,
                                      content.get("path", ""),
                                      content.get("name"))
                )
            else:
                return {"success": False,
                        "error": f"不支持的消息类型: {msg_type}"}

            return {
                "success": result.success,
                "message_id": result.message_id,
                "error": result.error,
            }

        except RuntimeError as e:
            # asyncio.run() 可能因事件循环冲突失败
            logger.warning(f"[ChannelMessageTool] 异步桥接失败: {e}")
            return {"success": False,
                    "error": f"发送失败: {e}"}
