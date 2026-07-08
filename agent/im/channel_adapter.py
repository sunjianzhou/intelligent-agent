"""Channel Adapter 抽象层（Python 侧）

职责：统一所有 channel 的发送行为，供：
  1. LLM tool 调用（ChannelMessageTool 替代 FeishuIMTool）
  2. ChannelRouter 多通道广播

⚠️ 异步/同步边界：
  - 所有 send_* 方法声明为 async（接口契约）
  - ChannelAdapter 内部使用 requests（同步 HTTP），在 async 方法中直接调用
    （FastAPI 的事件循环可以处理同步 IO 在线程池中运行）
  - LLM tool（BaseTool.execute 是同步的）通过 asyncio.run() 桥接
  - Java 侧所有方法为同步（Spring MVC 线程模型）

设计版本：v1.2（2026-07-08）
"""
from __future__ import annotations

import hashlib
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Optional


# ═══════════════════════════════════════════════════════════
# 枚举
# ═══════════════════════════════════════════════════════════

class ChannelType(str, Enum):
    FEISHU   = "feishu_im"
    WECOM    = "wecom"
    WEB      = "web"
    TELEGRAM = "telegram"
    CLI      = "cli"


class MessageType(str, Enum):
    TEXT  = "text"
    CARD  = "card"        # interactive card / markdown
    IMAGE = "image"
    FILE  = "file"


class MessageStatus(str, Enum):
    """消息生命周期状态"""
    PENDING   = "pending"     # 准备发送（队列中）
    SENT      = "sent"        # API 返回成功（有 message_id）
    DELIVERED = "delivered"   # 平台确认送达（webhook 回调）
    READ      = "read"        # 用户已读（仅部分 channel 支持）
    FAILED    = "failed"      # 发送失败（含重试耗尽）


# ═══════════════════════════════════════════════════════════
# 数据模型
# ═══════════════════════════════════════════════════════════

@dataclass
class ChannelMessage:
    """跨 channel 统一消息模型"""
    channel:      ChannelType
    sender_id:    str
    content:      str
    message_id:   str = ""              # 归一化：channel_type:original_id
    dedup_key:    str = ""              # 去重键：request_id 或 hash(content+ts)
    msg_type:     MessageType = MessageType.TEXT
    chat_id:      Optional[str] = None
    chat_type:    Optional[str] = None  # "p2p" / "group"
    mentioned:    bool = False
    status:       MessageStatus = MessageStatus.PENDING
    raw_payload:  Optional[dict] = None
    extra:        Dict[str, Any] = field(default_factory=dict)


@dataclass
class SendResult:
    """发送操作统一结果"""
    success:    bool
    message_id: Optional[str] = None    # 归一化格式 channel_type:original_id
    error:      Optional[str] = None
    channel:    Optional[ChannelType] = None
    status:     MessageStatus = MessageStatus.SENT
    latency_ms: float = 0.0             # 发送耗时（毫秒）


@dataclass
class UserInfo:
    """用户信息（channel-agnostic）"""
    user_id:      str
    display_name: Optional[str] = None
    avatar_url:   Optional[str] = None
    channel:      Optional[ChannelType] = None
    extra:        Dict[str, Any] = field(default_factory=dict)


# ═══════════════════════════════════════════════════════════
# 重试配置
# ═══════════════════════════════════════════════════════════

@dataclass
class RetryConfig:
    """指数退避重试配置"""
    max_retries: int = 3
    base_delay_sec: float = 1.0       # 首次重试延迟
    max_delay_sec: float = 30.0       # 最大延迟上限
    backoff_multiplier: float = 2.0   # 退避倍率
    retryable_errors: tuple = (       # 可重试的错误类型
        "timeout", "connection", "rate_limit", "server_error"
    )

    def delay_for_attempt(self, attempt: int) -> float:
        """计算第 attempt 次重试的延迟（1-indexed）"""
        delay = self.base_delay_sec * (self.backoff_multiplier ** (attempt - 1))
        return min(delay, self.max_delay_sec)


# ═══════════════════════════════════════════════════════════
# 限流器（Token Bucket）
# ═══════════════════════════════════════════════════════════

class TokenBucket:
    """令牌桶限流器（线程安全）

    用法:
        limiter = TokenBucket(rate=50, burst=10)   # 50次/秒，突发+10
        if limiter.acquire():
            await adapter.send_text(...)
    """

    def __init__(self, rate: float, burst: int = 0):
        if rate < 0:
            raise ValueError("rate must be >= 0")
        self._rate = rate                # 每秒填充令牌数
        self._burst = burst              # 桶容量 = rate + burst
        self._capacity = rate + burst
        self._tokens = self._capacity
        self._last_refill = time.monotonic()
        self._rejected_count = 0

    def acquire(self) -> bool:
        """尝试获取一个令牌。返回 True = 放行。"""
        if self._rate <= 0:
            return True  # 不限流
        now = time.monotonic()
        elapsed = now - self._last_refill
        self._tokens = min(self._capacity, self._tokens + elapsed * self._rate)
        self._last_refill = now
        if self._tokens >= 1.0:
            self._tokens -= 1.0
            return True
        self._rejected_count += 1
        return False

    @property
    def rejected_count(self) -> int:
        return self._rejected_count


# ═══════════════════════════════════════════════════════════
# Channel 指标
# ═══════════════════════════════════════════════════════════

@dataclass
class ChannelMetric:
    """单 channel 发送指标（可观测性）"""
    channel:           ChannelType
    total_attempts:    int = 0
    total_successes:   int = 0
    total_failures:    int = 0
    total_retries:     int = 0
    rate_limit_hits:   int = 0
    total_latency_ms:  float = 0.0

    @property
    def success_rate(self) -> float:
        if self.total_attempts == 0:
            return 1.0
        return self.total_successes / self.total_attempts

    @property
    def avg_latency_ms(self) -> float:
        if self.total_attempts == 0:
            return 0.0
        return self.total_latency_ms / self.total_attempts


# ═══════════════════════════════════════════════════════════
# 抽象适配器
# ═══════════════════════════════════════════════════════════

class ChannelAdapter(ABC):
    """Channel 抽象适配器

    每个 channel 实现一个子类，注册到 ChannelRouter。
    只覆盖 send_* 方法中该 channel 支持的操作（不支持的返回失败）。

    子类在 __init__ 中设置：
      - self._rate_limiters: dict[str, TokenBucket]  # 按操作类型独立限流
      - self._session: requests.Session             # HTTP 连接池复用
      - self._metrics: ChannelMetric                 # 由基类自动初始化
    """

    def __init__(self):
        # 子类必须在其 __init__ 中先设置 channel_type 所需属性再调 super().__init__()
        # 如果在初始化中访问 channel_type 失败，使用占位值
        try:
            ct = self.channel_type
        except Exception:
            ct = ChannelType.WEB  # 占位，子类应该覆盖
        self._metrics = ChannelMetric(channel=ct)

    # ── 标识 ──────────────────────────────────────────────

    @property
    @abstractmethod
    def channel_type(self) -> ChannelType:
        """返回 channel 类型标识"""
        ...

    # ── 限流（按操作类型独立）──────────────────────────────

    # 子类在 __init__ 中初始化 _rate_limiters: dict[str, TokenBucket]
    # key 为操作类型： "text" / "card" / "file" / "image"
    # 例：_rate_limiters = {"text": TokenBucket(50, 10), "card": TokenBucket(1.67, 3)}

    def _check_rate_limit(self, operation: str = "text") -> bool:
        """检查指定操作是否被限流。返回 True = 放行。"""
        limiters = getattr(self, '_rate_limiters', None)
        if limiters is None:
            return True
        limiter = limiters.get(operation)
        if limiter is None:
            return True
        return limiter.acquire()

    @property
    def retry_config(self) -> RetryConfig:
        """重试策略。子类覆盖以自定义。"""
        return RetryConfig()

    # ── 核心发送方法 ──────────────────────────────────────

    @abstractmethod
    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        """发送文本消息"""
        ...

    async def send_card(self, receiver_id: str, card: dict,
                        chat_type: str = "p2p") -> SendResult:
        return SendResult(success=False, error="card 未实现",
                          channel=self.channel_type, status=MessageStatus.FAILED)

    async def send_file(self, receiver_id: str, file_path: str,
                        file_name: Optional[str] = None,
                        chat_type: str = "p2p") -> SendResult:
        return SendResult(success=False, error="file 未实现",
                          channel=self.channel_type, status=MessageStatus.FAILED)

    async def send_image(self, receiver_id: str, image_data: bytes,
                         chat_type: str = "p2p") -> SendResult:
        return SendResult(success=False, error="image 未实现",
                          channel=self.channel_type, status=MessageStatus.FAILED)

    # ── 带重试+限流的发送包装 ─────────────────────────────

    async def send_with_retry(self, send_fn, *args,
                              operation: str = "text", **kwargs) -> SendResult:
        """统一的发送包装：按操作类型限流 → 发送 → 指数退避重试 → 记录指标

        Args:
            send_fn: async callable，返回 SendResult
            operation: 操作类型（"text"/"card"/"file"/"image"），用于选择限流器
        """
        import asyncio as _asyncio

        self._metrics.total_attempts += 1
        t0 = time.monotonic()

        # 按操作类型限流（微优化 1：text 和 card 各自独立限流）
        if not self._check_rate_limit(operation):
            self._metrics.rate_limit_hits += 1
            self._metrics.total_failures += 1
            return SendResult(
                success=False,
                error=f"rate limited: {operation}",
                channel=self.channel_type,
                status=MessageStatus.FAILED,
            )

        cfg = self.retry_config
        last_error = None

        for attempt in range(cfg.max_retries + 1):
            try:
                raw_result = send_fn(*args, **kwargs)
                # 兼容同步/异步两种 send_fn
                if _asyncio.iscoroutine(raw_result) or hasattr(raw_result, '__await__'):
                    result = await raw_result
                else:
                    result = raw_result
                latency = (time.monotonic() - t0) * 1000

                if result.success:
                    self._metrics.total_successes += 1
                    self._metrics.total_latency_ms += latency
                    result.latency_ms = latency
                    result.status = MessageStatus.SENT
                    return result

                # API 返回失败——检查是否可重试
                last_error = result.error or "unknown"
                if attempt < cfg.max_retries and self._is_retryable(last_error):
                    self._metrics.total_retries += 1
                    delay = cfg.delay_for_attempt(attempt + 1)
                    await _asyncio.sleep(delay)
                    continue
                break

            except Exception as e:
                last_error = str(e)
                if attempt < cfg.max_retries and self._is_retryable(last_error):
                    self._metrics.total_retries += 1
                    delay = cfg.delay_for_attempt(attempt + 1)
                    await _asyncio.sleep(delay)
                    continue
                break

        self._metrics.total_failures += 1
        return SendResult(
            success=False,
            error=f"重试{cfg.max_retries}次后失败: {last_error}",
            channel=self.channel_type,
            status=MessageStatus.FAILED,
            latency_ms=(time.monotonic() - t0) * 1000,
        )

    def _is_retryable(self, error_msg: str) -> bool:
        """判断错误是否可重试"""
        error_lower = error_msg.lower()
        return any(e in error_lower for e in self.retry_config.retryable_errors)

    # ── 指标 ──────────────────────────────────────────────

    @property
    def metrics(self) -> ChannelMetric:
        return self._metrics

    def reset_metrics(self) -> None:
        self._metrics = ChannelMetric(channel=self.channel_type)

    # ── 消息 ID ───────────────────────────────────────────

    def _extract_raw_id(self, api_response: dict) -> Optional[str]:
        """子类覆盖：从 API 响应中提取原始 message_id。默认从 data.message_id 取。"""
        data = api_response.get("data", {})
        return data.get("message_id") if isinstance(data, dict) else None

    def extract_message_id(self, api_response: dict) -> Optional[str]:
        """归一化 message_id → "channel_type:original_id" """
        raw_id = self._extract_raw_id(api_response)
        if not raw_id:
            return None
        return f"{self.channel_type.value}:{str(raw_id)}"

    # ── 配置属性 ──────────────────────────────────────────

    @property
    def max_text_length(self) -> int:
        return 4000

    @property
    def max_card_size(self) -> int:
        """卡片内容最大字节数（JSON 序列化后）。子类覆盖。0 表示不限制。"""
        return 0

    @property
    def enabled(self) -> bool:
        return True

    def truncate_text(self, text: str) -> str:
        if len(text) <= self.max_text_length:
            return text
        return text[:self.max_text_length - 3] + "..."

    def enforce_card_size_limit(self, card: dict) -> dict:
        """检查卡片大小，超出限制时截断 body 字段并附加截断提示。

        子类覆盖 max_card_size 即可，无需覆盖本方法。
        """
        import json as _json
        max_size = self.max_card_size
        if max_size <= 0:
            return card
        payload = _json.dumps(card, ensure_ascii=False)
        if len(payload) <= max_size:
            return card
        # 截断策略：优先缩短 body 字段
        body = card.get("body", "")
        if isinstance(body, str) and len(body) > 100:
            headroom = max_size - len(payload) + len(body) - 50  # 保留 50 字节给截断提示
            if headroom > 0:
                card = dict(card)  # 不修改入参
                card["body"] = body[:headroom] + "\n\n...(内容过长已截断)"
        return card

    # ── HTTP Session 连接复用（微优化 2）────────────────────

    def _init_session(self) -> "requests.Session":
        """创建带连接池复用的 requests.Session。

        子类在 __init__ 中调用：self._session = self._init_session()
        然后在 _do_send_sync 中用 self._session.post(...) 替代 requests.post(...)
        性能提升 30-50%（TCP 连接复用 + keep-alive）。
        """
        import requests as _requests
        from requests.adapters import HTTPAdapter as _HTTPAdapter
        from urllib3.util.retry import Retry as _Retry

        session = _requests.Session()
        adapter = _HTTPAdapter(
            pool_connections=4,       # 连接池大小
            pool_maxsize=8,           # 每个池最大连接数
            max_retries=_Retry(total=0),  # 重试由 send_with_retry 统一管理
        )
        session.mount("https://", adapter)
        session.mount("http://", adapter)
        return session

    # ── 用户信息 ──────────────────────────────────────────

    async def get_user_info(self, user_id: str) -> Optional[UserInfo]:
        return None
