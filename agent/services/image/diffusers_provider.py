"""diffusers 直接集成 Provider（骨架）

无需启动外部服务；通过 HuggingFace `diffusers` 库在 Python 进程内
直接加载 Stable Diffusion 模型并生成图片。

使用前提（TODO-IMG-3 完善）：
  pip install diffusers transformers accelerate torch
  # GPU 推荐：pip install torch --index-url https://download.pytorch.org/whl/cu121
  # macOS Apple Silicon：pip install torch torchvision

配置示例（.env）：
  IMAGE_GEN_PROVIDER=diffusers
  IMAGE_GEN_DIFFUSERS_MODEL=runwayml/stable-diffusion-v1-5
  IMAGE_GEN_DIFFUSERS_DEVICE=auto   # auto | cuda | cpu | mps

模型会在首次调用时自动下载到 HuggingFace 缓存目录（~/.cache/huggingface/）。

可替换的模型（IMAGE_GEN_DIFFUSERS_MODEL）：
  - runwayml/stable-diffusion-v1-5       （SD 1.5，约 4 GB，CPU 可运行）
  - stabilityai/stable-diffusion-xl-base-1.0  （SDXL，约 7 GB，需 GPU）
  - CompVis/stable-diffusion-v1-4        （SD 1.4，较轻量）
  - 本地路径，如 /models/my_sd_model     （通过 civitai 等下载的 .safetensors 需转换）
"""
from __future__ import annotations

import io
from typing import Optional, Any

from loguru import logger

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult


class DiffusersProvider(BaseImageProvider):
    """
    使用 HuggingFace diffusers 在进程内直接推理。

    pipeline 首次调用时懒加载，避免影响服务启动时间。
    TODO-IMG-3：支持模型热切换、LoRA 加载、SDXL Refiner、float16 精度优化。
    """

    def __init__(self, model_id: str, device: str = "auto"):
        self._model_id = model_id
        self._device   = self._resolve_device(device)
        self._pipeline: Optional[Any] = None   # 懒加载

    @property
    def provider_name(self) -> str:
        return "diffusers"

    @property
    def current_model(self) -> str:
        return self._model_id

    @staticmethod
    def _resolve_device(device: str) -> str:
        if device != "auto":
            return device
        try:
            import torch
            if torch.cuda.is_available():
                return "cuda"
            if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
                return "mps"
        except ImportError:
            pass
        return "cpu"

    def is_available(self) -> bool:
        """检查 diffusers + torch 是否已安装（不加载模型）。"""
        try:
            import diffusers   # noqa
            import torch       # noqa
            return True
        except ImportError:
            return False

    @staticmethod
    def _is_sdxl(model_id: str) -> bool:
        """根据模型 ID 判断是否为 SDXL 系列。"""
        xl_keywords = ("xl", "sdxl", "stable-diffusion-xl")
        lower = model_id.lower()
        return any(k in lower for k in xl_keywords)

    def _load_pipeline(self) -> Any:
        """懒加载 Pipeline；自动识别 SDXL / SD1.x，应用 float16 + attention slicing。"""
        if self._pipeline is not None:
            return self._pipeline

        logger.info(f"[Diffusers] 加载模型 {self._model_id} → device={self._device}（首次加载可能需数分钟）")
        try:
            import torch
            from diffusers import DPMSolverMultistepScheduler

            dtype = torch.float16 if self._device in ("cuda", "mps") else torch.float32
            use_fp16 = dtype == torch.float16

            if self._is_sdxl(self._model_id):
                from diffusers import StableDiffusionXLPipeline
                pipe = StableDiffusionXLPipeline.from_pretrained(
                    self._model_id,
                    torch_dtype=dtype,
                    use_safetensors=True,
                    variant="fp16" if use_fp16 else None,
                )
                logger.info("[Diffusers] 检测到 SDXL 模型，使用 StableDiffusionXLPipeline")
            else:
                from diffusers import StableDiffusionPipeline
                pipe = StableDiffusionPipeline.from_pretrained(
                    self._model_id,
                    torch_dtype=dtype,
                    safety_checker=None,
                )

            pipe.scheduler = DPMSolverMultistepScheduler.from_config(pipe.scheduler.config)
            pipe = pipe.to(self._device)

            # 内存优化
            pipe.enable_attention_slicing()
            logger.info("[Diffusers] attention slicing 已启用")

            if self._device == "cuda":
                try:
                    pipe.enable_xformers_memory_efficient_attention()
                    logger.info("[Diffusers] xformers 内存优化已启用")
                except Exception:
                    logger.debug("[Diffusers] xformers 不可用（pip install xformers 可进一步提速）")

            self._pipeline = pipe
            logger.info(f"[Diffusers] 模型加载完成: {self._model_id}")
            return pipe
        except ImportError as e:
            raise RuntimeError(
                f"diffusers/torch 未安装: {e}\n"
                "请执行: pip install diffusers transformers accelerate torch"
            ) from e

    async def generate(self, req: ImageRequest) -> ImageResult:
        """在线程池中运行同步推理（避免阻塞 asyncio 事件循环）。"""
        import asyncio
        try:
            image_bytes = await asyncio.get_event_loop().run_in_executor(
                None, self._generate_sync, req
            )
            return ImageResult(
                success=True,
                image_bytes=image_bytes,
                provider=self.provider_name,
                model=self._model_id,
            )
        except RuntimeError as e:
            return self.unavailable_result(str(e))
        except Exception as e:
            logger.error(f"[Diffusers] 生成失败: {e}")
            return self.unavailable_result(str(e))

    def _generate_sync(self, req: ImageRequest) -> bytes:
        """同步推理逻辑（在 executor 线程中运行）。"""
        pipe = self._load_pipeline()

        try:
            w, h = (int(x) for x in req.size.split("x"))
        except ValueError:
            w, h = 512, 512

        full_prompt = f"{req.prompt}, {req.style}" if req.style else req.prompt

        logger.info(f"[Diffusers] 开始推理 size={req.size} steps={req.steps} device={self._device}")
        result = pipe(
            prompt          = full_prompt,
            negative_prompt = req.negative_prompt or "",
            width           = w,
            height          = h,
            num_inference_steps = req.steps or 20,
            guidance_scale  = req.guidance_scale or 7.5,
        )
        image = result.images[0]

        buf = io.BytesIO()
        image.save(buf, format="PNG")
        logger.info(f"[Diffusers] 推理完成，图片 {len(buf.getvalue())} bytes")
        return buf.getvalue()
