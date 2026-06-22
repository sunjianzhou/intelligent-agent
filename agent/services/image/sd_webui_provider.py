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
    调用 SD WebUI /sdapi/v1/txt2img 或 /sdapi/v1/img2img 接口。
    API 文档：http://localhost:7860/docs（WebUI 启动后访问）
    """

    def __init__(self, base_url: str, model: str = "",
                 username: str = "", password: str = ""):
        self._base_url = base_url.rstrip("/")
        self._model    = model or "stable-diffusion-xl"
        self._auth     = (username, password) if username else None

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

    async def list_models(self) -> list:
        """从 SD WebUI 获取可用检查点列表（title / model_name）。"""
        try:
            async with httpx.AsyncClient(timeout=10, auth=self._auth) as client:
                r = await client.get(f"{self._base_url}/sdapi/v1/sd-models")
                r.raise_for_status()
                return [
                    {"name": m.get("title", ""), "filename": m.get("filename", ""), "hash": m.get("hash", "")}
                    for m in r.json()
                ]
        except Exception as e:
            logger.warning(f"[SDWebUI] 获取模型列表失败: {e}")
            return []

    async def switch_model(self, model_name: str) -> tuple:
        """通过 /sdapi/v1/options 热切换模型，返回 (success, message)。"""
        try:
            async with httpx.AsyncClient(timeout=120, auth=self._auth) as client:
                r = await client.post(
                    f"{self._base_url}/sdapi/v1/options",
                    json={"sd_model_checkpoint": model_name},
                )
                r.raise_for_status()
                logger.info(f"[SDWebUI] 模型已切换为: {model_name}")
                return True, f"模型已切换为 {model_name}"
        except httpx.ConnectError:
            return False, f"无法连接 SD WebUI（{self._base_url}）"
        except Exception as e:
            logger.error(f"[SDWebUI] 切换模型失败: {e}")
            return False, str(e)

    async def list_controlnet_modules(self) -> list:
        """从 SD WebUI ControlNet 扩展获取可用预处理器列表（如 canny/depth/openpose）。"""
        try:
            async with httpx.AsyncClient(timeout=10, auth=self._auth) as client:
                r = await client.get(f"{self._base_url}/controlnet/module_list")
                r.raise_for_status()
                return r.json().get("module_list", [])
        except Exception as e:
            logger.warning(f"[SDWebUI] 获取 ControlNet 预处理器列表失败: {e}")
            return []

    async def list_controlnet_models(self) -> list:
        """从 SD WebUI ControlNet 扩展获取可用模型列表。"""
        try:
            async with httpx.AsyncClient(timeout=10, auth=self._auth) as client:
                r = await client.get(f"{self._base_url}/controlnet/model_list")
                r.raise_for_status()
                return r.json().get("model_list", [])
        except Exception as e:
            logger.warning(f"[SDWebUI] 获取 ControlNet 模型列表失败: {e}")
            return []

    async def get_progress(self) -> dict:
        """查询当前生成进度（0.0~1.0）。SD WebUI 未在出图时返回 progress=0。"""
        try:
            async with httpx.AsyncClient(timeout=5, auth=self._auth) as client:
                r = await client.get(f"{self._base_url}/sdapi/v1/progress",
                                     params={"skip_current_image": "true"})
                r.raise_for_status()
                data = r.json()
                return {
                    "progress":   data.get("progress", 0.0),
                    "eta":        data.get("eta_relative", 0.0),
                    "state":      data.get("state", {}),
                    "job":        data.get("state", {}).get("job", ""),
                    "step":       data.get("state", {}).get("sampling_step", 0),
                    "total_step": data.get("state", {}).get("sampling_steps", 0),
                }
        except Exception as e:
            logger.debug(f"[SDWebUI] 进度查询失败: {e}")
            return {"progress": 0.0, "eta": 0.0, "state": {}, "job": "", "step": 0, "total_step": 0}

    async def generate(self, req: ImageRequest) -> ImageResult:
        try:
            w, h = (int(x) for x in req.size.split("x"))
        except ValueError:
            w, h = 1024, 1024

        sampler = req.sampler_name or "DPM++ 2M Karras"
        full_prompt = f"{req.prompt}, {req.style}" if req.style else req.prompt

        base_payload = {
            "prompt":          full_prompt,
            "negative_prompt": req.negative_prompt or "",
            "steps":           req.steps or 20,
            "cfg_scale":       req.guidance_scale or 7.5,
            "width":           w,
            "height":          h,
            "sampler_name":    sampler,
            "batch_size":      1,
        }
        base_payload.update(req.extra)

        # img2img 模式
        if req.init_image_base64:
            endpoint = f"{self._base_url}/sdapi/v1/img2img"
            payload  = {
                **base_payload,
                "init_images":        [req.init_image_base64],
                "denoising_strength": req.denoising_strength,
                "resize_mode":        0,
            }
            if req.controlnet_enabled:
                payload["alwayson_scripts"] = {
                    "controlnet": {
                        "args": [{
                            "input_image": req.init_image_base64,
                            "module":      req.controlnet_module,
                            "model":       req.controlnet_model,
                            "weight":      req.controlnet_weight,
                        }]
                    }
                }
                logger.info(f"[SDWebUI] ControlNet 已启用 module={req.controlnet_module} model={req.controlnet_model}")
            logger.info(f"[SDWebUI] img2img 请求 size={req.size} sampler={sampler}")
        else:
            endpoint = f"{self._base_url}/sdapi/v1/txt2img"
            payload  = base_payload
            logger.info(f"[SDWebUI] txt2img 请求 size={req.size} sampler={sampler} prompt={full_prompt[:60]}")

        try:
            async with httpx.AsyncClient(timeout=300, auth=self._auth) as client:
                resp = await client.post(endpoint, json=payload)
                resp.raise_for_status()
                data   = resp.json()
                images = data.get("images") or []
                if not images:
                    return self.unavailable_result("SD WebUI 返回空结果")

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
