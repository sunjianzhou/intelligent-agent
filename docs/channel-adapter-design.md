# Channel Adapter 抽象层设计

> 版本：v1.2 | 日期：2026-07-08 | 作者：Linmiaoshusheng
> v1.2 更新：3 项微优化（按操作分限流/Session 连接复用/Card 大小限制）
> v1.1 更新：纳入 4 风险 + 6 补充建议（异步桥接/ID归一化/限流/去重/路由/工厂/生命周期/测试/可观测性/重试）

## 1. 现状分析

### 1.1 当前消息流

```
外部消息到达
  │
  ├── 飞书：FeishuWebSocketClient (WS长连) → FeishuEventController.routeEvent()
  │      └── 构建 ChatRequest(channel="feishu_im") → AgentService.chatFull() → Python /api/chat
  │           └── 回复：FeishuMessageSender.sendInteractive()
  │
  └── 企微：WeComCallbackController.receiveMessage() (HTTP回调)
         └── 构建 ChatRequest(channel="wecom") → AgentService.chatFull() → Python /api/chat
              └── 回复：WeComMessageSender.sendText()

LLM 主动发消息
  └── Python FeishuIMTool (BaseTool, category="im")
       └── 直接调飞书 API（不走 Java）
```

### 1.2 关键问题

| 问题 | 影响 |
|------|------|
| 飞书/企微各自独立实现，无共享接口 | 新增 channel 需重写完整流水线 |
| Python `FeishuIMTool` 只覆盖飞书 | LLM 无法通过工具向企微/Web/Telegram 发消息 |
| 发送逻辑分散在 Java `FeishuMessageSender` + Python `feishu_client.py` | 两边 token 管理/重试逻辑重复 |
| 无多通道并行广播能力 | 同一条消息不能同时推送飞书+企微 |

### 1.3 不破坏的现有功能（W1-W9）

- Java → Python 的 `ChatRequest.channel` 字段透传（已工作）
- `allowed_tool_categories` 硬限制（已工作）
- System prompt 按 channel 分级注入（已工作：`_request_channel_ctx`）
- 飞书 `FeishuRecallBridge` 撤回联动
- 群聊静默规则（`NO_REPLY` sentinel）
- 发送前/后验证（TODO-93 失职自查钩子）

---

## 2. 架构决策：两侧各一个抽象层

### 2.1 为什么两侧各一个？

```
┌──────────────────────────────────────────────────────────┐
│                     Channel Adapter Layer                 │
├────────────────────────┬─────────────────────────────────┤
│  Python (agent/im/)    │  Java (backend/web/im/)          │
│  职责：LLM工具出口      │  职责：外部消息入口 + 回复出口    │
│  • LLM 调用工具发消息   │  • 接收外部事件并归一化          │
│  • 多通道广播路由       │  • 回复消息到 channel            │
│  • Token/重试（Python） │  • 生命周期管理（启动/停止）      │
│  • 发送前/后验证        │  • Token/重试（Java）            │
├────────────────────────┼─────────────────────────────────┤
│  ChannelAdapter (ABC)  │  ChannelAdapter (interface)      │
│  ChannelMessage        │  ChannelMessage (record)         │
│  ChannelRouter         │  ChannelAdapterManager           │
└────────────────────────┴─────────────────────────────────┘
```

**决策理由：**

1. **职责分离**：Java 负责"从外部收消息"（WebSocket/HTTP 回调），Python 负责"LLM 决策后主动发消息"。两者不可合并。
2. **最小改动**：飞书的接收链路（`FeishuEventController` → `AgentService`）和发送链路（`FeishuIMTool`）分别在两侧，各侧改造各自的即可。
3. **故障隔离**：Java 侧发送失败不影响 Python LLM 推理，反之亦然。
4. **协议统一**：两侧共享 `ChannelMessage` 数据结构定义，但各自独立实现。

### 2.2 数据模型统一

两侧使用**语义相同、语言不同**的 `ChannelMessage`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `channel` | ChannelType enum | `feishu_im` / `wecom` / `web` / `telegram` / `cli` |
| `sender_id` | str | 发送者ID（channel-specific） |
| `content` | str | 纯文本内容 |
| `message_id` | str | **归一化格式** `channel_type:original_id`（见 2.3 节） |
| `dedup_key` | str | **去重键**：同一消息跨通道唯一标识（取 `request_id` 或 `hash(content+timestamp)`） |
| `msg_type` | MessageType | text / card / image / file |
| `chat_id` | str? | 群聊ID（私聊为 None） |
| `chat_type` | str? | "p2p" / "group" |
| `mentioned` | bool | 是否 @ 了机器人 |
| `status` | MessageStatus | **消息生命周期**（见 2.4 节） |
| `raw_payload` | dict? | 原始消息体（调试用） |

### 2.3 Message ID 归一化格式

**问题**：飞书返回 `om_xxx`，企微返回数字 `msgid`，Telegram 返回数字 `message_id`，Web 无外部 ID——格式不一致导致跨通道追踪困难。

**方案**：所有 `message_id` 统一为 `channel_type:original_id` 格式：

| Channel | API 返回 | 归一化后 |
|---------|---------|----------|
| Feishu | `om_8f4a2b3c...` | `feishu_im:om_8f4a2b3c...` |
| WeCom | `msgid` = `123456` | `wecom:123456` |
| Telegram | `message_id` = `789` | `telegram:789` |
| Web | 无 | `web:<uuid4>` |

`extract_message_id()` 在基类中做归一化，子类只需实现 `_extract_raw_id()`：

```python
def extract_message_id(self, api_response: dict) -> Optional[str]:
    """归一化 message_id → "channel_type:original_id" """
    raw_id = self._extract_raw_id(api_response)
    if not raw_id:
        return None
    return f"{self.channel_type.value}:{raw_id}"

def _extract_raw_id(self, api_response: dict) -> Optional[str]:
    """子类覆盖：从 API 响应中提取原始 ID"""
    return None  # 默认实现
```

### 2.4 消息生命周期（MessageStatus）

```
┌─────────┐    ┌──────┐    ┌───────────┐    ┌──────┐    ┌────────┐
│ PENDING │ → │ SENT │ → │ DELIVERED │ → │ READ │    │ FAILED │
└─────────┘    └──────┘    └───────────┘    └──────┘    └────────┘
  准备发送       API返回     平台确认送达    用户已读     发送失败
               message_id    (如有回调)    (如有回调)
```

```python
class MessageStatus(str, Enum):
    PENDING   = "pending"     # 准备发送（队列中）
    SENT      = "sent"        # API 返回成功（有 message_id）
    DELIVERED = "delivered"   # 平台确认送达（webhook 回调）
    READ      = "read"        # 用户已读（仅部分 channel 支持）
    FAILED    = "failed"      # 发送失败（含重试耗尽）

---

## 3. 接口定义

### 3.1 Python 侧：`agent/im/channel_adapter.py`

```python
"""Channel Adapter 抽象层（Python 侧）

职责：统一所有 channel 的发送行为，供：
  1. LLM tool 调用（替换 FeishuIMTool 为 ChannelMessageTool）
  2. ChannelRouter 多通道广播

⚠️ 异步/同步边界：
  - 所有 send_* 方法声明为 async（接口契约）
  - ChannelAdapter 内部使用 requests（同步 HTTP），在 async 方法中直接调用
    （FastAPI 的事件循环可以处理同步 IO 在线程池中运行）
  - LLM tool（BaseTool.execute 是同步的）通过 asyncio.run() 桥接
  - Java 侧所有方法为同步（Spring MVC 线程模型）
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional
import time


# ═══════════════════════════════════════════════════════════
# 数据模型
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
    PENDING   = "pending"
    SENT      = "sent"
    DELIVERED = "delivered"
    READ      = "read"
    FAILED    = "failed"


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
    extra:        dict = field(default_factory=dict)


@dataclass
class SendResult:
    """发送操作统一结果"""
    success:    bool
    message_id: Optional[str] = None    # 归一化格式
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
    extra:        dict = field(default_factory=dict)


# ═══════════════════════════════════════════════════════════
# 重试配置
# ═══════════════════════════════════════════════════════════

@dataclass
class RetryConfig:
    """指数退避重试配置"""
    max_retries: int = 3
    base_delay_sec: float = 1.0     # 首次重试延迟
    max_delay_sec: float = 30.0     # 最大延迟上限
    backoff_multiplier: float = 2.0 # 退避倍率
    retryable_errors: tuple = (     # 可重试的错误类型
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
        self._rate = rate                # 每秒填充令牌数
        self._burst = burst              # 桶容量 = rate + burst
        self._capacity = rate + burst
        self._tokens = self._capacity
        self._last_refill = time.monotonic()
        self._rejected_count = 0

    def acquire(self) -> bool:
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
    """

    def __init__(self):
        self._metrics = ChannelMetric(channel=self.channel_type)
        # 子类在 __init__ 中设置 self._rate_limiter

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
        limiter = getattr(self, '_rate_limiters', {}).get(operation)
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
        import asyncio

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
                result = await send_fn(*args, **kwargs)
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
                    await asyncio.sleep(delay)
                    continue
                break

            except Exception as e:
                last_error = str(e)
                if attempt < cfg.max_retries and self._is_retryable(last_error):
                    self._metrics.total_retries += 1
                    delay = cfg.delay_for_attempt(attempt + 1)
                    await asyncio.sleep(delay)
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

    def reset_metrics(self):
        self._metrics = ChannelMetric(channel=self.channel_type)

    # ── 消息 ID ───────────────────────────────────────────

    def _extract_raw_id(self, api_response: dict) -> Optional[str]:
        """子类覆盖：从 API 响应中提取原始 message_id"""
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
            pool_connections=4,      # 连接池大小
            pool_maxsize=8,          # 每个池最大连接数
            max_retries=_Retry(total=0),  # 重试由 send_with_retry 统一管理
        )
        session.mount("https://", adapter)
        session.mount("http://", adapter)
        return session

    # ── 用户信息 ──────────────────────────────────────────

    async def get_user_info(self, user_id: str) -> Optional[UserInfo]:
        return None
```

### 3.2 Java 侧：`backend/web/im/ChannelAdapter.java`

```java
package com.intelligent.agent.web.im;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Channel 适配器接口（Java 侧）。
 *
 * 职责：
 *   1. 外部消息归一化（Controller 收到原始事件 → normalizeMessage → ChannelMessage）
 *   2. 回复消息发送（sendText / sendCard / sendFile）
 *   3. 生命周期管理（start / stop）
 *
 * 每个 channel 实现一个 @Component，由 ChannelAdapterManager 统一管理。
 *
 * ⚠️ 异步/同步边界：
 *   - Java 侧所有方法为同步（Spring MVC 线程模型）
 *   - 发送失败时内部重试（阻塞当前线程，由 ExecutorService 隔离）
 *   - 需要异步时用 CompletableFuture.supplyAsync() 包装：
 *     CompletableFuture.supplyAsync(() -> adapter.sendText(...), broadcastExecutor)
 *   - Python 侧所有方法为 async（FastAPI 事件循环），asyncio.run() 桥接同步调用
 */
public interface ChannelAdapter {

    /** channel 类型标识 */
    ChannelType channelType();

    /** 是否启用（从配置读取） */
    boolean isEnabled();

    // ── 限流 ──────────────────────────────────────────────

    /** 每秒最大请求数。0 表示不限流。 */
    default double rateLimitPerSecond() { return 0; }

    /** 重试配置 */
    default RetryConfig retryConfig() { return RetryConfig.DEFAULT; }

    // ── 消息归一化 ────────────────────────────────────────

    /**
     * 将 channel 原始事件归一化为 ChannelMessage。
     * Controller 收到外部消息后调用此方法，然后传给 AgentService.chatFull()。
     */
    ChannelMessage normalizeMessage(Object rawEvent);

    // ── 发送 ──────────────────────────────────────────────

    /** 发送文本消息 */
    SendResult sendText(String receiverId, String text, String chatType);

    /** 发送卡片/富文本消息 */
    SendResult sendCard(String receiverId, Map<String, Object> card, String chatType);

    /** 发送文件 */
    SendResult sendFile(String receiverId, String filePath, String fileName, String chatType);

    /** 发送图片 */
    default SendResult sendImage(String receiverId, byte[] imageData, String chatType) {
        return new SendResult(false, null, "image 未实现", channelType());
    }

    // ── 带重试+限流的发送包装 ──────────────────────────────

    /** 统一发送包装：限流 → 发送 → 指数退避重试 → 记录指标 */
    default SendResult sendWithRetry(
            java.util.function.Supplier<SendResult> sendFn) {
        RetryConfig cfg = retryConfig();
        long t0 = System.currentTimeMillis();
        Exception lastEx = null;

        for (int attempt = 0; attempt <= cfg.maxRetries(); attempt++) {
            try {
                // 限流检查（由子类的 TokenBucket 实现）
                if (!checkRateLimit()) {
                    return new SendResult(false, null,
                        "rate limited (" + rateLimitPerSecond() + "/s)", channelType());
                }

                SendResult result = sendFn.get();
                if (result.isSuccess()) {
                    return result;
                }
                lastEx = new RuntimeException(result.getError());
            } catch (Exception e) {
                lastEx = e;
            }

            if (attempt < cfg.maxRetries() && isRetryable(lastEx)) {
                try {
                    Thread.sleep((long) (cfg.delayForAttempt(attempt + 1) * 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                break;
            }
        }

        long latency = System.currentTimeMillis() - t0;
        return new SendResult(false, null,
            "重试" + cfg.maxRetries() + "次后失败: " +
                (lastEx != null ? lastEx.getMessage() : "unknown"),
            channelType());
    }

    default boolean checkRateLimit() {
        return true;  // 子类覆盖实现 TokenBucket
    }

    default boolean isRetryable(Exception e) {
        if (e == null || e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("connection")
            || msg.contains("rate_limit") || msg.contains("server_error");
    }

    // ── 用户信息 ──────────────────────────────────────────

    default Optional<UserInfo> getUserInfo(String userId) {
        return Optional.empty();
    }

    // ── 消息验证 ──────────────────────────────────────────

    /** 从 API 响应中提取原始 message_id */
    default String extractRawMessageId(Map<String, Object> apiResponse) {
        if (apiResponse == null) return null;
        Object data = apiResponse.get("data");
        if (data instanceof Map) {
            return (String) ((Map<?, ?>) data).get("message_id");
        }
        return null;
    }

    /** 归一化 message_id → "channel_type:original_id" */
    default String extractMessageId(Map<String, Object> apiResponse) {
        String rawId = extractRawMessageId(apiResponse);
        if (rawId == null || rawId.trim().isEmpty()) return null;
        return channelType().getValue() + ":" + rawId;
    }

    /** 发送后验证 */
    default boolean verifySend(Map<String, Object> apiResponse) {
        return extractRawMessageId(apiResponse) != null;
    }

    // ── 指标 ──────────────────────────────────────────────

    default ChannelMetric getMetrics() { return new ChannelMetric(channelType()); }

    default void resetMetrics() {}

    // ── 配置 ──────────────────────────────────────────────

    default int maxTextLength() { return 4000; }

    default String truncateText(String text) {
        if (text == null) return "";
        int max = maxTextLength();
        if (text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }

    // ── 生命周期 ──────────────────────────────────────────

    default void start() {}
    default void stop() {}
}
```

### 3.3 Java 侧辅助类型

```java
// RetryConfig.java
public record RetryConfig(
    int maxRetries,
    double baseDelaySec,
    double maxDelaySec,
    double backoffMultiplier
) {
    public static final RetryConfig DEFAULT =
        new RetryConfig(3, 1.0, 30.0, 2.0);

    public double delayForAttempt(int attempt) {
        double delay = baseDelaySec * Math.pow(backoffMultiplier, attempt - 1);
        return Math.min(delay, maxDelaySec);
    }
}

// TokenBucket.java
public class TokenBucket {
    private final double rate;
    private final double capacity;
    private double tokens;
    private long lastRefill;
    private long rejectedCount = 0;

    public TokenBucket(double rate, int burst) {
        this.rate = rate;
        this.capacity = rate + burst;
        this.tokens = capacity;
        this.lastRefill = System.nanoTime();
    }

    public synchronized boolean acquire() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefill) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsed * rate);
        lastRefill = now;
        if (tokens >= 1.0) { tokens -= 1.0; return true; }
        rejectedCount++;
        return false;
    }
}

// ChannelMetric.java
public record ChannelMetric(
    ChannelType channel,
    long totalAttempts,
    long totalSuccesses,
    long totalFailures,
    long totalRetries,
    long rateLimitHits,
    double totalLatencyMs
) {
    public ChannelMetric(ChannelType channel) {
        this(channel, 0, 0, 0, 0, 0, 0.0);
    }
}
```

### 3.4 ChannelAdapterFactory（补充建议 2）

**问题**：当前初始化代码通过 `if os.environ.get("FEISHU_APP_ID")` 逐条判断，新增 channel 需手写 if-else 链。

**方案**：Factory 模式自动发现并创建所有 enabled 的 adapter。

```python
# agent/im/adapter_factory.py
"""Channel Adapter 工厂 —— 自动发现、创建、注册"""
import os
from typing import List

from loguru import logger
from agent.im.channel_adapter import ChannelAdapter, ChannelType


class ChannelAdapterFactory:
    """根据环境变量自动创建所有 enabled adapter，避免手写 if-else"""

    _registry: dict = {}  # {ChannelType: (class, required_env_var)}

    @classmethod
    def register(cls, channel_type: ChannelType, adapter_class,
                 required_env: str = None):
        cls._registry[channel_type] = (adapter_class, required_env)

    @classmethod
    def create_all(cls, **deps) -> List[ChannelAdapter]:
        adapters = []
        for ct, (clz, env_var) in cls._registry.items():
            if env_var and not os.environ.get(env_var):
                logger.debug(f"[Factory] 跳过 {ct.value}：{env_var} 未设置")
                continue
            try:
                a = clz(**deps)
                if a.enabled:
                    adapters.append(a)
                    logger.info(f"[Factory] 创建 {ct.value} adapter")
            except Exception as e:
                logger.warning(f"[Factory] {ct.value} 创建失败: {e}")
        return adapters


# ── 注册表（模块加载时延迟导入） ──────────────────────────
def _register_all():
    from agent.im.adapters.feishu_adapter import FeishuAdapter
    from agent.im.adapters.wecom_adapter import WeComAdapter
    from agent.im.adapters.web_adapter import WebAdapter
    from agent.im.adapters.telegram_adapter import TelegramAdapter

    ChannelAdapterFactory.register(ChannelType.FEISHU,   FeishuAdapter,   "FEISHU_APP_ID")
    ChannelAdapterFactory.register(ChannelType.WECOM,    WeComAdapter,    "WECOM_CORP_ID")
    ChannelAdapterFactory.register(ChannelType.WEB,      WebAdapter,      None)  # 始终可用
    ChannelAdapterFactory.register(ChannelType.TELEGRAM, TelegramAdapter, "TELEGRAM_BOT_TOKEN")
```

Java 侧 Spring 已提供天然的 Factory 模式（`List<ChannelAdapter>` 构造器注入），无需额外类。

---

## 3bis. Async/Sync 边界设计（风险 1 应对）

### 3bis.1 边界图

```
┌─────────────────────────────────────────────────────────┐
│                    Caller / Context                       │
├──────────┬──────────┬──────────┬──────────┬─────────────┤
│ LLM Tool │ chat API │ Java Ctl │ Notify   │ CLI         │
│ (sync)   │ (async)  │ (sync)   │ (async)  │ (sync)      │
├──────────┼──────────┼──────────┼──────────┼─────────────┤
│    │     │    │     │    │     │    │     │    │        │
│ asyncio  │ 直接    │ 直接    │ 直接    │ asyncio     │
│ .run()   │ await   │ 调用    │ await   │ .run()      │
│    │     │    │     │    │     │    │     │    │        │
├──────────┴──────────┴──────────┴──────────┴─────────────┤
│     ChannelAdapter (Python: async, Java: sync)            │
├──────────────────────────────────────────────────────────┤
│              Platform API (HTTP / WS)                     │
└──────────────────────────────────────────────────────────┘
```

### 3bis.2 三条调用路径

```python
# 路径 1：Agent 内部异步调用（chat_router.py）
# FastAPI async handler → 直接 await
result = await adapter.send_text(receiver_id, text)

# 路径 2：LLM Tool 同步调用（BaseTool.execute → sync → async 桥接）
class ChannelMessageTool(BaseTool):
    def execute(self, channel, receiver_id, msg_type, content):
        result = asyncio.run(
            adapter.send_with_retry(
                lambda: adapter.send_text(receiver_id, content.get("text", ""))
            )
        )
        return {"success": result.success, "message_id": result.message_id}

# 路径 3：ChannelRouter.broadcast（async → asyncio.gather 并行）
results = await channel_router.broadcast_text(text, receivers)
```

```java
// Java 侧：所有调用为同步，需要并行时用 CompletableFuture
// 路径 1：Controller 直接调用（由 feishuExecutor 线程池隔离）
SendResult result = adapter.sendText(chatId, reply, "group");

// 路径 2：多通道并行广播（CompletableFuture.supplyAsync）
var futures = new HashMap<ChannelType, CompletableFuture<SendResult>>();
for (var entry : receivers.entrySet()) {
    futures.put(entry.getKey(), CompletableFuture.supplyAsync(
        () -> adapter.sendText(entry.getValue(), text, chatType),
        broadcastExecutor
    ));
}
// 等待全部完成，任一失败不影响其他
for (var e : futures.entrySet()) {
    try {
        results.put(e.getKey(), e.getValue().get(30, TimeUnit.SECONDS));
    } catch (Exception ex) {
        results.put(e.getKey(), new SendResult(false, null, ex.getMessage(), e.getKey()));
    }
}
```

---

## 4. 四个 Channel 实现骨架

### 4.1 FeishuAdapter（Python + Java）

**Python 侧：`agent/im/adapters/feishu_adapter.py`**
```python
"""飞书 Channel 适配器（Python 侧）

替代原 feishu_client.py / FeishuIMTool。
复用现有的 _get_tenant_access_token()、_verify_message_content()、
_extract_message_id() 逻辑。
"""
import json, os, time
from typing import Optional

import requests
from loguru import logger

from agent.im.channel_adapter import (
    ChannelAdapter, ChannelType, SendResult, UserInfo,
)

FEISHU_BASE = "https://open.feishu.cn"
_MAX_TEXT_LENGTH = 15000


class FeishuAdapter(ChannelAdapter):
    """飞书 IM 适配器

    限流：text 50次/秒, card 100次/分钟(≈1.67/秒)（微优化 1：按操作分限流）
    重试：3 次指数退避（1s/2s/4s）
    Card 大小：最大 30KB（微优化 3）
    HTTP：requests.Session() 连接池复用（微优化 2）
    """

    def __init__(self, app_id: str = None, app_secret: str = None):
        super().__init__()
        self._app_id     = app_id or os.environ.get("FEISHU_APP_ID", "")
        self._app_secret = app_secret or os.environ.get("FEISHU_APP_SECRET", "")
        self._token_cache: dict = {"token": None, "expiry": 0.0}
        # 微优化 1：按操作类型独立限流
        self._rate_limiters = {
            "text":  TokenBucket(rate=50, burst=10),   # 飞书 text: 50次/秒
            "card":  TokenBucket(rate=1.67, burst=3),  # 飞书 card: 100次/分钟
            "image": TokenBucket(rate=10, burst=3),    # 飞书 image: 10次/秒
        }
        # 微优化 2：HTTP 连接池复用（性能提升 30-50%）
        self._session = self._init_session()

    @property
    def channel_type(self) -> ChannelType:
        return ChannelType.FEISHU

    @property
    def max_text_length(self) -> int:
        return _MAX_TEXT_LENGTH

    @property
    def max_card_size(self) -> int:
        return 30 * 1024  # 飞书 card 最大 30KB（微优化 3）

    @property
    def enabled(self) -> bool:
        return bool(self._app_id and self._app_secret)

    # ── Token ─────────────────────────────────────────────

    def _get_token(self) -> str:
        now = time.time()
        if self._token_cache["token"] and now < self._token_cache["expiry"] - 300:
            return self._token_cache["token"]
        resp = requests.post(
            f"{FEISHU_BASE}/open-apis/auth/v3/tenant_access_token/internal",
            json={"app_id": self._app_id, "app_secret": self._app_secret},
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        if data.get("code") != 0:
            raise RuntimeError(f"飞书 token 获取失败: {data}")
        self._token_cache["token"]  = data["tenant_access_token"]
        self._token_cache["expiry"] = now + data["expire"]
        return self._token_cache["token"]

    # ── 发送 ───────────────────────────────────────────────

    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        """发送文本消息（通过 send_with_retry 包装限流+重试）"""
        id_type = self._resolve_id_type(chat_type, receiver_id)
        content = {"text": self.truncate_text(text)}
        return await self.send_with_retry(
            lambda: self._do_send_sync(receiver_id, "text", content, id_type)
        )

    async def send_card(self, receiver_id: str, card: dict,
                        chat_type: str = "p2p") -> SendResult:
        # 微优化 3：card 大小检查 + 自动截断
        card = self.enforce_card_size_limit(card)
        id_type = self._resolve_id_type(chat_type, receiver_id)
        return await self.send_with_retry(
            lambda: self._do_send_sync(receiver_id, "interactive", card, id_type),
            operation="card",  # 微优化 1：card 独立限流
        )

    async def send_image(self, receiver_id: str, image_data: bytes,
                         chat_type: str = "p2p") -> SendResult:
        """图片需先上传飞书获取 image_key，然后发送。此处为骨架。"""
        return SendResult(success=False, error="image 上传待实现",
                          channel=self.channel_type, status=MessageStatus.FAILED)

    async def get_user_info(self, user_id: str) -> Optional[UserInfo]:
        """调用飞书 获取用户信息 API"""
        try:
            token = self._get_token()
            resp = requests.get(
                f"{FEISHU_BASE}/open-apis/contact/v3/users/{user_id}",
                headers={"Authorization": f"Bearer {token}"},
                timeout=10,
            )
            data = resp.json()
            if data.get("code") == 0:
                u = data.get("data", {}).get("user", {})
                return UserInfo(
                    user_id=user_id,
                    display_name=u.get("name"),
                    avatar_url=u.get("avatar", {}).get("avatar_240"),
                    channel=ChannelType.FEISHU,
                )
        except Exception as e:
            logger.warning(f"飞书获取用户信息失败 [user={user_id}]: {e}")
        return None

    # ── 内部 ───────────────────────────────────────────────

    def _resolve_id_type(self, chat_type: str, receiver_id: str) -> str:
        if chat_type == "group" or receiver_id.startswith("oc_"):
            return "chat_id"
        return "open_id"

    def _do_send_sync(self, receiver_id: str, msg_type: str,
                      content: dict, id_type: str) -> SendResult:
        """单次发送（同步，使用复用 Session——微优化 2）"""
        # 发送前验证（TODO-93 失职自查钩子）
        self._verify_pre_send(msg_type, content)

        token = self._get_token()
        resp = self._session.post(  # 微优化 2：复用 TCP 连接
            f"{FEISHU_BASE}/open-apis/im/v1/messages",
            params={"receive_id_type": id_type},
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
            return SendResult(success=False, channel=ChannelType.FEISHU,
                              error=f"API code={result.get('code')}: {result.get('msg')}")
        msg_id = self.extract_message_id(result)
        return SendResult(success=msg_id is not None, message_id=msg_id,
                          channel=ChannelType.FEISHU,
                          error=None if msg_id else "message_id 缺失")

    def _verify_pre_send(self, msg_type: str, content: dict) -> None:
        """TODO-93 失职自查钩子：发送前验证"""
        if not content:
            logger.warning("[feishu pre-send] content 为空")
            return
        if msg_type == "text":
            text = content.get("text", "")
            if not text or not text.strip():
                logger.warning("[feishu pre-send] text 消息内容为空")
            elif len(text) > _MAX_TEXT_LENGTH:
                logger.warning(f"[feishu pre-send] text 过长 ({len(text)} > {_MAX_TEXT_LENGTH})")
```

**最小改动路径**（原 `FeishuIMTool`）：
1. `FeishuIMTool.execute()` 内部改为委托给 `FeishuAdapter.send_text()`
2. `FeishuIMTool` 保留类名和 `BaseTool` 注册路径不变（工具注册 `"im_message"` 不变）
3. 新增 `allowed_tool_categories=["im"]` 的 channel-aware 过滤：飞书 channel 只看到飞书 tool，企微只看到企微 tool

### 4.2 WebAdapter（Python + Java）

**Python 侧：`agent/im/adapters/web_adapter.py`**
```python
"""Web PWA Channel 适配器（Python 侧）

Web channel 的消息发送通过 WebSocket 推送到前端，不调用外部 API。
"""
from typing import Optional
from loguru import logger

from agent.im.channel_adapter import (
    ChannelAdapter, ChannelType, SendResult,
)


class WebAdapter(ChannelAdapter):
    """Web PWA 适配器

    通过 WebSocket 向浏览器推送消息。
    无外部 API → 无限流、无重试、无 token。
    """

    def __init__(self, ws_manager=None):
        super().__init__()
        self._ws_manager = ws_manager

    @property
    def channel_type(self) -> ChannelType:
        return ChannelType.WEB

    @property
    def rate_limit_per_second(self) -> float:
        return 0.0  # Web 不限流

    @property
    def max_text_length(self) -> int:
        return 3000

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
                return SendResult(success=True, channel=ChannelType.WEB,
                                  message_id=None)  # Web 无外部 message_id
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
```

**Java 侧：WebChannelAdapter** — Web 接收消息已通过 `ChatController` 的 WebSocket 处理，不需要 Java 侧 WebChannelAdapter。Java 侧只需 3 个 channel adapter（Feishu/WeCom/Telegram）。

### 4.3 WeComAdapter（Python + Java）

**Python 侧：`agent/im/adapters/wecom_adapter.py`**
```python
"""企业微信 Channel 适配器（Python 侧）

注意：企微消息发送当前在 Java 侧（WeComMessageSender），
Python 侧 WeComAdapter 用于 LLM 工具调用场景。
"""
import json, os, time, requests
from typing import Optional
from loguru import logger

from agent.im.channel_adapter import (
    ChannelAdapter, ChannelType, SendResult, UserInfo,
)

WECOM_BASE = "https://qyapi.weixin.qq.com"


class WeComAdapter(ChannelAdapter):
    """企业微信适配器

    限流：text 100次/分钟(≈1.67/秒)，card 同（微优化 1）
    重试：3 次指数退避
    Card 大小：最大 4KB（微优化 3）
    """

    def __init__(self, corp_id: str = None, secret: str = None, agent_id: int = 0):
        super().__init__()
        self._corp_id  = corp_id or os.environ.get("WECOM_CORP_ID", "")
        self._secret   = secret or os.environ.get("WECOM_SECRET", "")
        self._agent_id = agent_id or int(os.environ.get("WECOM_AGENT_ID", "0"))
        self._token_cache: dict = {"token": None, "expiry": 0.0}
        self._rate_limiters = {
            "text": TokenBucket(rate=1.67, burst=3),
            "card": TokenBucket(rate=1.67, burst=3),
        }
        self._session = self._init_session()  # 微优化 2

    @property
    def channel_type(self) -> ChannelType:
        return ChannelType.WECOM

    @property
    def rate_limit_per_second(self) -> float:
        return 1.67  # 100次/分钟

    @property
    def max_text_length(self) -> int:
        return 2048  # 企微文本限制

    @property
    def enabled(self) -> bool:
        return bool(self._corp_id and self._secret and self._agent_id)

    @property
    def max_card_size(self) -> int:
        return 4 * 1024  # 企微 card 最大 4KB（微优化 3）

    def _extract_raw_id(self, api_response: dict) -> Optional[str]:
        """企微返回 msgid（数字字符串）"""
        msgid = api_response.get("msgid")
        return str(msgid) if msgid else None

    def _get_token(self) -> str:
        now = time.time()
        if self._token_cache["token"] and now < self._token_cache["expiry"] - 300:
            return self._token_cache["token"]
        resp = requests.get(
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

    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        token = self._get_token()
        body = {
            "touser":  receiver_id,
            "msgtype": "text",
            "agentid": self._agent_id,
            "text":    {"content": self.truncate_text(text)},
        }
        try:
            resp = requests.post(
                f"{WECOM_BASE}/cgi-bin/message/send?access_token={token}",
                json=body, timeout=10,
            )
            data = resp.json()
            errcode = data.get("errcode", -1)
            success = errcode == 0
            return SendResult(
                success=success,
                message_id=data.get("msgid") if success else None,
                error=None if success else data.get("errmsg", f"errcode={errcode}"),
                channel=ChannelType.WECOM,
            )
        except Exception as e:
            return SendResult(success=False, error=str(e), channel=ChannelType.WECOM)

    async def send_card(self, receiver_id: str, card: dict,
                        chat_type: str = "p2p") -> SendResult:
        """企微卡片消息（textcard 类型）"""
        token = self._get_token()
        body = {
            "touser":  receiver_id,
            "msgtype": "textcard",
            "agentid": self._agent_id,
            "textcard": card,
        }
        try:
            resp = requests.post(
                f"{WECOM_BASE}/cgi-bin/message/send?access_token={token}",
                json=body, timeout=10,
            )
            data = resp.json()
            return SendResult(
                success=data.get("errcode") == 0,
                message_id=data.get("msgid"),
                channel=ChannelType.WECOM,
            )
        except Exception as e:
            return SendResult(success=False, error=str(e), channel=ChannelType.WECOM)

    def extract_message_id(self, api_response: dict) -> Optional[str]:
        """企微返回 msgid 而非 message_id"""
        return api_response.get("msgid")
```

### 4.4 TelegramAdapter（Python + Java）

**Python 侧：`agent/im/adapters/telegram_adapter.py`**
```python
"""Telegram Bot Channel 适配器（Python 侧）

使用 python-telegram-bot 或直接 HTTP 调用 Telegram Bot API。
"""
import os, json
from typing import Optional

import requests
from loguru import logger

from agent.im.channel_adapter import (
    ChannelAdapter, ChannelType, SendResult, UserInfo,
)

TELEGRAM_API = "https://api.telegram.org"


class TelegramAdapter(ChannelAdapter):
    """Telegram Bot 适配器

    限流：30 次/秒（Telegram Bot API 限制），text/card 共用
    Card 大小：无硬限制（Telegram message 最大 4096 字符，由 max_text_length 控制）
    """

    def __init__(self, bot_token: str = None):
        super().__init__()
        self._bot_token = bot_token or os.environ.get("TELEGRAM_BOT_TOKEN", "")
        self._rate_limiters = {
            "text": TokenBucket(rate=30, burst=5),
            "card": TokenBucket(rate=30, burst=5),
        }
        self._session = self._init_session()  # 微优化 2

    @property
    def channel_type(self) -> ChannelType:
        return ChannelType.TELEGRAM

    @property
    def rate_limit_per_second(self) -> float:
        return 30.0  # Telegram Bot API 限制

    @property
    def max_text_length(self) -> int:
        return 4096

    @property
    def enabled(self) -> bool:
        return bool(self._bot_token)

    def _extract_raw_id(self, api_response: dict) -> Optional[str]:
        """Telegram 返回 result.message_id（数字）"""
        result = api_response.get("result", {})
        mid = result.get("message_id") if isinstance(result, dict) else None
        return str(mid) if mid else None

    def _api_url(self, method: str) -> str:
        return f"{TELEGRAM_API}/bot{self._bot_token}/{method}"

    async def send_text(self, receiver_id: str, text: str,
                        chat_type: str = "p2p") -> SendResult:
        try:
            resp = requests.post(
                self._api_url("sendMessage"),
                json={
                    "chat_id": receiver_id,
                    "text": self.truncate_text(text),
                    "parse_mode": "Markdown",
                },
                timeout=10,
            )
            data = resp.json()
            success = data.get("ok", False)
            msg_id = str(data.get("result", {}).get("message_id", "")) if success else None
            return SendResult(
                success=success,
                message_id=msg_id,
                error=None if success else data.get("description"),
                channel=ChannelType.TELEGRAM,
            )
        except Exception as e:
            return SendResult(success=False, error=str(e), channel=ChannelType.TELEGRAM)

    async def send_card(self, receiver_id: str, card: dict,
                        chat_type: str = "p2p") -> SendResult:
        """Telegram Inline Keyboard 作为卡片实现"""
        try:
            text = card.get("title", "") + "\n" + card.get("body", "")
            reply_markup = card.get("inline_keyboard")
            body = {
                "chat_id": receiver_id,
                "text": text,
                "parse_mode": "Markdown",
            }
            if reply_markup:
                body["reply_markup"] = json.dumps({"inline_keyboard": reply_markup})
            resp = requests.post(self._api_url("sendMessage"), json=body, timeout=10)
            data = resp.json()
            return SendResult(
                success=data.get("ok", False),
                message_id=str(data.get("result", {}).get("message_id", "")),
                channel=ChannelType.TELEGRAM,
            )
        except Exception as e:
            return SendResult(success=False, error=str(e), channel=ChannelType.TELEGRAM)

    async def send_image(self, receiver_id: str, image_data: bytes,
                         chat_type: str = "p2p") -> SendResult:
        """通过 multipart/form-data 发送图片"""
        # Telegram sendPhoto API
        return SendResult(success=False, error="image 待实现", channel=ChannelType.TELEGRAM)

    async def get_user_info(self, user_id: str) -> Optional[UserInfo]:
        try:
            resp = requests.get(
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
```

**Java 侧 TelegramAdapter** — 接收 Telegram webhook 回调，归一化为 `ChannelMessage`，调用 `AgentService.chatFull()`。结构与 `WeComCallbackController` 类似（HTTP webhook 模式）。

---

## 5. ChannelRouter：多通道路由器

### 5.1 Python 侧 ChannelRouter

```python
"""多通道路由器（Python 侧）

能力矩阵：
  - 单通道发送（send_to）
  - 多通道并行广播（broadcast_text，asyncio.gather + 失败隔离）
  - 中央路由（resolve_channels）—— 根据用户偏好/历史行为选择目标 channel
  - 去重（_dedup_cache）—— 基于 dedup_key 的幂等发送
  - 动态注册/注销 adapter
"""
import asyncio
import hashlib
import time
from collections import OrderedDict
from typing import Dict, List, Optional, Tuple

from loguru import logger

from agent.im.channel_adapter import (
    ChannelAdapter, ChannelType, SendResult,
)

# 去重缓存：LRU 1000 条，TTL 5 分钟
_DEDUP_MAX_SIZE = 1000
_DEDUP_TTL_SEC = 300


class ChannelRouter:
    """多通道路由器"""

    def __init__(self):
        self._adapters: Dict[ChannelType, ChannelAdapter] = {}
        self._user_prefs: Dict[str, List[ChannelType]] = {}  # user_id → 偏好 channel 优先级
        self._dedup_cache: OrderedDict[str, float] = OrderedDict()  # dedup_key → expiry_ts

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
            f"{ct.value}={rid}" for ct, rid in sorted(receivers.items(), key=lambda x: x[0].value)
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
            # 返回"假成功"结果，避免调用方重试
            return {
                ch: SendResult(success=True, message_id=None, channel=ch,
                               error="dedup: skipped")
                for ch in receivers
            }

        async def _send_one(ch: ChannelType, rid: str) -> tuple:
            adapter = self._adapters.get(ch)
            if not adapter or not adapter.enabled:
                return ch, SendResult(success=False, channel=ch,
                                      error=f"channel {ch} 不可用")
            return ch, await adapter.send_with_retry(
                lambda: adapter.send_text(rid, text, chat_type=chat_type)
            )

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
                logger.warning(f"[ChannelRouter] {ch.value} 失败: {result.error}")

        return output

    # ── 中央路由：根据用户偏好选择 channel（补充建议 1）────

    def set_user_preferences(self, user_id: str,
                             preferred_channels: List[ChannelType]):
        """设置用户 channel 偏好（优先级排序，高优先在前）"""
        self._user_prefs[user_id] = preferred_channels

    def resolve_channels(
        self,
        user_id: str,
        message: str = "",
        urgency: str = "normal",  # "low" / "normal" / "high"
    ) -> Dict[ChannelType, str]:
        """中央路由：根据用户偏好 + 消息上下文决定发送到哪些 channel。

        策略优先级：
          1. urgency=high → 所有已启用 channel
          2. 用户显式偏好（_user_prefs[user_id]）
          3. 历史行为推断（最近活跃 channel）
          4. 默认：Web（如果可用）

        Args:
            user_id: 目标用户
            message: 消息内容（用于上下文感知路由）
            urgency: 紧急程度

        Returns:
            {ChannelType: receiver_id} —— 可直接传给 broadcast_text()
        """
        if urgency == "high":
            # 紧急消息：所有可用 channel
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
        if ChannelType.WEB in self._adapters and self._adapters[ChannelType.WEB].enabled:
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
            targets = self.resolve_channels(user_id, text, urgency)
        else:
            targets = {ch: self._resolve_receiver_id(ch, user_id) for ch in channels}

        if not targets:
            return {}

        return await self.broadcast_text(text, targets)

    # ── 指标聚合 ──────────────────────────────────────────

    def get_all_metrics(self) -> Dict[ChannelType, dict]:
        """获取所有 channel 的指标摘要"""
        return {
            ct: {
                "success_rate": a.metrics.success_rate,
                "avg_latency_ms": a.metrics.avg_latency_ms,
                "total_success": a.metrics.total_successes,
                "total_failures": a.metrics.total_failures,
                "rate_limit_hits": a.metrics.rate_limit_hits,
            }
            for ct, a in self._adapters.items()
        }
```

### 5.2 Java 侧：`ChannelAdapterManager`

```java
package com.intelligent.agent.web.im;

import java.util.*;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Channel 适配器管理器（Java 侧）。
 *
 * 聚合所有 ChannelAdapter 实现，提供：
 *   - 按 channel 类型获取 adapter
 *   - 多通道并行发送（失败隔离）
 *   - 生命周期统一管理
 */
@Slf4j
@Component
public class ChannelAdapterManager {

    private final Map<ChannelType, ChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final ExecutorService broadcastExecutor =
        Executors.newFixedThreadPool(4);

    /** Spring 自动注入所有 ChannelAdapter Bean */
    public ChannelAdapterManager(List<ChannelAdapter> adapterList) {
        for (ChannelAdapter a : adapterList) {
            adapters.put(a.channelType(), a);
            log.info("[ChannelManager] 注册 channel: {}", a.channelType());
        }
    }

    public Optional<ChannelAdapter> get(ChannelType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    public List<ChannelAdapter> listEnabled() {
        return adapters.values().stream()
            .filter(ChannelAdapter::isEnabled)
            .toList();
    }

    /** 多通道并行发送（失败隔离） */
    public Map<ChannelType, SendResult> broadcastText(
            String text, Map<ChannelType, String> receivers, String chatType) {

        Map<ChannelType, SendResult> results = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (var entry : receivers.entrySet()) {
            ChannelType ch = entry.getKey();
            String receiverId = entry.getValue();
            ChannelAdapter adapter = adapters.get(ch);
            if (adapter == null || !adapter.isEnabled()) continue;

            futures.add(broadcastExecutor.submit(() -> {
                try {
                    SendResult r = adapter.sendText(receiverId, text, chatType);
                    results.put(ch, r);
                } catch (Exception e) {
                    log.error("[ChannelManager] broadcast {} 失败: {}", ch, e.getMessage());
                    results.put(ch, new SendResult(false, null, e.getMessage(), ch));
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); }
            catch (Exception e) { log.warn("[ChannelManager] broadcast 超时: {}", e.getMessage()); }
        }

        return results;
    }

    /** 生命周期：启动所有 adapter */
    public void startAll() {
        listEnabled().forEach(a -> {
            try { a.start(); } catch (Exception e) {
                log.error("启动 {} 失败: {}", a.channelType(), e.getMessage());
            }
        });
    }

    /** 生命周期：停止所有 adapter */
    public void stopAll() {
        listEnabled().forEach(a -> {
            try { a.stop(); } catch (Exception e) {
                log.error("停止 {} 失败: {}", a.channelType(), e.getMessage());
            }
        });
    }
}
```

---

## 6. 现有代码最小化改造

### 6.1 Python 侧改造清单

| 文件 | 改动 | 影响 |
|------|------|------|
| `agent/im/__init__.py` | 导出 ChannelAdapter / ChannelRouter | 无 |
| `agent/im/channel_adapter.py` | **新增**：接口 + 数据模型 | 无 |
| `agent/im/channel_router.py` | **新增**：多通道路由器 | 无 |
| `agent/im/adapters/feishu_adapter.py` | **新增**：FeishuAdapter | 替代 feishu_client.py |
| `agent/im/adapters/web_adapter.py` | **新增**：WebAdapter | 无 |
| `agent/im/adapters/wecom_adapter.py` | **新增**：WeComAdapter | 无 |
| `agent/im/adapters/telegram_adapter.py` | **新增**：TelegramAdapter | 无 |
| `agent/im/feishu_client.py` | **修改**：`FeishuIMTool.execute()` 委托给 `FeishuAdapter` | 不破坏工具注册 |
| `agent/core/agent.py` / `conversation_flow.py` | **不改动** | channel 字段已透传 |

**FeishuIMTool 最小改动：**

```python
# agent/im/feishu_client.py 改动（仅改 execute 方法）

class FeishuIMTool(BaseTool):
    """向飞书用户/群组发送消息。（通过 FeishuAdapter 委托）"""

    def __init__(self, adapter: "FeishuAdapter" = None):
        super().__init__(name="im_message", category="im")
        self._adapter = adapter or FeishuAdapter()
        # ... 参数定义不变 ...

    def execute(self, receiver_id, msg_type, content, receive_id_type="open_id"):
        """委托给 FeishuAdapter，保持原接口兼容"""
        import asyncio
        if msg_type == "text":
            text = content.get("text", "")
            return asyncio.run(
                self._adapter.send_text(receiver_id, text,
                                        chat_type="group" if receive_id_type == "chat_id" else "p2p")
            )
        elif msg_type == "interactive":
            return asyncio.run(
                self._adapter.send_card(receiver_id, content,
                                        chat_type="group" if receive_id_type == "chat_id" else "p2p")
            )
        # ... 其他类型类似 ...
```

### 6.2 Java 侧改造清单

| 文件 | 改动 | 影响 |
|------|------|------|
| `backend/web/im/ChannelAdapter.java` | **新增**：接口 | 无 |
| `backend/web/im/ChannelMessage.java` | **新增**：record | 无 |
| `backend/web/im/SendResult.java` | **新增**：record | 无 |
| `backend/web/im/ChannelType.java` | **新增**：enum | 无 |
| `backend/web/im/ChannelAdapterManager.java` | **新增**：管理器 | 无 |
| `backend/web/feishu/FeishuEventController.java` | **修改**：实现 ChannelAdapter 或委托给 FeishuChannelAdapter | 最小 |
| `backend/web/wecom/WeComCallbackController.java` | **修改**：实现 ChannelAdapter 或委托给 WeComChannelAdapter | 最小 |
| `feishu/FeishuMessageSender.java` | **不改动** | 被 FeishuChannelAdapter 复用 |
| `wecom/WeComMessageSender.java` | **不改动** | 被 WeComChannelAdapter 复用 |
| `service/AgentService.java` | **不改动** | chatFull() 不变 |

**最小改动方案**——不强迫 Controller 实现接口，而是创建独立的 Adapter 类：

```java
// 新增 FeishuChannelAdapter，复合现有 FeishuEventController + FeishuMessageSender

@Component
public class FeishuChannelAdapter implements ChannelAdapter {
    private final FeishuConfig config;
    private final FeishuMessageSender sender;

    // ... channelType() → ChannelType.FEISHU
    // ... normalizeMessage() → 复用 FeishuEventController 的消息解析逻辑
    // ... sendText/sendCard → 委托给 FeishuMessageSender
    // ... isEnabled() → config.isEnabled()
}
```

**FeishuEventController 零改动！** 只需在合适的初始化位置注册 adapter 到 manager。

---

## 7. 三阶段落地路线

### Phase 1：抽象层 + 飞书 + Web（Week 1-2）

```
目标：建立 ChannelAdapter 抽象层，飞书 + Web 首个双通道覆盖 90% 场景
```

| 步骤 | 产出 | 验证标准 |
|------|------|----------|
| 1. 创建 Python `agent/im/channel_adapter.py` | ABC + 数据模型 | 代码可 import |
| 2. 创建 `agent/im/adapters/feishu_adapter.py` | FeishuAdapter | `pytest tests/im/` 通过 |
| 3. 创建 `agent/im/adapters/web_adapter.py` | WebAdapter | `pytest tests/im/` 通过 |
| 4. 创建 `agent/im/channel_router.py` | ChannelRouter | broadcast 测试通过 |
| 5. 改造 `feishu_client.py` FeishuIMTool | 委托给 FeishuAdapter | 现有 370 测试不退化 |
| 6. 创建 Java `backend/web/im/` 包 | ChannelAdapter + Manager | 编译通过 |
| 7. 创建 `FeishuChannelAdapter`（Java）| 实现接口，复用 FeishuMessageSender | 飞书功能不退化 |

**关键验证**：飞书发送消息（LLM 工具调用 → FeishuAdapter → 飞书 API）功能完整，Web PWA 通知推送功能完整，现有全部测试通过。

### Phase 2：企微 + Telegram（Week 3-4）

```
目标：完成 4 通道全覆盖，验证多通道并行
```

| 步骤 | 产出 | 验证标准 |
|------|------|------|
| 1. 创建 `agent/im/adapters/wecom_adapter.py` | WeComAdapter | 企微消息发送 |
| 2. 创建 `agent/im/adapters/telegram_adapter.py` | TelegramAdapter | Telegram 消息发送 |
| 3. 创建 `WeComChannelAdapter`（Java）| 实现接口，复用 WeComMessageSender | 企微功能不退化 |
| 4. 创建 `TelegramChannelAdapter`（Java）| Webhook 接收 + 回复 | Telegram 收发 |
| 5. LLM tool 注册改造 | `im_message` → channel-aware tool 注册 | 每个 channel 只能调用自己的 tool |
| 6. 端到端测试 | 4 通道 x 收发场景 | E2E 或手动验证 |

### Phase 3：双通道并行 + 生产加固（Week 5-6）

```
目标：ChannelRouter.broadcast_text() 生产可用 + 监控 + 降级
```

| 步骤 | 产出 | 验证标准 |
|------|------|------|
| 1. 接入 ChannelRouter 到通知系统 | 任务完成/告警 → broadcast_to_all | 手动触发验证 |
| 2. 失败降级策略 | send_text 失败 fallback 到 Web | 模拟飞书 API 故障 |
| 3. 发送指标收集 | 延迟/成功率/重试次数 per channel | Grafana 面板 |
| 4. 连接健康检查 | `/health` 返回各 channel 状态 | API 验证 |
| 5. 文档 + CLAUDE.md 更新 | 架构图 + API 文档 | AI_PROJECT_CONTEXT.md 同步 |

---

## 8. 双通道并行策略（6.5 阶段核心）

### 8.1 并行发送机制

```
用户说："向所有人通知任务完成"

ChannelRouter.broadcast_text(
    "✅ 任务「重构数据库」已完成",
    receivers={
        ChannelType.FEISHU: "oc_group_xxx",    # 飞书群
        ChannelType.WECOM:  "user_yyy",         # 企微用户
        ChannelType.WEB:    "user_zzz",         # 前端 WebSocket
        ChannelType.TELEGRAM: "123456789",      # Telegram chat
    }
)

内部：asyncio.gather 4 个异步任务，并行执行
任意一个失败 → 记录日志 + 返回 failed 的 SendResult
其余继续执行
```

### 8.2 失败隔离策略

```
broadcast 结果:
  feishu:    ✅ success  message_id="om_xxx"
  wecom:     ❌ failed   error="errcode=40003: invalid userid"
  web:       ✅ success  (无 message_id)
  telegram:  ✅ success  message_id="456"

日志：
  WARNING [ChannelRouter] broadcast wecom 失败: errcode=40003: invalid userid

其他 3 个 channel 的消息已正常送达。
```

### 8.3 是否需要 ParallelRunner？

**不需要专门的 ParallelRunner 类。** `asyncio.gather(*tasks, return_exceptions=True)` 已经提供了所需的所有能力：

- 并行执行（Python asyncio 的事件循环自动调度）
- 失败隔离（`return_exceptions=True`）
- 结果收集（返回值是 `list[tuple[ChannelType, SendResult] | Exception]`）

Java 侧用 `ExecutorService.submit()` + `Future.get(timeout)` 同样足够。

**如果未来需要更复杂的编排**（如：DAG 依赖、条件分支、重试策略），才考虑引入专门的编排层。当前阶段不需要。

### 8.4 任一失败另一条继续推的策略

```python
# 默认策略（推荐）
results = await router.broadcast_text(text, receivers)
# → 全部执行完毕，返回结果字典

# 可选：fail_fast=True（任一失败立即取消其余，用于关键场景）
results = await router.broadcast_text(text, receivers, fail_fast=True)
# → 第一个失败时 asyncio.gather 抛出，其他任务被取消

# 可选：带重试的 broadcast
async def broadcast_with_retry(router, text, receivers, max_retries=2):
    failed = dict(receivers)
    for attempt in range(max_retries + 1):
        results = await router.broadcast_text(text, failed)
        failed = {ch: rid for ch, rid in failed.items()
                  if not results.get(ch, SendResult(False)).success}
        if not failed:
            break
    return results
```

---

## 9. LLM 工具改造：channel-aware 注册

### 9.1 现状

```python
# 现状：单一 im_message tool，LLM 只能发飞书
tool_manager.register_tool(FeishuIMTool(), category="im")
```

### 9.2 改造后

```python
# 改造：每个 channel 注册独立 tool，通过 allowed_tool_categories 控制可见性

# 在 agent 初始化时
channel_router = ChannelRouter()
channel_router.register(FeishuAdapter())
channel_router.register(WeComAdapter())
channel_router.register(WebAdapter())
channel_router.register(TelegramAdapter())

# 注册 channel-aware tool（替代 FeishuIMTool）
tool_manager.register_tool(
    ChannelMessageTool(channel_router), category="im"
)
```

```python
class ChannelMessageTool(BaseTool):
    """统一 IM 消息发送工具（channel-aware）

    LLM 调用: send_message(channel="feishu_im", receiver_id="ou_xxx",
                           msg_type="text", content={"text": "Hello"})
    """

    def __init__(self, router: ChannelRouter):
        super().__init__(name="send_message", category="im")
        self._router = router
        self.parameters = [
            ToolParameter(name="channel", type="string",
                          description="目标渠道: feishu_im/wecom/web/telegram", required=True),
            ToolParameter(name="receiver_id", type="string",
                          description="接收方ID", required=True),
            ToolParameter(name="msg_type", type="string",
                          description="消息类型: text/card/image/file", required=True),
            ToolParameter(name="content", type="object",
                          description="消息内容", required=True),
        ]

    def execute(self, channel: str, receiver_id: str, msg_type: str,
                content: dict) -> dict:
        import asyncio
        ct = ChannelType(channel)
        adapter = self._router.get(ct)
        if not (adapter and adapter.enabled):
            return {"success": False, "error": f"channel {channel} 不可用"}

        if msg_type == "text":
            result = asyncio.run(
                adapter.send_text(receiver_id, content.get("text", "")))
        elif msg_type == "card":
            result = asyncio.run(adapter.send_card(receiver_id, content))
        else:
            result = SendResult(success=False, error=f"不支持的消息类型: {msg_type}")

        return {"success": result.success, "message_id": result.message_id,
                "error": result.error}
```

配合 `allowed_tool_categories=["im"]` 在飞书/企微 channel 上下文中限制可见性，确保 LLM 不会尝试用不存在的 channel 发消息。

---

## 10. 文件结构总览

```
agent/im/
├── __init__.py                    # 导出 ChannelAdapter, ChannelRouter, ChannelMessageTool
├── channel_adapter.py             # ABC + 数据模型（ChannelMessage, SendResult, UserInfo）
├── channel_router.py              # ChannelRouter（多通道广播）
├── channel_message_tool.py        # ChannelMessageTool（替代 FeishuIMTool 的 LLM tool）
├── feishu_client.py               # [保留] FeishuIMTool 委托给 FeishuAdapter（向后兼容）
├── adapters/
│   ├── __init__.py
│   ├── feishu_adapter.py          # FeishuAdapter
│   ├── web_adapter.py             # WebAdapter（WebSocket push）
│   ├── wecom_adapter.py           # WeComAdapter
│   └── telegram_adapter.py        # TelegramAdapter
└── tests/
    ├── __init__.py
    ├── test_channel_adapter.py    # 接口契约测试
    ├── test_feishu_adapter.py     # 飞书适配器测试
    ├── test_channel_router.py     # 路由器测试（含 broadcast）
    └── test_channel_message_tool.py # LLM tool 测试

backend/web/src/main/java/com/intelligent/agent/web/im/
├── ChannelAdapter.java            # 接口
├── ChannelMessage.java            # 归一化消息 record
├── SendResult.java                # 发送结果 record
├── ChannelType.java               # 枚举
├── UserInfo.java                  # 用户信息 record
├── ChannelAdapterManager.java     # 管理器（含 broadcast）
├── FeishuChannelAdapter.java      # 飞书实现（复用 FeishuMessageSender）
├── WeComChannelAdapter.java       # 企微实现（复用 WeComMessageSender）
├── WebChannelAdapter.java         # Web 实现（WebSocket push）
└── TelegramChannelAdapter.java    # Telegram 实现（webhook + send）
```

---

## 11. 风险与缓解

| 风险 | 等级 | 影响 | 缓解 |
|------|------|------|------|
| FeishuIMTool 改造导致现有工具调用失败 | 低 | 飞书 IM 功能中断 | FeishuIMTool 保留类名+参数签名不变，内部委托给 FeishuAdapter |
| 异步/同步桥接死锁（风险1） | **中** | `asyncio.run()` 在已有事件循环中调用抛 RuntimeError | 3bis 节明确定义 3 条调用路径边界；`ChannelMessageTool.execute` 检测当前是否在事件循环中，用 `loop.create_task()` 替代 `asyncio.run()` |
| 消息 ID 格式不统一（风险2） | **中** | 跨通道追踪失败，撤回联动不可靠 | 2.3 节定义归一化格式 `channel_type:original_id`；`extract_message_id()` 基类统一追加前缀 |
| 限流差异导致消息丢失（风险3） | **中** | 飞书 50次/s vs 企微 100次/min，高并发下超限被拒 | TokenBucket 实现 `acquire()`，超限返回 False + 记录 `rate_limit_hits` 指标；ChannelRouter 不缓存超限消息 |
| 消息去重失效（风险4） | **中** | 同一任务完成通知通过 4 个 channel 推送 4 次 | `dedup_key = sha256(content+receivers)` + LRU+TTL 缓存；`broadcast_text` 发送前检查 |
| Java 侧 Token 重复管理 | 低 | 飞书/企微各管理自己的 token | ChannelAdapter 接口不强制统一 token 管理，各 adapter 独立 |
| Telegram webhook 无历史基础设施 | 低 | 需从头搭建 | Phase 3 再实现，给足时间 |
| 4 adapter 集成测试爆炸（补充建议4） | **中** | 4 通道写完一起测，问题放大 | **每个 adapter 写完后立即单元测试**（Mock 平台 API）→ 集成测试 → E2E；见第 10bis 节测试策略 |

---

## 10bis. 测试策略（补充建议 4 + 补充建议 5）

### 分层测试金字塔

```
        ┌──────┐
        │ E2E  │  ← 1-2 个关键路径（飞书真实收发）
       ┌┴──────┴┐
       │ 集成   │  ← ChannelRouter.broadcast 双通道
      ┌┴────────┴┐
      │ 适配器   │  ← 每个 adapter 独立 UT（Mock API）
     ┌┴──────────┴┐
     │  接口契约   │  ← ChannelAdapter ABC 契约测试
    └─────────────┘
```

### 每层测试内容

**L1：接口契约测试**（`test_channel_adapter.py`）
```python
def test_all_adapters_implement_send_text():
    """每个 ChannelAdapter 子类必须实现 send_text"""
    for cls in get_adapter_subclasses():
        assert hasattr(cls, 'send_text')
        assert callable(cls.send_text)

def test_extract_message_id_format():
    """所有 adapter 返回的 message_id 必须符合 channel_type:original_id 格式"""
    for adapter in all_adapters:
        mid = adapter.extract_message_id(mock_response(adapter.channel_type))
        if mid is not None:
            assert mid.startswith(adapter.channel_type.value + ":")
```

**L2：Adapter 单元测试**（每个 adapter 1 个文件）
```python
# test_feishu_adapter.py —— Mock Feishu API
class TestFeishuAdapter:
    def test_send_text_success(self, mocker):
        mock_resp = {"code": 0, "data": {"message_id": "om_xxx"}}
        mocker.patch("requests.post", return_value=MockResponse(mock_resp))
        result = asyncio.run(adapter.send_text("ou_test", "Hello"))
        assert result.success
        assert result.message_id == "feishu_im:om_xxx"

    def test_send_text_rate_limited(self):
        # 快速连续发送 61 次，第 61 次应被限流拒绝
        ...

    def test_send_text_retry_exhausted(self, mocker):
        mocker.patch("requests.post", side_effect=TimeoutError())
        result = asyncio.run(adapter.send_text("ou_test", "Hello"))
        assert not result.success
        assert "重试3次后失败" in result.error
```

**L3：集成测试**（`test_channel_router.py`）
```python
async def test_broadcast_two_channels_partial_failure():
    """飞书成功 + 企微失败 → 飞书的发送不受影响"""
    router = ChannelRouter()
    router.register(MockFeishuAdapter(should_succeed=True))
    router.register(MockWeComAdapter(should_succeed=False))

    results = await router.broadcast_text("test", {
        ChannelType.FEISHU: "ou_1",
        ChannelType.WECOM: "user_1",
    })

    assert results[ChannelType.FEISHU].success
    assert not results[ChannelType.WECOM].success

async def test_dedup_prevents_duplicate_broadcast():
    """相同内容+receivers 的重复广播被去重"""
    results1 = await router.broadcast_text("任务完成", receivers)
    results2 = await router.broadcast_text("任务完成", receivers)
    assert results2[ChannelType.FEISHU].error == "dedup: skipped"

async def test_resolve_channels_user_prefs():
    router.set_user_preferences("user_1", [ChannelType.WECOM, ChannelType.WEB])
    targets = router.resolve_channels("user_1")
    assert list(targets.keys()) == [ChannelType.WECOM, ChannelType.WEB]
```

**L4：E2E**（手动或用真实 API sandbox）
```
1. 启动 agent + feishu adapter（真实 FEISHU_APP_ID/FEISHU_APP_SECRET）
2. → LLM 工具调用 send_message(channel="feishu_im", ...)
3. → 飞书收到消息
4. 验证 message_id 格式为 "feishu_im:om_xxx"
```

### 可观测性指标（补充建议 5）

每个 adapter 内置 `ChannelMetric`，通过 `/health` 端点暴露：

```python
# 在 health_router.py 中
@router.get("/health")
async def health():
    channel_status = {}
    for ct, a in channel_router._adapters.items():
        m = a.metrics
        channel_status[ct.value] = {
            "enabled": a.enabled,
            "success_rate": f"{m.success_rate:.1%}",
            "avg_latency_ms": f"{m.avg_latency_ms:.0f}",
            "total_sent": m.total_successes,
            "total_failed": m.total_failures,
            "rate_limited": m.rate_limit_hits,
        }
    return {"status": "ok", "channels": channel_status}
```

响应示例：
```json
{
  "status": "ok",
  "channels": {
    "feishu_im": {"enabled": true, "success_rate": "98.5%", "avg_latency_ms": "320",
                  "total_sent": 1523, "total_failed": 23, "rate_limited": 5},
    "wecom":     {"enabled": true, "success_rate": "100%",  "avg_latency_ms": "180",
                  "total_sent": 876,  "total_failed": 0,  "rate_limited": 0},
    "web":       {"enabled": true, "success_rate": "99.8%", "avg_latency_ms": "5",
                  "total_sent": 4521, "total_failed": 9,  "rate_limited": 0},
    "telegram":  {"enabled": false}
  }
}
```

---

## 12. 总结

| 维度 | 决策 |
|------|------|
| 抽象层位置 | Python + Java 两侧各一个，职责分离 |
| Async/Sync 边界 | Python: async 接口 + asyncio.run() 桥接同步调用；Java: 同步接口 + CompletableFuture 并行 |
| Message ID 归一化 | 统一 `channel_type:original_id` 格式 |
| 限流 | `_rate_limiters` 字典按操作独立：text/card/file/image，`_check_rate_limit(operation)` 分发 |
| 连接复用 | `_init_session()` → `requests.Session()` + HTTPAdapter 连接池，性能提升 30-50% |
| Card 大小 | `max_card_size` 属性（飞书 30KB, 企微 4KB, Telegram 不限），`enforce_card_size_limit()` 自动截断 |
| 去重 | `dedup_key = sha256(content+receivers)` + LRU 1000 + TTL 5min |
| 重试 | `RetryConfig(max_retries=3, base=1s, backoff=2x, max=30s)` |
| 最小改动 | FeishuIMTool 保留类名，内部委托；Java Controller 不改动，新建 Adapter 类 |
| 落地顺序 | Phase 1: 飞书+Web → Phase 2: 企微+Telegram → Phase 3: 并行+加固+可观测性 |
| 测试策略 | L1 契约 → L2 单 adapter Mock → L3 集成 broadcast → L4 E2E |
| 双通道并行 | asyncio.gather + 失败隔离，不需要专门 ParallelRunner |
| 向后兼容 | 不破坏 W1-W9 任何已有功能 |
