"""飞书 IM 消息发送工具（只发不收，7 种消息类型）。

v2.0：FeishuIMTool 内部委托给 FeishuAdapter（Channel Adapter 抽象层），
保持类名、参数签名、注册路径不变，向后兼容。
"""
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


# 飞书消息内容最大长度（字符数），超过此值会截断风险
_MAX_TEXT_LENGTH = 15000
# 消息发送最大重试次数（message_id 缺失时）
_MAX_MSG_ID_RETRIES = 1


def _verify_message_content(msg_type: str, content: dict) -> None:
    """发送前验证消息内容完整性（TODO-93 失职自查钩子）。

    检查项：content 非空、text 类型有 text 字段且非空、长度合规。
    发现问题时 logger.warning 但不阻断发送（宁可发出也不静默吞掉）。
    """
    if not content:
        logger.warning("[feishu pre-send] content 为空 dict")
        return

    if msg_type == "text":
        text = content.get("text", "")
        if not text or not text.strip():
            logger.warning("[feishu pre-send] text 消息内容为空")
        elif len(text) > _MAX_TEXT_LENGTH:
            logger.warning(
                f"[feishu pre-send] text 消息过长 ({len(text)} > {_MAX_TEXT_LENGTH})，"
                f"飞书可能截断: {text[:80]}..."
            )

    # interactive / post 类型：检查 content 可 JSON 序列化
    try:
        json.dumps(content, ensure_ascii=False)
    except (TypeError, ValueError) as e:
        logger.warning(f"[feishu pre-send] content JSON 序列化失败: {e}")


def _extract_message_id(result: dict):
    """从飞书 API 响应中提取 message_id。返回 str 或 None。"""
    if not result:
        return None
    data = result.get("data", {})
    return data.get("message_id") if isinstance(data, dict) else None


class FeishuIMTool(BaseTool):
    """向飞书用户/群组发送消息。（v2.0：委托给 FeishuAdapter）"""

    def __init__(self, adapter=None):
        super().__init__(name="im_message", category="im")
        # 延迟导入避免循环依赖
        if adapter is None:
            from im.adapters.feishu_adapter import FeishuAdapter
            adapter = FeishuAdapter()
        self._adapter = adapter
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
        """委托给 FeishuAdapter（v2.0 Channel Adapter 抽象层）。

        保持与原接口完全兼容：参数签名不变，返回 dict 不变。
        adapter 不可用时（未配置凭证/测试环境）走原有逻辑。
        """
        # adapter 不可用时走原有逻辑（向后兼容，尤其是测试环境）
        if not self._adapter.enabled:
            return self._legacy_execute(receiver_id, msg_type, content, receive_id_type)

        import asyncio

        chat_type = "group" if receive_id_type == "chat_id" else "p2p"

        if msg_type == "text":
            text = content.get("text", "")
            result = asyncio.run(
                self._adapter.send_text(receiver_id, text, chat_type=chat_type,
                                        receive_id_type=receive_id_type)
            )
        elif msg_type == "interactive":
            result = asyncio.run(
                self._adapter.send_card(receiver_id, content, chat_type=chat_type,
                                        receive_id_type=receive_id_type)
            )
        elif msg_type in ("image", "file", "sticker", "emoji", "post"):
            return self._legacy_execute(receiver_id, msg_type, content, receive_id_type)
        else:
            return {"code": -1, "msg": f"不支持的消息类型: {msg_type}"}

        return {
            "code": 0 if result.success else -1,
            "msg": "ok" if result.success else result.error,
            "data": {"message_id": result.message_id} if result.message_id else {},
        }

    def _legacy_execute(self, receiver_id: str, msg_type: str, content: dict,
                        receive_id_type: str = "open_id") -> dict:
        """原始执行逻辑（adapter 不可用时的向后兼容路径）。"""
        _verify_message_content(msg_type, content)

        result = self._do_send_original(receiver_id, msg_type, content, receive_id_type)

        msg_id = _extract_message_id(result)
        if not msg_id:
            logger.warning(
                f"[feishu post-send] message_id 缺失，重试 1 次 "
                f"(receiver={receiver_id}, msg_type={msg_type})"
            )
            result = self._do_send_original(receiver_id, msg_type, content, receive_id_type)
            msg_id = _extract_message_id(result)
            if not msg_id:
                logger.error(
                    f"[feishu post-send] 重试后仍无 message_id，"
                    f"消息可能未送达 (receiver={receiver_id})"
                )
            else:
                logger.info(f"[feishu post-send] 重试成功，message_id={msg_id}")

        return result

    def _do_send_original(self, receiver_id: str, msg_type: str, content: dict,
                          receive_id_type: str = "open_id") -> dict:
        """原始飞书 API 调用（使用 requests.post，向后兼容 mock）。"""
        token = _get_tenant_access_token()
        resp = requests.post(
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
