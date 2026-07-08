"""Channel Adapter 接口契约测试（L1）。

测试：TokenBucket 限流 / RetryConfig 延迟计算 / ChannelMetric 统计 /
      extract_message_id 归一化格式 / 接口完整性
"""
import pytest
import time

from im.channel_adapter import (
    ChannelType,
    MessageType,
    MessageStatus,
    ChannelMessage,
    SendResult,
    UserInfo,
    RetryConfig,
    TokenBucket,
    ChannelMetric,
    ChannelAdapter,
)


# ═══════════════════════════════════════════════════════════
# TokenBucket
# ═══════════════════════════════════════════════════════════

class TestTokenBucket:
    def test_unlimited_when_rate_zero(self):
        """rate=0 时始终放行"""
        b = TokenBucket(rate=0)
        for _ in range(1000):
            assert b.acquire()

    def test_acquire_depletes_tokens(self):
        """获取令牌消耗计数"""
        b = TokenBucket(rate=10, burst=0)  # 容量 10
        acquired = 0
        for _ in range(100):
            if b.acquire():
                acquired += 1
            else:
                break
        assert acquired <= 10  # 初始只有 10 个令牌

    def test_rate_limiting_kicks_in(self):
        """限流起效：burst 用完后拒绝"""
        b = TokenBucket(rate=100, burst=0)  # 100/s，容量 100
        for _ in range(100):
            assert b.acquire()  # 消耗全部令牌
        assert not b.acquire()  # 第 101 次被拒绝
        assert b.rejected_count == 1

    def test_rejected_count_increments(self):
        """被拒绝时 rejected_count 递增"""
        b = TokenBucket(rate=1, burst=0)
        b.acquire()  # 消耗唯一令牌
        b.acquire()  # 被拒绝
        b.acquire()  # 被拒绝
        assert b.rejected_count == 2

    def test_negative_rate_raises(self):
        """负速率为非法输入"""
        with pytest.raises(ValueError):
            TokenBucket(rate=-1)


# ═══════════════════════════════════════════════════════════
# RetryConfig
# ═══════════════════════════════════════════════════════════

class TestRetryConfig:
    def test_default_values(self):
        cfg = RetryConfig()
        assert cfg.max_retries == 3
        assert cfg.base_delay_sec == 1.0
        assert cfg.max_delay_sec == 30.0
        assert cfg.backoff_multiplier == 2.0

    def test_delay_sequence(self):
        """delay_for_attempt 指数增长"""
        cfg = RetryConfig(base_delay_sec=1.0, backoff_multiplier=2.0)
        assert cfg.delay_for_attempt(1) == 1.0
        assert cfg.delay_for_attempt(2) == 2.0
        assert cfg.delay_for_attempt(3) == 4.0

    def test_delay_capped_at_max(self):
        """延迟不超过 max_delay_sec"""
        cfg = RetryConfig(base_delay_sec=10, max_delay_sec=30,
                          backoff_multiplier=2.0)
        assert cfg.delay_for_attempt(3) == 30.0  # 40 -> capped at 30

    def test_custom_values(self):
        cfg = RetryConfig(max_retries=5, base_delay_sec=0.5,
                          max_delay_sec=10, backoff_multiplier=3.0)
        assert cfg.max_retries == 5
        assert cfg.delay_for_attempt(1) == 0.5
        assert cfg.delay_for_attempt(2) == 1.5


# ═══════════════════════════════════════════════════════════
# ChannelMetric
# ═══════════════════════════════════════════════════════════

class TestChannelMetric:
    def test_empty_has_perfect_success_rate(self):
        m = ChannelMetric(channel=ChannelType.WEB)
        assert m.success_rate == 1.0
        assert m.avg_latency_ms == 0.0

    def test_all_success(self):
        m = ChannelMetric(channel=ChannelType.FEISHU,
                          total_attempts=10, total_successes=10,
                          total_latency_ms=1000)
        assert m.success_rate == 1.0
        assert m.avg_latency_ms == 100.0

    def test_half_success(self):
        m = ChannelMetric(channel=ChannelType.FEISHU,
                          total_attempts=10, total_successes=5)
        assert m.success_rate == 0.5

    def test_all_failure(self):
        m = ChannelMetric(channel=ChannelType.FEISHU,
                          total_attempts=10, total_successes=0,
                          total_failures=10)
        assert m.success_rate == 0.0


# ═══════════════════════════════════════════════════════════
# SendResult / ChannelMessage / UserInfo
# ═══════════════════════════════════════════════════════════

class TestDataModels:
    def test_send_result_defaults(self):
        r = SendResult(success=True)
        assert r.message_id is None
        assert r.status == MessageStatus.SENT

    def test_send_result_failed_status(self):
        r = SendResult(success=False, status=MessageStatus.FAILED,
                       channel=ChannelType.FEISHU)
        assert r.status == MessageStatus.FAILED

    def test_channel_message_defaults(self):
        m = ChannelMessage(channel=ChannelType.WEB, sender_id="u1",
                           content="hello")
        assert m.msg_type == MessageType.TEXT
        assert m.status == MessageStatus.PENDING
        assert m.mentioned is False

    def test_user_info(self):
        u = UserInfo(user_id="ou_123", display_name="Test",
                     channel=ChannelType.FEISHU)
        assert u.user_id == "ou_123"
        assert u.channel == ChannelType.FEISHU
        assert u.extra == {}


# ═══════════════════════════════════════════════════════════
# ChannelAdapter 子类最小实现 + extract_message_id
# ═══════════════════════════════════════════════════════════

class _MinimalAdapter(ChannelAdapter):
    """最小实现用于测试基类方法"""
    channel_type = ChannelType.WEB

    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        return SendResult(success=True, channel=ChannelType.WEB)


class TestChannelAdapterBase:
    def test_extract_message_id_normalized_format(self):
        """extract_message_id 返回 channel_type:original_id 格式"""
        adapter = _MinimalAdapter()
        resp = {"data": {"message_id": "msg_abc123"}}
        mid = adapter.extract_message_id(resp)
        assert mid == "web:msg_abc123"

    def test_extract_message_id_returns_none_for_empty(self):
        adapter = _MinimalAdapter()
        assert adapter.extract_message_id({}) is None
        assert adapter.extract_message_id({"data": {}}) is None

    def test_truncate_text_within_limit(self):
        adapter = _MinimalAdapter()
        assert adapter.truncate_text("hello") == "hello"

    def test_truncate_text_exceeds_limit(self):
        """超长文本被截断"""
        class _ShortAdapter(_MinimalAdapter):
            @property
            def max_text_length(self): return 10
        adapter = _ShortAdapter()
        long_text = "a" * 20
        result = adapter.truncate_text(long_text)
        assert len(result) <= 10
        assert result.endswith("...")

    def test_max_card_size_default_zero(self):
        """默认 max_card_size=0 表示不限制"""
        adapter = _MinimalAdapter()
        assert adapter.max_card_size == 0

    def test_enforce_card_size_limit_noop_when_zero(self):
        """max_card_size=0 时原样返回"""
        adapter = _MinimalAdapter()
        card = {"body": "x" * 10000}
        assert adapter.enforce_card_size_limit(card) == card

    def test_enabled_default_true(self):
        assert _MinimalAdapter().enabled is True

    def test_retry_config_default(self):
        cfg = _MinimalAdapter().retry_config
        assert cfg.max_retries == 3


# ═══════════════════════════════════════════════════════════
# ChannelType / MessageType / MessageStatus
# ═══════════════════════════════════════════════════════════

class TestEnums:
    def test_channel_type_values(self):
        assert ChannelType.FEISHU == "feishu_im"
        assert ChannelType.WECOM == "wecom"
        assert ChannelType.WEB == "web"
        assert ChannelType.TELEGRAM == "telegram"
        assert ChannelType.CLI == "cli"

    def test_message_type_values(self):
        assert MessageType.TEXT == "text"
        assert MessageType.CARD == "card"
        assert MessageType.IMAGE == "image"
        assert MessageType.FILE == "file"

    def test_message_status_flow(self):
        assert MessageStatus.PENDING == "pending"
        assert MessageStatus.SENT == "sent"
        assert MessageStatus.DELIVERED == "delivered"
        assert MessageStatus.READ == "read"
        assert MessageStatus.FAILED == "failed"
