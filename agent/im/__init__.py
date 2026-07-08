"""IM Channel Adapter 抽象层。

导出：
  - ChannelAdapter（ABC 基类）
  - ChannelRouter（多通道路由器）
  - ChannelAdapterFactory（适配器工厂）
  - ChannelMessageTool（统一 LLM IM 工具）
  - 数据模型：ChannelType, MessageType, MessageStatus, ChannelMessage, SendResult, UserInfo
  - 基础设施：RetryConfig, TokenBucket, ChannelMetric
"""
from im.channel_adapter import (
    # 枚举
    ChannelType,
    MessageType,
    MessageStatus,
    # 数据模型
    ChannelMessage,
    SendResult,
    UserInfo,
    # 基础设施
    RetryConfig,
    TokenBucket,
    ChannelMetric,
    # ABC
    ChannelAdapter,
)
