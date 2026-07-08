"""企业微信 Channel 适配器（Python 侧）

限流：text 100次/分钟(≈1.67/s)，card 同（微优化 1）
重试：3 次指数退避
Card 大小：最大 4KB（微优化 3）
HTTP：requests.Session() 连接池复用（微优化 2）
"""
from __future__ import annotations

import os
import time
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

WECOM_BASE = "https://qyapi.weixin.qq.com"


class WeComAdapter(ChannelAdapter):
    """企业微信适配器"""

    def __init__(self, corp_id: str = None, secret: str = None,
                 agent_id: int = 0):
        super().__init__()
        self._corp_id  = corp_id or os.environ.get("WECOM_CORP_ID", "")
        self._secret   = secret or os.environ.get("WECOM_SECRET", "")
        self._agent_id = agent_id or int(
            os.environ.get("WECOM_AGENT_ID", "0")
        )
        self._token_cache: dict = {"token": None, "expiry": 0.0}
        self._rate_limiters = {
            "text": TokenBucket(rate=1.67, burst=3),  # 100次/分钟
            "card": TokenBucket(rate=1.67, burst=3),
        }
        self._session = self._init_session()  # 微优化 2

    # ── 标识 ──────────────────────────────────────────────

    @property
    def channel_type(self) -> ChannelType:
        return ChannelType.WECOM

    @property
    def max_text_length(self) -> int:
        return 2048  # 企微文本限制

    @property
    def max_card_size(self) -> int:
        return 4 * 1024  # 企微 card 最大 4KB（微优化 3）

    @property
    def enabled(self) -> bool:
        return bool(self._corp_id and self._secret and self._agent_id)

    def _extract_raw_id(self, api_response: dict) -> Optional[str]:
        """企微返回 msgid（数字字符串）"""
        msgid = api_response.get("msgid")
        return str(msgid) if msgid else None

    # ── Token ─────────────────────────────────────────────

    def _get_token(self) -> str:
        now = time.time()
        if self._token_cache["token"] and now < self._token_cache["expiry"] - 300:
            return self._token_cache["token"]
        resp = _requests.get(
            f"{WECOM_BASE}/cgi-bin/gettoken",
            params={"corpid": self._corp_id, "corpsecret": self._secret},
            timeout=10,
        )
        data = resp.json()
        if data.get("errcode") != 0:
            raise RuntimeError(f"企微 token 获取失败: {data}")
        self._token_cache["token"]  = data["access_token"]
        self._token_cache["expiry"] = now + data["expires_in"]
        return self._token_cache["token"]

    # ── 发送 ───────────────────────────────────────────────

    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        return await self.send_with_retry(
            lambda: self._do_send_sync(receiver_id, "text",
                                       self.truncate_text(text)),
            operation="text",
        )

    async def send_card(self, receiver_id: str, card: dict,
                        chat_type: str = "p2p") -> SendResult:
        card = self.enforce_card_size_limit(card)
        return await self.send_with_retry(
            lambda: self._do_send_sync_card(receiver_id, card),
            operation="card",
        )

    def _do_send_sync(self, receiver_id: str, msg_type: str,
                      text_content: str) -> SendResult:
        """发送 text 消息"""
        token = self._get_token()
        body = {
            "touser":  receiver_id,
            "msgtype": "text",
            "agentid": self._agent_id,
            "text":    {"content": text_content},
        }
        try:
            resp = self._session.post(
                f"{WECOM_BASE}/cgi-bin/message/send?access_token={token}",
                json=body, timeout=10,
            )
            data = resp.json()
            errcode = data.get("errcode", -1)
            success = errcode == 0
            msg_id = self.extract_message_id(data) if success else None
            return SendResult(
                success=success, message_id=msg_id,
                error=None if success else data.get("errmsg", f"errcode={errcode}"),
                channel=ChannelType.WECOM,
            )
        except Exception as e:
            return SendResult(
                success=False, error=str(e), channel=ChannelType.WECOM,
                status=MessageStatus.FAILED,
            )

    def _do_send_sync_card(self, receiver_id: str,
                           card: dict) -> SendResult:
        """发送 textcard 消息"""
        token = self._get_token()
        body = {
            "touser":   receiver_id,
            "msgtype":  "textcard",
            "agentid":  self._agent_id,
            "textcard": card,
        }
        try:
            resp = self._session.post(
                f"{WECOM_BASE}/cgi-bin/message/send?access_token={token}",
                json=body, timeout=10,
            )
            data = resp.json()
            success = data.get("errcode") == 0
            msg_id = self.extract_message_id(data) if success else None
            return SendResult(
                success=success, message_id=msg_id,
                channel=ChannelType.WECOM,
            )
        except Exception as e:
            return SendResult(
                success=False, error=str(e), channel=ChannelType.WECOM,
                status=MessageStatus.FAILED,
            )
