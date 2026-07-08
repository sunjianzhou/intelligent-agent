"""多通道路由器（Python 侧）

能力矩阵：
  - 单通道发送（send_to）
  - 多通道并行广播（broadcast_text，asyncio.gather + 失败隔离）
  - 中央路由（resolve_channels）—— 根据用户偏好/紧急程度选择目标 channel
  - 去重（_dedup_cache）—— 基于 dedup_key 的幂等发送
  - 动态注册/注销 adapter
  - 指标聚合（get_all_metrics）
"""
from __future__ import annotations

import asyncio
import hashlib
import time
from collections import OrderedDict
from typing import Dict, List, Optional

from loguru import logger

from im.channel_adapter import (
    ChannelAdapter,
    ChannelType,
    SendResult,
)

# 去重缓存：LRU 1000 条，TTL 5 分钟
_DEDUP_MAX_SIZE = 1000
_DEDUP_TTL_SEC = 300


class ChannelRouter:
    """多通道路由器

    用法:
        router = ChannelRouter()
        router.register(FeishuAdapter())
        router.register(WebAdapter(ws_manager))

        # 单通道发送
        result = await router.send_to(ChannelType.FEISHU, "ou_xxx", "Hello")

        # 多通道广播（任一失败不影响其他）
        results = await router.broadcast_text(
            "任务完成！",
            receivers={ChannelType.FEISHU: "ou_xxx", ChannelType.WEB: "user_123"},
        )
    """

    def __init__(self):
        self._adapters: Dict[ChannelType, ChannelAdapter] = {}
        self._user_prefs: Dict[str, List[ChannelType]] = {}  # user_id → 偏好顺序
        self._dedup_cache: OrderedDict[str, float] = OrderedDict()  # key → expiry_ts

    # ── 注册 ──────────────────────────────────────────────

    def register(self, adapter: ChannelAdapter) -> None:
        self._adapters[adapter.channel_type] = adapter
        logger.info(f"[ChannelRouter] 注册: {adapter.channel_type.value}")

    def unregister(self, channel_type: ChannelType) -> None:
        self._adapters.pop(channel_type, None)

    def get(self, channel_type: ChannelType) -> Optional[ChannelAdapter]:
        return self._adapters.get(channel_type)

    def list_enabled(self) -> List[ChannelType]:
        return [ct for ct, a in self._adapters.items() if a.enabled]

    # ── 去重 ──────────────────────────────────────────────

    def _make_dedup_key(self, text: str, receivers: Dict[ChannelType, str]) -> str:
        """生成去重键：sha256(text + sorted(receivers))"""
        payload = text + "|" + "|".join(
            f"{ct.value}={rid}"
            for ct, rid in sorted(receivers.items(), key=lambda x: x[0].value)
        )
        return hashlib.sha256(payload.encode()).hexdigest()[:16]

    def _is_duplicate(self, dedup_key: str) -> bool:
        """检查消息是否已发送（LRU + TTL）"""
        if not dedup_key:
            return False
        now = time.time()
        # 清理过期条目
        expired = [k for k, exp in self._dedup_cache.items() if now > exp]
        for k in expired:
            self._dedup_cache.pop(k, None)
        if dedup_key in self._dedup_cache:
            return True
        # 添加 + LRU 淘汰
        self._dedup_cache[dedup_key] = now + _DEDUP_TTL_SEC
        while len(self._dedup_cache) > _DEDUP_MAX_SIZE:
            self._dedup_cache.popitem(last=False)
        return False

    # ── 单通道 ─────────────────────────────────────────────

    async def send_to(self, channel: ChannelType, receiver_id: str,
                      text: str, **kwargs) -> SendResult:
        adapter = self._adapters.get(channel)
        if not adapter or not adapter.enabled:
            return SendResult(success=False, error=f"channel {channel} 不可用",
                              channel=channel)
        return await adapter.send_text(receiver_id, text, **kwargs)

    # ── 多通道广播（失败隔离）───────────────────────────────

    async def broadcast_text(
        self,
        text: str,
        receivers: Dict[ChannelType, str],
        chat_type: str = "p2p",
        fail_fast: bool = False,
        dedup_key: str = "",
    ) -> Dict[ChannelType, SendResult]:
        """向多个 channel 的对应 receiver 广播同一条消息。

        Args:
            text: 消息文本
            receivers: {ChannelType: receiver_id}
            chat_type: "p2p" / "group"
            fail_fast: True = 任一失败立即取消其他（默认 False：失败隔离）
            dedup_key: 去重键。为空时自动从 (text + receivers) 生成

        Returns:
            {ChannelType: SendResult}，包含每个 channel 的发送结果
        """
        # 去重检查
        key = dedup_key or self._make_dedup_key(text, receivers)
        if self._is_duplicate(key):
            logger.debug(f"[ChannelRouter] 重复消息，跳过: dedup_key={key}")
            return {
                ch: SendResult(success=True, channel=ch,
                               error="dedup: skipped")
                for ch in receivers
            }

        async def _send_one(ch: ChannelType, rid: str):
            adapter = self._adapters.get(ch)
            if not adapter or not adapter.enabled:
                return ch, SendResult(success=False, channel=ch,
                                      error=f"channel {ch} 不可用")
            return ch, await adapter.send_text(rid, text, chat_type=chat_type)

        tasks = [_send_one(ch, rid) for ch, rid in receivers.items()]

        if not tasks:
            return {}

        if fail_fast:
            results = await asyncio.gather(*tasks)
        else:
            results = await asyncio.gather(*tasks, return_exceptions=True)

        output: Dict[ChannelType, SendResult] = {}
        for item in results:
            if isinstance(item, Exception):
                logger.error(f"[ChannelRouter] broadcast 异常: {item}")
                continue
            ch, result = item
            output[ch] = result
            if not result.success:
                logger.warning(
                    f"[ChannelRouter] {ch.value} 失败: {result.error}"
                )

        return output

    # ── 中央路由：根据用户偏好选择 channel（补充建议 1）─────────

    def set_user_preferences(self, user_id: str,
                             preferred_channels: List[ChannelType]):
        """设置用户 channel 偏好（优先级排序，高优先在前）"""
        self._user_prefs[user_id] = preferred_channels

    def resolve_channels(
        self,
        user_id: str,
        urgency: str = "normal",  # "low" / "normal" / "high"
    ) -> Dict[ChannelType, str]:
        """中央路由：根据用户偏好 + 紧急程度决定发送到哪些 channel。

        策略优先级：
          1. urgency=high → 所有已启用 channel
          2. 用户显式偏好（_user_prefs[user_id]）
          3. 默认：Web（如果可用）

        Returns:
            {ChannelType: receiver_id} —— 可直接传给 broadcast_text()
        """
        if urgency == "high":
            return {
                ct: self._resolve_receiver_id(ct, user_id)
                for ct in self.list_enabled()
            }

        # 用户显式偏好
        prefs = self._user_prefs.get(user_id)
        if prefs:
            result = {}
            for ct in prefs:
                if ct in self._adapters and self._adapters[ct].enabled:
                    result[ct] = self._resolve_receiver_id(ct, user_id)
            if result:
                return result

        # 默认：Web 优先
        if (ChannelType.WEB in self._adapters and
                self._adapters[ChannelType.WEB].enabled):
            return {ChannelType.WEB: user_id}

        return {}

    def _resolve_receiver_id(self, channel: ChannelType,
                             user_id: str) -> str:
        """将内部 user_id 映射为 channel-specific receiver_id。

        例：用户 "feishu:ou_xxx" → 在飞书 channel 提取 "ou_xxx"
        """
        prefix = channel.value + ":"
        if user_id.startswith(prefix):
            return user_id[len(prefix):]
        return user_id

    # ── 广播到所有或指定 channel ──────────────────────────

    async def broadcast_to_all(
        self, text: str, user_id: str,
        channels: Optional[List[ChannelType]] = None,
        urgency: str = "normal",
    ) -> Dict[ChannelType, SendResult]:
        """向所有（或指定）已启用 channel 广播消息。

        适用于：任务完成通知、系统告警等。
        """
        if channels is None:
            targets = self.resolve_channels(user_id, urgency)
        else:
            targets = {
                ch: self._resolve_receiver_id(ch, user_id) for ch in channels
            }

        if not targets:
            return {}

        return await self.broadcast_text(text, targets)

    # ── 指标聚合 ──────────────────────────────────────────

    def get_all_metrics(self) -> Dict[ChannelType, dict]:
        """获取所有 channel 的指标摘要"""
        return {
            ct: {
                "success_rate": f"{a.metrics.success_rate:.1%}",
                "avg_latency_ms": f"{a.metrics.avg_latency_ms:.0f}",
                "total_success": a.metrics.total_successes,
                "total_failures": a.metrics.total_failures,
                "rate_limit_hits": a.metrics.rate_limit_hits,
            }
            for ct, a in self._adapters.items()
        }
