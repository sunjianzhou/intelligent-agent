"""Channel Adapter 工厂

自动发现、创建、注册所有已配置的 channel adapter。
避免手写 if-else 链。
"""
from __future__ import annotations

import os
from typing import List

from loguru import logger

from im.channel_adapter import ChannelAdapter, ChannelType


class ChannelAdapterFactory:
    """Adapter 工厂：根据环境变量自动创建所有 enabled adapter

    用法:
        from im.adapter_factory import ChannelAdapterFactory
        adapters = ChannelAdapterFactory.create_all(ws_manager=ws_manager)
        for a in adapters:
            channel_router.register(a)
    """

    # {ChannelType: (adapter_class, required_env_var)}
    _registry: dict = {}

    @classmethod
    def register(cls, channel_type: ChannelType, adapter_class,
                 required_env: str = None):
        """注册一个 adapter 类型到工厂"""
        cls._registry[channel_type] = (adapter_class, required_env)

    @classmethod
    def create_all(cls, **deps) -> List[ChannelAdapter]:
        """创建所有 enabled 的 adapter 实例

        Args:
            **deps: 传递给 adapter 构造函数的依赖（如 ws_manager）
        Returns:
            已创建的 adapter 列表（仅 enabled 的）
        """
        _register_all()  # 确保注册表已填充
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


# ── 注册表 ────────────────────────────────────────────────
_registered = False


def _register_all():
    """延迟导入以避免循环依赖"""
    global _registered
    if _registered:
        return
    _registered = True

    from im.adapters.feishu_adapter import FeishuAdapter
    from im.adapters.web_adapter import WebAdapter
    from im.adapters.wecom_adapter import WeComAdapter
    from im.adapters.telegram_adapter import TelegramAdapter

    ChannelAdapterFactory.register(
        ChannelType.FEISHU, FeishuAdapter, "FEISHU_APP_ID"
    )
    ChannelAdapterFactory.register(
        ChannelType.WECOM, WeComAdapter, "WECOM_CORP_ID"
    )
    ChannelAdapterFactory.register(
        ChannelType.WEB, WebAdapter, None  # 始终可用
    )
    ChannelAdapterFactory.register(
        ChannelType.TELEGRAM, TelegramAdapter, "TELEGRAM_BOT_TOKEN"
    )
