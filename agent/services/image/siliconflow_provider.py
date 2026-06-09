"""SiliconFlow 图片生成 Provider"""
import httpx
from loguru import logger

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult


class SiliconFlowProvider(BaseImageProvider):
    """
    调用 SiliconFlow /v1/images/generations 接口。
    文档：https://docs.siliconflow.cn/reference/image-generations
    免费模型：black-forest-labs/FLUX.1-schnell
    """

    def __init__(self, api_key: str, model: str, base_url: str, steps: int):
        self._api_key  = api_key
        self._model    = model
        self._base_url = base_url.rstrip("/")
        self._steps    = steps

    @property
    def provider_name(self) -> str:
        return "siliconflow"

    @property
    def current_model(self) -> str:
        return self._model

    def is_available(self) -> bool:
        return bool(self._api_key)

    async def generate(self, req: ImageRequest) -> ImageResult:
        if not self.is_available():
            return self.unavailable_result(
                "SiliconFlow API Key 未配置，请在 .env.docker 中设置 IMAGE_GEN_API_KEY"
            )

        prompt = f"{req.prompt}, {req.style}" if req.style else req.prompt
        payload = {
            "model":                self._model,
            "prompt":               prompt,
            "image_size":           req.size,
            "num_inference_steps":  req.steps or self._steps,
            "guidance_scale":       req.guidance_scale,
            "batch_size":           1,
        }
        if req.negative_prompt:
            payload["negative_prompt"] = req.negative_prompt
        payload.update(req.extra)

        logger.info(f"[SiliconFlow] 生成请求 model={self._model} size={req.size} "
                    f"prompt={prompt[:60]}")
        try:
            async with httpx.AsyncClient(timeout=90) as client:
                resp = await client.post(
                    f"{self._base_url}/v1/images/generations",
                    headers={
                        "Authorization": f"Bearer {self._api_key}",
                        "Content-Type":  "application/json",
                    },
                    json=payload,
                )
                resp.raise_for_status()
                data  = resp.json()
                images = data.get("images") or []
                if not images or not images[0].get("url"):
                    return self.unavailable_result(f"API 返回空结果: {data}")
                logger.info(f"[SiliconFlow] 生成成功: {images[0]['url'][:80]}")
                return ImageResult(
                    success=True,
                    image_url=images[0]["url"],
                    provider=self.provider_name,
                    model=self._model,
                )
        except httpx.HTTPStatusError as e:
            body = e.response.text[:300] if e.response else ""
            code = e.response.status_code
            if code == 401:
                return self.unavailable_result("API Key 无效，请检查 IMAGE_GEN_API_KEY")
            if code == 429:
                return self.unavailable_result("请求频率超限，请稍后重试")
            return self.unavailable_result(f"HTTP {code}: {body}")
        except httpx.TimeoutException:
            return self.unavailable_result("请求超时（90s），请稍后重试")
        except Exception as e:
            logger.error(f"[SiliconFlow] 异常: {e}")
            return self.unavailable_result(str(e))
