"""Channel 通知器（TODO-106）：整合 ChannelRouter 到通知系统。

用法:
    from im.channel_notifier import notify_user

    # 任务完成 → 推送到用户首选 channel
    await notify_user("user_123", "✅ 任务「重构数据库」已完成")

    # 系统告警 → 推送到所有 channel
    await notify_user("user_123", "⚠️ 磁盘空间不足", urgency="high")
"""
from __future__ import annotations

import asyncio
from typing import Dict, Optional

from loguru import logger

from im.channel_adapter import ChannelType, SendResult
from im.channel_router import _get_global_router


async def notify_user(
    user_id: str,
    message: str,
    urgency: str = "normal",
    channels: Optional[list[ChannelType]] = None,
) -> Dict[ChannelType, SendResult]:
    """向用户推送通知。

    Args:
        user_id: 目标用户 ID
        message: 通知消息
        urgency: "low" / "normal" / "high"
        channels: 指定 channel 列表（None = 根据 resolve_channels 自动选择）

    Returns:
        {ChannelType: SendResult}
    """
    router = _get_global_router()
    if router is None:
        logger.warning("[ChannelNotifier] ChannelRouter 未初始化，通知无法发送")
        return {}

    try:
        results = await router.broadcast_to_all(
            message, user_id, channels=channels, urgency=urgency
        )
        return results
    except Exception as e:
        logger.error(f"[ChannelNotifier] 通知发送失败: {e}")
        return {}


def notify_user_sync(
    user_id: str,
    message: str,
    urgency: str = "normal",
    channels: Optional[list[ChannelType]] = None,
) -> Dict[ChannelType, SendResult]:
    """同步版本：供 scheduler 等非 async 上下文使用。"""
    try:
        return asyncio.run(notify_user(user_id, message, urgency, channels))
    except RuntimeError as e:
        logger.warning(f"[ChannelNotifier] 同步通知失败（可能在已有事件循环中）: {e}")
        # 尝试在已有循环中创建任务
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                future = asyncio.run_coroutine_threadsafe(
                    notify_user(user_id, message, urgency, channels), loop
                )
                return future.result(timeout=30)
        except Exception:
            pass
        return {}
