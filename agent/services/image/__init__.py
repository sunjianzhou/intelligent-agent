"""图片生成 Provider 工厂

切换方式：在 .env 中设置 IMAGE_GEN_PROVIDER：
  sd_webui    — 本地 AUTOMATIC1111 SD WebUI（默认，推荐）
  comfyui     — 本地 ComfyUI（工作流驱动）
  diffusers   — HuggingFace diffusers 进程内直接推理（无需外部服务）
  siliconflow — 云端（需 IMAGE_GEN_API_KEY）
"""
from typing import Optional

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult
from config.settings import settings


def create_image_provider() -> Optional[BaseImageProvider]:
    """
    根据 IMAGE_GEN_PROVIDER 配置实例化对应 Provider。
    构造失败时记录警告并返回 None（工具侧会展示未配置提示）。
    """
    from loguru import logger
    provider_name = settings.image_gen_provider.lower()
    logger.debug(f"初始化图片生成 Provider: {provider_name}")

    try:
        if provider_name == "sd_webui":
            from services.image.sd_webui_provider import SDWebUIProvider
            return SDWebUIProvider(
                base_url=settings.image_gen_base_url,
                model=settings.image_gen_model,
                username=settings.image_gen_sd_user,
                password=settings.image_gen_sd_pass,
            )

        if provider_name == "comfyui":
            from services.image.comfyui_provider import ComfyUIProvider
            return ComfyUIProvider(
                base_url=settings.image_gen_base_url,
                workflow_path=settings.image_gen_comfyui_workflow,
                model=settings.image_gen_model,
            )

        if provider_name == "diffusers":
            from services.image.diffusers_provider import DiffusersProvider
            return DiffusersProvider(
                model_id=settings.image_gen_diffusers_model,
                device=settings.image_gen_diffusers_device,
            )

        # 兜底：siliconflow（云端）
        if provider_name == "siliconflow":
            from services.image.siliconflow_provider import SiliconFlowProvider
            return SiliconFlowProvider(
                api_key=settings.image_gen_api_key,
                model=settings.image_gen_model or "black-forest-labs/FLUX.1-schnell",
                base_url=settings.image_gen_base_url,
                steps=settings.image_gen_steps,
            )

        logger.warning(f"未知图片 Provider: {provider_name}，图片生成已禁用")
        return None

    except Exception as e:
        logger.warning(f"图片 Provider ({provider_name}) 初始化失败，将禁用图片生成: {e}")
        return None


__all__ = [
    "BaseImageProvider",
    "ImageRequest",
    "ImageResult",
    "create_image_provider",
]
