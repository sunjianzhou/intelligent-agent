"""TODO-106 Phase 3 集成测试：fallback + notifier + health。"""
import pytest

from im.channel_adapter import (
    ChannelAdapter,
    ChannelType,
    MessageStatus,
    SendResult,
)
from im.channel_router import ChannelRouter


# ── Mock adapters ────────────────────────────────────────

class _MockSuccessAdapter(ChannelAdapter):
    def __init__(self, ct):
        self._channel_type = ct
        super().__init__()

    @property
    def channel_type(self): return self._channel_type
    @property
    def enabled(self): return True

    async def send_text(self, receiver_id, text, chat_type="p2p"):
        return SendResult(success=True, channel=self._channel_type)


class _MockFailAdapter(ChannelAdapter):
    def __init__(self, ct):
        self._channel_type = ct
        super().__init__()

    @property
    def channel_type(self): return self._channel_type
    @property
    def enabled(self): return True

    async def send_text(self, receiver_id, text, chat_type="p2p"):
        return SendResult(success=False, error="mock failure",
                          channel=self._channel_type, status=MessageStatus.FAILED)


# ═════════════════════════════════════════════════════════
# Fallback 测试
# ═════════════════════════════════════════════════════════

class TestFallback:
    @pytest.mark.asyncio
    async def test_fallback_when_primary_fails(self):
        """首选 channel 失败时自动降级到 Web"""
        router = ChannelRouter()
        router.register(_MockFailAdapter(ChannelType.FEISHU))
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        result = await router.send_with_fallback(
            ChannelType.FEISHU, "ou_test", "hello"
        )
        assert result.success
        assert "fallback" in (result.error or "")

    @pytest.mark.asyncio
    async def test_no_fallback_when_primary_succeeds(self):
        """首选成功时不触发 fallback"""
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.FEISHU))

        result = await router.send_with_fallback(
            ChannelType.FEISHU, "ou_test", "hello"
        )
        assert result.success
        assert result.error is None or "fallback" not in result.error

    @pytest.mark.asyncio
    async def test_no_self_fallback(self):
        """不做自身到自身的循环 fallback"""
        router = ChannelRouter()
        router.register(_MockFailAdapter(ChannelType.FEISHU))
        # 没有 Web adapter

        result = await router.send_with_fallback(
            ChannelType.FEISHU, "ou_test", "hello",
            fallback_channel=ChannelType.FEISHU,  # 同 channel，跳过
        )
        assert not result.success


# ═════════════════════════════════════════════════════════
# broadcast_to_all 测试
# ═════════════════════════════════════════════════════════

class TestBroadcastToAll:
    @pytest.mark.asyncio
    async def test_high_urgency_all_enabled(self):
        """urgency=high 时广播所有 channel"""
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.FEISHU))
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        results = await router.broadcast_to_all(
            "alert", "user1", urgency="high"
        )
        assert ChannelType.FEISHU in results
        assert ChannelType.WEB in results
        assert results[ChannelType.FEISHU].success
        assert results[ChannelType.WEB].success

    @pytest.mark.asyncio
    async def test_explicit_channels(self):
        """指定 channels 参数时只发指定的"""
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.FEISHU))
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        results = await router.broadcast_to_all(
            "test", "user1", channels=[ChannelType.FEISHU]
        )
        assert ChannelType.FEISHU in results
        assert ChannelType.WEB not in results


# ═════════════════════════════════════════════════════════
# 全局 Router 单例
# ═════════════════════════════════════════════════════════

class TestGlobalRouter:
    def test_init_and_get(self):
        router = ChannelRouter()
        from im.channel_router import init_global_router, _get_global_router
        init_global_router(router)
        assert _get_global_router() is router

    def test_default_is_none(self):
        from im.channel_router import _get_global_router, init_global_router
        # 重置为 None（不影响其他测试）
        init_global_router(None)
        assert _get_global_router() is None
