"""diffusers 直接集成 Provider

无需启动外部服务；通过 HuggingFace diffusers 在 Python 进程内直接推理。

配置（.env）：
  IMAGE_GEN_PROVIDER=diffusers
  IMAGE_GEN_DIFFUSERS_MODEL=runwayml/stable-diffusion-v1-5
  IMAGE_GEN_DIFFUSERS_DEVICE=auto   # auto | cuda | cpu | mps

本机（GTX 1660 SUPER 6GB）推荐：SD 1.5 系列，512×512 约 10-20 秒/张。
"""
from __future__ import annotations

import io
import threading
from typing import Any, Dict, Optional, Tuple

from loguru import logger

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult

# ── 模块级状态（跨请求共享，单用户场景安全）─────────────────────────────────
# key: "{model_id}:{device}"，value: pipeline 对象
_pipeline_cache: Dict[str, Any] = {}
_cache_lock = threading.Lock()

# 进度状态：由生成回调写入，由 get_progress() 读取；_progress_lock 保护并发读写
_progress_state: Dict[str, Any] = {
    "progress": 0.0,
    "step": 0,
    "max": 0,
    "model_id": None,
}
_progress_lock = threading.Lock()


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


def _is_sdxl(model_id: str) -> bool:
    xl_kw = ("xl", "sdxl", "stable-diffusion-xl")
    return any(k in model_id.lower() for k in xl_kw)


def _pick_dtype(device: str):
    """返回适合该设备的 torch dtype。"""
    import torch
    return torch.float16 if device in ("cuda", "mps") else torch.float32


def _build_base_pipeline(model_id: str, dtype) -> Any:
    """从 HuggingFace 加载裸 pipeline（未移至设备）。"""
    from diffusers import DPMSolverMultistepScheduler

    if _is_sdxl(model_id):
        from diffusers import StableDiffusionXLPipeline
        import torch
        pipe = StableDiffusionXLPipeline.from_pretrained(
            model_id, torch_dtype=dtype,
            use_safetensors=True,
            variant="fp16" if dtype == torch.float16 else None,
        )
        logger.info("[Diffusers] SDXL 模型，使用 StableDiffusionXLPipeline")
    else:
        from diffusers import StableDiffusionPipeline
        pipe = StableDiffusionPipeline.from_pretrained(
            model_id, torch_dtype=dtype, safety_checker=None,
        )

    pipe.scheduler = DPMSolverMultistepScheduler.from_config(pipe.scheduler.config)
    return pipe


def _apply_memory_opts(pipe: Any, device: str) -> Any:
    """移至设备并启用显存优化选项。"""
    pipe = pipe.to(device)
    pipe.enable_attention_slicing()
    logger.info("[Diffusers] attention slicing 已启用")

    if device == "cuda":
        try:
            pipe.enable_xformers_memory_efficient_attention()
            logger.info("[Diffusers] xformers 内存优化已启用")
        except Exception as e:
            logger.debug("[Diffusers] xformers 不可用（pip install xformers 可进一步提速）", exc_info=True)

    return pipe


def _load_pipeline(model_id: str, device: str) -> Any:
    """加载并缓存 pipeline（线程安全）。"""
    cache_key = f"{model_id}:{device}"
    with _cache_lock:
        if cache_key in _pipeline_cache:
            return _pipeline_cache[cache_key]

    logger.info(f"[Diffusers] 加载模型 {model_id} → {device}（首次加载可能需数分钟）")
    try:
        dtype = _pick_dtype(device)
        pipe  = _build_base_pipeline(model_id, dtype)
        pipe  = _apply_memory_opts(pipe, device)

        with _cache_lock:
            _pipeline_cache[cache_key] = pipe
        logger.info(f"[Diffusers] 模型加载完成: {model_id}")
        return pipe

    except ImportError as e:
        raise RuntimeError(
            f"diffusers/torch 未安装: {e}\n"
            "请执行: pip install diffusers transformers accelerate torch"
        ) from e
    except Exception as e:
        logger.error(f"[Diffusers] 模型加载失败 ({model_id}): {e}", exc_info=True)
        raise


class DiffusersProvider(BaseImageProvider):
    """使用 HuggingFace diffusers 在进程内直接推理。

    特性：
    - pipeline 懒加载 + 模块级缓存（模型热切换无需重启服务）
    - callback_on_step_end 实时更新进度状态
    - float16 精度 + attention slicing + xformers 可选
    """

    def __init__(self, model_id: str, device: str = "auto"):
        self._model_id = model_id
        self._device   = _resolve_device(device)

    @property
    def provider_name(self) -> str:
        return "diffusers"

    @property
    def current_model(self) -> str:
        return self._model_id

    def is_available(self) -> bool:
        try:
            import diffusers  # noqa
            import torch      # noqa
            return True
        except ImportError:
            return False

    def get_progress(self) -> dict:
        """返回当前生成进度（由 step callback 更新）。"""
        with _progress_lock:
            return {
                "progress":  _progress_state.get("progress", 0.0),
                "step":      _progress_state.get("step", 0),
                "max":       _progress_state.get("max", 0),
                "model_id":  _progress_state.get("model_id"),
            }

    def switch_model(self, new_model_id: str) -> Tuple[bool, str]:
        """清除旧模型缓存，下次生成时自动加载新模型。"""
        old_key = f"{self._model_id}:{self._device}"
        with _cache_lock:
            if old_key in _pipeline_cache:
                del _pipeline_cache[old_key]
                try:
                    import torch
                    if self._device == "cuda":
                        torch.cuda.empty_cache()
                except Exception as e:
                    logger.error(f"[Diffusers] 清除 GPU 缓存失败: {e}", exc_info=True)
                logger.info(f"[Diffusers] 模型缓存已清除: {self._model_id}")
        self._model_id = new_model_id
        return True, f"已切换到 {new_model_id}（下次生成时加载）"

    async def generate(self, req: ImageRequest) -> ImageResult:
        import asyncio
        with _progress_lock:
            _progress_state.update({"progress": 0.0, "step": 0, "max": 0, "model_id": self._model_id})
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
            logger.error(f"[Diffusers] 生成失败: {e}", exc_info=True)
            return self.unavailable_result(str(e))

    def _generate_sync(self, req: ImageRequest) -> bytes:
        """同步推理；在 executor 线程中运行。"""
        pipe = _load_pipeline(self._model_id, self._device)

        try:
            w, h = (int(x) for x in req.size.split("x"))
        except ValueError:
            w, h = 512, 512

        full_prompt   = f"{req.prompt}, {req.style}" if req.style else req.prompt
        total_steps   = req.steps or 20
        with _progress_lock:
            _progress_state["max"] = total_steps

        def _step_callback(pipeline, step: int, timestep: int, kwargs: dict) -> dict:
            with _progress_lock:
                _progress_state["step"]     = step + 1
                _progress_state["progress"] = (step + 1) / total_steps
            return kwargs

        logger.info(f"[Diffusers] 开始推理 size={req.size} steps={total_steps} device={self._device}")

        # img2img 支持（需 PIL）
        if req.init_image_base64:
            return self._generate_img2img(pipe, req, full_prompt, total_steps, _step_callback, w, h)

        result = pipe(
            prompt              = full_prompt,
            negative_prompt     = req.negative_prompt or "",
            width               = w,
            height              = h,
            num_inference_steps = total_steps,
            guidance_scale      = req.guidance_scale or 7.5,
            callback_on_step_end = _step_callback,
        )
        image = result.images[0]
        buf = io.BytesIO()
        image.save(buf, format="PNG")
        logger.info(f"[Diffusers] 推理完成，{len(buf.getvalue())} bytes")
        with _progress_lock:
            _progress_state["progress"] = 1.0
        return buf.getvalue()

    def _generate_img2img(self, pipe, req: ImageRequest, prompt: str,
                          total_steps: int, callback, w: int, h: int) -> bytes:
        """img2img：使用 StableDiffusionImg2ImgPipeline（按需实例化）。"""
        import base64
        from PIL import Image as PILImage

        # 解码底图
        img_bytes = base64.b64decode(req.init_image_base64)
        init_image = PILImage.open(io.BytesIO(img_bytes)).convert("RGB").resize((w, h))

        try:
            from diffusers import StableDiffusionImg2ImgPipeline
            import torch
            dtype = torch.float16 if self._device in ("cuda", "mps") else torch.float32
            img2img_pipe = StableDiffusionImg2ImgPipeline(
                vae=pipe.vae, text_encoder=pipe.text_encoder, tokenizer=pipe.tokenizer,
                unet=pipe.unet, scheduler=pipe.scheduler, safety_checker=None,
                feature_extractor=None, requires_safety_checker=False,
            ).to(self._device)
        except Exception as e:
            logger.warning(f"[Diffusers] img2img pipeline 构建失败，降级 txt2img: {e}")
            # 降级：直接 txt2img 忽略底图
            result = pipe(
                prompt=prompt, negative_prompt=req.negative_prompt or "",
                width=w, height=h, num_inference_steps=total_steps,
                guidance_scale=req.guidance_scale or 7.5,
                callback_on_step_end=callback,
            )
            buf = io.BytesIO()
            result.images[0].save(buf, format="PNG")
            with _progress_lock:
                _progress_state["progress"] = 1.0
            return buf.getvalue()

        result = img2img_pipe(
            prompt          = prompt,
            negative_prompt = req.negative_prompt or "",
            image           = init_image,
            strength        = req.denoising_strength,
            num_inference_steps = total_steps,
            guidance_scale  = req.guidance_scale or 7.5,
            callback_on_step_end = callback,
        )
        buf = io.BytesIO()
        result.images[0].save(buf, format="PNG")
        with _progress_lock:
            _progress_state["progress"] = 1.0
        return buf.getvalue()
