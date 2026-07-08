"""ChannelRouter + AdapterFactory 集成测试（L3）。"""
import pytest

from im.channel_adapter import (
    ChannelAdapter,
    ChannelType,
    MessageStatus,
    SendResult,
)
from im.channel_router import ChannelRouter
from im.adapter_factory import ChannelAdapterFactory


# ── Mock Adapters ─────────────────────────────────────────

class _MockSuccessAdapter(ChannelAdapter):
    """总是成功的 mock adapter"""
    def __init__(self, ct=ChannelType.WEB):
        # 注意：必须在 super().__init__() 之前设置 self._ct，
        # 因为基类 __init__ 会通过 channel_type property 访问它。
        self._channel_type = ct
        super().__init__()
        self.sent_texts = []  # 记录发送内容

    @property
    def channel_type(self): return self._channel_type
    @property
    def enabled(self): return True

    async def send_text(self, receiver_id, text, chat_type="p2p"):
        self.sent_texts.append((receiver_id, text))
        return SendResult(success=True, channel=self._channel_type)


class _MockFailAdapter(ChannelAdapter):
    """总是失败的 mock adapter"""
    def __init__(self, ct=ChannelType.WECOM):
        self._channel_type = ct
        super().__init__()

    @property
    def channel_type(self): return self._channel_type
    @property
    def enabled(self): return True

    async def send_text(self, receiver_id, text, chat_type="p2p"):
        return SendResult(success=False, error="mock error",
                          channel=self._channel_type, status=MessageStatus.FAILED)


class _MockDisabledAdapter(ChannelAdapter):
    """已禁用的 mock adapter"""
    def __init__(self):
        self._channel_type = ChannelType.TELEGRAM
        super().__init__()

    @property
    def channel_type(self): return self._channel_type
    @property
    def enabled(self): return False

    async def send_text(self, receiver_id, text, chat_type="p2p"):
        return SendResult(success=False, error="should not be called")


# ═══════════════════════════════════════════════════════════
# ChannelRouter
# ═══════════════════════════════════════════════════════════

class TestChannelRouter:
    @pytest.mark.asyncio
    async def test_send_to_single_channel(self):
        router = ChannelRouter()
        mock = _MockSuccessAdapter()
        router.register(mock)

        result = await router.send_to(ChannelType.WEB, "u1", "hello")
        assert result.success
        assert mock.sent_texts == [("u1", "hello")]

    @pytest.mark.asyncio
    async def test_send_to_disabled_returns_error(self):
        router = ChannelRouter()
        router.register(_MockDisabledAdapter())

        result = await router.send_to(ChannelType.TELEGRAM, "u1", "hello")
        assert not result.success
        assert "不可用" in result.error

    @pytest.mark.asyncio
    async def test_broadcast_two_channels_both_succeed(self):
        router = ChannelRouter()
        f1 = _MockSuccessAdapter(ChannelType.FEISHU)
        f2 = _MockSuccessAdapter(ChannelType.WEB)
        router.register(f1)
        router.register(f2)

        results = await router.broadcast_text("test", {
            ChannelType.FEISHU: "ou_1",
            ChannelType.WEB: "web_1",
        })

        assert results[ChannelType.FEISHU].success
        assert results[ChannelType.WEB].success

    @pytest.mark.asyncio
    async def test_broadcast_partial_failure_isolation(self):
        """一个失败不影响另一个"""
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.FEISHU))
        router.register(_MockFailAdapter(ChannelType.WECOM))

        results = await router.broadcast_text("test", {
            ChannelType.FEISHU: "ou_1",
            ChannelType.WECOM: "wc_1",
        })

        assert results[ChannelType.FEISHU].success
        assert not results[ChannelType.WECOM].success
        assert results[ChannelType.WECOM].error == "mock error"

    @pytest.mark.asyncio
    async def test_dedup_prevents_duplicate_broadcast(self):
        """相同 content+receivers 二次广播被去重"""
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        receivers = {ChannelType.WEB: "u1"}
        r1 = await router.broadcast_text("任务完成", receivers)
        r2 = await router.broadcast_text("任务完成", receivers)

        assert r1[ChannelType.WEB].success
        assert r2[ChannelType.WEB].error == "dedup: skipped"

    @pytest.mark.asyncio
    async def test_different_text_no_dedup(self):
        """不同内容不被去重"""
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.WEB))
        receivers = {ChannelType.WEB: "u1"}

        r1 = await router.broadcast_text("msg A", receivers)
        r2 = await router.broadcast_text("msg B", receivers)

        assert r1[ChannelType.WEB].success
        assert r2[ChannelType.WEB].success
        assert r2[ChannelType.WEB].error is None

    def test_dedup_key_deterministic(self):
        """相同输入生成相同 dedup_key"""
        router = ChannelRouter()
        k1 = router._make_dedup_key("hello", {ChannelType.WEB: "u1"})
        k2 = router._make_dedup_key("hello", {ChannelType.WEB: "u1"})
        assert k1 == k2
        assert len(k1) == 16

    def test_dedup_key_different_order(self):
        """receiver 顺序不影响 dedup_key"""
        router = ChannelRouter()
        k1 = router._make_dedup_key("hello", {
            ChannelType.WEB: "u1", ChannelType.FEISHU: "ou_2",
        })
        k2 = router._make_dedup_key("hello", {
            ChannelType.FEISHU: "ou_2", ChannelType.WEB: "u1",
        })
        assert k1 == k2

    def test_resolve_channels_high_urgency_all_enabled(self):
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.FEISHU))
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        targets = router.resolve_channels("user1", urgency="high")
        assert ChannelType.FEISHU in targets
        assert ChannelType.WEB in targets

    def test_resolve_channels_user_prefs(self):
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.FEISHU))
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        router.set_user_preferences("user1", [ChannelType.WEB])
        targets = router.resolve_channels("user1")
        assert list(targets.keys()) == [ChannelType.WEB]

    def test_resolve_channels_defaults_to_web(self):
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        targets = router.resolve_channels("user1")
        assert ChannelType.WEB in targets

    def test_resolve_channels_no_web_fallback(self):
        """Web 不可用时返回空"""
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.FEISHU))

        targets = router.resolve_channels("user1")
        assert targets == {}

    def test_get_all_metrics(self):
        router = ChannelRouter()
        router.register(_MockSuccessAdapter(ChannelType.WEB))

        metrics = router.get_all_metrics()
        assert ChannelType.WEB in metrics
        assert "success_rate" in metrics[ChannelType.WEB]


# ═══════════════════════════════════════════════════════════
# AdapterFactory
# ═══════════════════════════════════════════════════════════

class TestAdapterFactory:
    def test_create_web_adapter_always_available(self):
        """WebAdapter 无需环境变量，始终创建"""
        adapters = ChannelAdapterFactory.create_all()
        web_adapters = [a for a in adapters if a.channel_type == ChannelType.WEB]
        assert len(web_adapters) == 1

    def test_feishu_adapter_skipped_without_env(self, monkeypatch):
        """无 FEISHU_APP_ID 时跳过飞书 adapter"""
        monkeypatch.delenv("FEISHU_APP_ID", raising=False)
        adapters = ChannelAdapterFactory.create_all()
        feishu = [a for a in adapters if a.channel_type == ChannelType.FEISHU]
        assert len(feishu) == 0

    def test_feishu_adapter_created_with_env(self, monkeypatch):
        """有 FEISHU_APP_ID 时创建飞书 adapter"""
        monkeypatch.setenv("FEISHU_APP_ID", "test")
        monkeypatch.setenv("FEISHU_APP_SECRET", "test")
        adapters = ChannelAdapterFactory.create_all()
        feishu = [a for a in adapters if a.channel_type == ChannelType.FEISHU]
        assert len(feishu) == 1
