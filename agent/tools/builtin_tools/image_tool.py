"""图片生成工具——通过 BaseImageProvider 接口调用，与具体实现解耦"""
import uuid
from pathlib import Path
from typing import Optional

import httpx
from loguru import logger

from tools.base_tool import BaseTool

_IMAGE_DIR = Path("/app/data/images")


class ImageGenerationTool(BaseTool):
    """
    根据文字描述生成图片。
    底层 Provider 由 services.image.create_image_provider() 决定，
    切换 IMAGE_GEN_PROVIDER 配置即可透明替换为 sd_webui 等本地方案。
    """

    def __init__(self):
        super().__init__(
            description=(
                "根据文字描述生成图片。参数: "
                "prompt(图片描述,英文效果更好,必填), "
                "size(尺寸,可选,如 1024x1024 / 512x512 / 768x1024,默认1024x1024), "
                "negative_prompt(不希望出现的内容,可选), "
                "style(风格提示词,可选,如 photorealistic/anime/oil painting)"
            )
        )

    async def execute_async(
        self,
        prompt: str,
        size: Optional[str] = None,
        negative_prompt: str = "",
        style: Optional[str] = None,
    ) -> str:
        from services.image import create_image_provider, ImageRequest
        from config.settings import settings

        provider = create_image_provider()
        if provider is None or not provider.is_available():
            tip = (
                "❌ 图片生成未启用。\n\n"
                "**当前配置说明**\n"
                f"- Provider: `{settings.image_gen_provider}`\n"
                "- 如需使用 SiliconFlow：在 `.env.docker` 填入 `IMAGE_GEN_API_KEY`\n"
                "- 如需使用本地 SD WebUI：设置 `IMAGE_GEN_PROVIDER=sd_webui` 并确保服务已启动"
            )
            return tip

        req = ImageRequest(
            prompt=prompt,
            size=size or settings.image_gen_size,
            steps=settings.image_gen_steps,
            style=style,
            negative_prompt=negative_prompt,
        )
        result = await provider.generate(req)

        if not result.success:
            return f"❌ 图片生成失败（{result.provider}）：{result.error}"

        # 统一持久化到本地，确保图片在历史对话中长期可访问
        filename = f"gen_{uuid.uuid4().hex[:12]}.png"
        local_url = await _persist_image(result, filename)

        short_prompt = prompt[:40] + ("..." if len(prompt) > 40 else "")
        provider_tag = f"`{result.provider}/{result.model}`" if result.model else f"`{result.provider}`"
        return (
            f"已为你生成图片（{provider_tag}）：\n\n"
            f"![{short_prompt}]({local_url})"
        )

    def execute(
        self,
        prompt: str,
        size: Optional[str] = None,
        negative_prompt: str = "",
        style: Optional[str] = None,
    ) -> str:
        import asyncio
        return asyncio.run(self.execute_async(prompt, size, negative_prompt, style))


async def _persist_image(result, filename: str) -> str:
    """
    将 ImageResult 保存到本地并返回可访问的相对 URL。
    优先使用 image_bytes；若只有 image_url 则下载后保存。
    """
    _IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    dest = _IMAGE_DIR / filename

    try:
        if result.image_bytes:
            dest.write_bytes(result.image_bytes)
        elif result.image_url:
            async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
                resp = await client.get(result.image_url)
                resp.raise_for_status()
                dest.write_bytes(resp.content)
        else:
            raise ValueError("ImageResult 既无 bytes 也无 url")
        logger.info(f"图片已本地化: {dest}")
        return f"/api/images/{filename}"
    except Exception as e:
        logger.warning(f"图片本地化失败，回退到远端 URL: {e}")
        # 降级：直接返回远端 URL（云端有时效性，但优于报错）
        return result.image_url or "（图片保存失败）"
