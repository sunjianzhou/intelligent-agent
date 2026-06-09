"""图片生成 Provider 工厂"""
from typing import Optional

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult
from config.settings import settings


def create_image_provider() -> Optional[BaseImageProvider]:
    """
    根据 IMAGE_GEN_PROVIDER 配置返回对应 Provider 实例。
    任意 Provider 构造失败时返回 None（工具侧会提示未配置）。

    切换方式：在 .env.docker 中设置
        IMAGE_GEN_PROVIDER=siliconflow   # 默认
        IMAGE_GEN_PROVIDER=sd_webui      # 本地 Stable Diffusion WebUI
    """
    provider_name = settings.image_gen_provider.lower()

    try:
        if provider_name == "sd_webui":
            from services.image.sd_webui_provider import SDWebUIProvider
            return SDWebUIProvider(
                base_url=settings.image_gen_base_url,
                model=settings.image_gen_model,
                username=getattr(settings, "image_gen_sd_user", ""),
                password=getattr(settings, "image_gen_sd_pass", ""),
            )

        # 默认 siliconflow
        from services.image.siliconflow_provider import SiliconFlowProvider
        return SiliconFlowProvider(
            api_key=settings.image_gen_api_key,
            model=settings.image_gen_model,
            base_url=settings.image_gen_base_url,
            steps=settings.image_gen_steps,
        )
    except Exception as e:
        from loguru import logger
        logger.warning(f"图片 Provider 初始化失败，将禁用图片生成: {e}")
        return None


__all__ = [
    "BaseImageProvider",
    "ImageRequest",
    "ImageResult",
    "create_image_provider",
]
