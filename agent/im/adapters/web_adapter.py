"""Web PWA Channel 适配器（Python 侧）

通过 WebSocket 向浏览器推送消息（通知、任务完成等）。
无外部 API → 无限流、无重试、无 token。
"""
from __future__ import annotations

from typing import Optional

from loguru import logger

from im.channel_adapter import (
    ChannelAdapter,
    ChannelType,
    SendResult,
)


class WebAdapter(ChannelAdapter):
    """Web PWA 适配器

    通过 WebSocket 向浏览器推送消息。
    无外部 API → 无限流、无重试、无 token。
    """

    def __init__(self, ws_manager=None):
        super().__init__()
        self._ws_manager = ws_manager  # WebSocket session 管理器

    @property
    def channel_type(self) -> ChannelType:
        return ChannelType.WEB

    @property
    def max_text_length(self) -> int:
        return 3000  # 前端展示限制

    @property
    def enabled(self) -> bool:
        return True  # Web 始终可用

    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        """通过 WebSocket 推送消息到前端用户 session"""
        try:
            if self._ws_manager:
                await self._ws_manager.send_to_user(receiver_id, {
                    "type": "notification",
                    "channel": "web",
                    "content": self.truncate_text(text),
                })
                return SendResult(success=True, channel=ChannelType.WEB)
            return SendResult(success=False, error="WS manager 未初始化",
                              channel=ChannelType.WEB)
        except Exception as e:
            logger.error(f"[web] 发送失败: {e}")
            return SendResult(success=False, error=str(e), channel=ChannelType.WEB)

    async def send_card(self, receiver_id: str, card: dict,
                        chat_type: str = "p2p") -> SendResult:
        """推送卡片到前端（如任务卡片）"""
        try:
            if self._ws_manager:
                await self._ws_manager.send_to_user(receiver_id, {
                    "type": "card",
                    "channel": "web",
                    "card": card,
                })
                return SendResult(success=True, channel=ChannelType.WEB)
            return SendResult(success=False, error="WS manager 未初始化",
                              channel=ChannelType.WEB)
        except Exception as e:
            return SendResult(success=False, error=str(e), channel=ChannelType.WEB)
