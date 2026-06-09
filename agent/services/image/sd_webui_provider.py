"""Stable Diffusion WebUI (AUTOMATIC1111) 图片生成 Provider

接入步骤：
1. 本地启动 SD WebUI，启动参数加 --api（开启 API 模式）
   示例：python launch.py --api --listen --port 7860
2. docker-compose.yml 的 agent 服务环境变量设置：
     IMAGE_GEN_PROVIDER=sd_webui
     IMAGE_GEN_BASE_URL=http://host.docker.internal:7860
3. 无需 API Key（默认无鉴权；如开启了 --api-auth 则设置 SD_WEBUI_USER/SD_WEBUI_PASS）
"""
import base64
import httpx
from loguru import logger

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult


class SDWebUIProvider(BaseImageProvider):
    """
    调用 SD WebUI /sdapi/v1/txt2img 接口。
    API 文档：http://localhost:7860/docs（WebUI 启动后访问）
    """

    def __init__(self, base_url: str, model: str = "",
                 username: str = "", password: str = ""):
        self._base_url  = base_url.rstrip("/")
        self._model     = model or "stable-diffusion-xl"
        self._auth      = (username, password) if username else None

    @property
    def provider_name(self) -> str:
        return "sd_webui"

    @property
    def current_model(self) -> str:
        return self._model

    def is_available(self) -> bool:
        """通过 GET /sdapi/v1/sd-models 快速探活（同步，仅初始化时调用）"""
        try:
            import httpx as _httpx
            r = _httpx.get(f"{self._base_url}/sdapi/v1/sd-models", timeout=5,
                           auth=self._auth)
            return r.status_code == 200
        except Exception:
            return False

    async def generate(self, req: ImageRequest) -> ImageResult:
        # 将 WxH 格式解析为整数
        try:
            w, h = (int(x) for x in req.size.split("x"))
        except ValueError:
            w, h = 1024, 1024

        payload = {
            "prompt":          f"{req.prompt}, {req.style}" if req.style else req.prompt,
            "negative_prompt": req.negative_prompt or "",
            "steps":           req.steps or 20,
            "cfg_scale":       req.guidance_scale or 7.5,
            "width":           w,
            "height":          h,
            "sampler_name":    "DPM++ 2M Karras",
            "batch_size":      1,
        }
        payload.update(req.extra)

        logger.info(f"[SDWebUI] 生成请求 size={req.size} "
                    f"prompt={payload['prompt'][:60]}")
        try:
            async with httpx.AsyncClient(
                timeout=300,  # 本地出图可能较慢
                auth=self._auth,
            ) as client:
                resp = await client.post(
                    f"{self._base_url}/sdapi/v1/txt2img",
                    json=payload,
                )
                resp.raise_for_status()
                data   = resp.json()
                images = data.get("images") or []
                if not images:
                    return self.unavailable_result("SD WebUI 返回空结果")

                # WebUI 返回 base64 编码的 PNG
                img_bytes = base64.b64decode(images[0])
                logger.info(f"[SDWebUI] 生成成功，图片大小: {len(img_bytes)} bytes")
                return ImageResult(
                    success=True,
                    image_bytes=img_bytes,
                    provider=self.provider_name,
                    model=self._model,
                )
        except httpx.ConnectError:
            return self.unavailable_result(
                f"无法连接 SD WebUI（{self._base_url}），请确认服务已启动并加了 --api 参数"
            )
        except httpx.TimeoutException:
            return self.unavailable_result("SD WebUI 出图超时（300s），请检查服务状态")
        except Exception as e:
            logger.error(f"[SDWebUI] 异常: {e}")
            return self.unavailable_result(str(e))
