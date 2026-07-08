"""Telegram Bot Channel 适配器（Python 侧）

限流：30 次/秒（Telegram Bot API 限制），text/card 共用
Card 大小：无硬限制（由 max_text_length 4096 字符控制）
HTTP：requests.Session() 连接池复用（微优化 2）
"""
from __future__ import annotations

import json as _json
import os
from typing import Optional

import requests as _requests
from loguru import logger

from im.channel_adapter import (
    ChannelAdapter,
    ChannelType,
    MessageStatus,
    SendResult,
    TokenBucket,
    UserInfo,
)

TELEGRAM_API = "https://api.telegram.org"


class TelegramAdapter(ChannelAdapter):
    """Telegram Bot 适配器"""

    def __init__(self, bot_token: str = None):
        super().__init__()
        self._bot_token = bot_token or os.environ.get(
            "TELEGRAM_BOT_TOKEN", ""
        )
        self._rate_limiters = {
            "text": TokenBucket(rate=30, burst=5),
            "card": TokenBucket(rate=30, burst=5),
        }
        self._session = self._init_session()  # 微优化 2

    # ── 标识 ──────────────────────────────────────────────

    @property
    def channel_type(self) -> ChannelType:
        return ChannelType.TELEGRAM

    @property
    def max_text_length(self) -> int:
        return 4096  # Telegram 限制

    @property
    def enabled(self) -> bool:
        return bool(self._bot_token)

    def _extract_raw_id(self, api_response: dict) -> Optional[str]:
        """Telegram 返回 result.message_id（数字）"""
        result = api_response.get("result", {})
        mid = result.get("message_id") if isinstance(result, dict) else None
        return str(mid) if mid else None

    # ── API helper ────────────────────────────────────────

    def _api_url(self, method: str) -> str:
        return f"{TELEGRAM_API}/bot{self._bot_token}/{method}"

    # ── 发送 ───────────────────────────────────────────────

    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        return await self.send_with_retry(
            lambda: self._do_send_sync(receiver_id,
                                       self.truncate_text(text)),
            operation="text",
        )

    async def send_card(self, receiver_id: str, card: dict,
                        chat_type: str = "p2p") -> SendResult:
        """Telegram Inline Keyboard 作为卡片实现"""
        return await self.send_with_retry(
            lambda: self._do_send_sync_card(receiver_id, card),
            operation="card",
        )

    async def send_image(self, receiver_id: str, image_data: bytes,
                         chat_type: str = "p2p") -> SendResult:
        """通过 multipart/form-data 发送图片（骨架）"""
        return SendResult(success=False, error="image 待实现",
                          channel=ChannelType.TELEGRAM,
                          status=MessageStatus.FAILED)

    def _do_send_sync(self, receiver_id: str, text: str) -> SendResult:
        """发送 text 消息"""
        try:
            resp = self._session.post(
                self._api_url("sendMessage"),
                json={
                    "chat_id": receiver_id,
                    "text": text,
                    "parse_mode": "Markdown",
                },
                timeout=10,
            )
            data = resp.json()
            success = data.get("ok", False)
            msg_id = self.extract_message_id(data) if success else None
            return SendResult(
                success=success, message_id=msg_id,
                error=None if success else data.get("description"),
                channel=ChannelType.TELEGRAM,
            )
        except Exception as e:
            return SendResult(
                success=False, error=str(e), channel=ChannelType.TELEGRAM,
                status=MessageStatus.FAILED,
            )

    def _do_send_sync_card(self, receiver_id: str,
                           card: dict) -> SendResult:
        """发送带 inline keyboard 的消息"""
        try:
            text = card.get("title", "") + "\n" + card.get("body", "")
            reply_markup = card.get("inline_keyboard")
            body = {
                "chat_id": receiver_id,
                "text": text,
                "parse_mode": "Markdown",
            }
            if reply_markup:
                body["reply_markup"] = _json.dumps(
                    {"inline_keyboard": reply_markup}
                )
            resp = self._session.post(
                self._api_url("sendMessage"), json=body, timeout=10,
            )
            data = resp.json()
            success = data.get("ok", False)
            msg_id = self.extract_message_id(data) if success else None
            return SendResult(
                success=success, message_id=msg_id,
                channel=ChannelType.TELEGRAM,
            )
        except Exception as e:
            return SendResult(
                success=False, error=str(e), channel=ChannelType.TELEGRAM,
                status=MessageStatus.FAILED,
            )

    # ── 用户信息 ──────────────────────────────────────────

    async def get_user_info(self, user_id: str) -> Optional[UserInfo]:
        try:
            resp = self._session.get(
                self._api_url("getChat"),
                params={"chat_id": user_id},
                timeout=10,
            )
            data = resp.json()
            if data.get("ok"):
                chat = data.get("result", {})
                return UserInfo(
                    user_id=user_id,
                    display_name=chat.get("first_name") or chat.get("title"),
                    channel=ChannelType.TELEGRAM,
                )
        except Exception as e:
            logger.warning(f"[telegram] 获取用户信息失败: {e}")
        return None
