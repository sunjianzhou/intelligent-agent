"""ComfyUI 图片生成 Provider

支持：txt2img / img2img / 采样器选择 / WebSocket 实时进度

启动 ComfyUI：
    python main.py --listen 0.0.0.0 --port 8188
.env 配置：
    IMAGE_GEN_PROVIDER=comfyui
    IMAGE_GEN_BASE_URL=http://localhost:8188
"""
from __future__ import annotations

import asyncio
import base64
import copy
import json
import threading
import uuid
from pathlib import Path
from typing import Any, Dict, Optional

import httpx
from loguru import logger

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult

# ── 模块级稳定 client_id（单用户场景，保证 WS 进度可被跨请求读取）────────────
_CLIENT_ID = uuid.uuid4().hex

# 进度状态（由 WS 监听线程写入，由 get_progress() 读取）
_progress_state: Dict[str, Any] = {"progress": 0.0, "step": 0, "max": 0, "prompt_id": None}
_progress_lock = threading.Lock()  # 保护多用户并发写入 _progress_state

# 轮询超时 / 间隔
_POLL_TIMEOUT_S = 300
_POLL_INTERVAL_S = 2

# ── 内置 txt2img 工作流（SD1.5 / SDXL 通用节点 ID 惯例）─────────────────────
_TXT2IMG_WORKFLOW: Dict[str, Any] = {
    "3": {
        "class_type": "KSampler",
        "inputs": {
            "cfg": 7.0,
            "denoise": 1.0,
            "latent_image": ["5", 0],
            "model":        ["4", 0],
            "negative":     ["7", 0],
            "positive":     ["6", 0],
            "sampler_name": "euler",
            "scheduler":    "karras",
            "seed":         0,
            "steps":        20,
        },
    },
    "4": {
        "class_type": "CheckpointLoaderSimple",
        "inputs": {"ckpt_name": "v1-5-pruned-emaonly.safetensors"},
    },
    "5": {
        "class_type": "EmptyLatentImage",
        "inputs": {"batch_size": 1, "height": 512, "width": 512},
    },
    "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 1], "text": ""}},
    "7": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 1], "text": ""}},
    "8": {"class_type": "VAEDecode",     "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
    "9": {"class_type": "SaveImage",     "inputs": {"filename_prefix": "ia_gen", "images": ["8", 0]}},
}

# ── 内置 img2img 工作流（LoadImage → VAEEncode → KSampler）──────────────────
_IMG2IMG_WORKFLOW: Dict[str, Any] = {
    "1": {
        "class_type": "LoadImage",
        "inputs": {"image": "__UPLOAD_FILENAME__", "upload": "image"},
    },
    "2": {
        "class_type": "VAEEncode",
        "inputs": {"pixels": ["1", 0], "vae": ["4", 2]},
    },
    "3": {
        "class_type": "KSampler",
        "inputs": {
            "cfg": 7.0,
            "denoise": 0.75,      # 由 req.denoising_strength 覆盖
            "latent_image": ["2", 0],
            "model":        ["4", 0],
            "negative":     ["7", 0],
            "positive":     ["6", 0],
            "sampler_name": "euler",
            "scheduler":    "karras",
            "seed":         0,
            "steps":        20,
        },
    },
    "4": {
        "class_type": "CheckpointLoaderSimple",
        "inputs": {"ckpt_name": "v1-5-pruned-emaonly.safetensors"},
    },
    "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 1], "text": ""}},
    "7": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 1], "text": ""}},
    "8": {"class_type": "VAEDecode",     "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
    "9": {"class_type": "SaveImage",     "inputs": {"filename_prefix": "ia_img2img", "images": ["8", 0]}},
}

# SD WebUI → ComfyUI 采样器名称映射（前端可能传来 SD WebUI 名称）
_SAMPLER_MAP: Dict[str, str] = {
    "DPM++ 2M Karras":       "dpmpp_2m",
    "DPM++ SDE Karras":      "dpmpp_sde",
    "DPM++ 2S a Karras":     "dpmpp_2s_ancestral",
    "Euler a":               "euler_ancestral",
    "Euler":                 "euler",
    "Heun":                  "heun",
    "DDIM":                  "ddim",
    "UniPC":                 "uni_pc",
    "LMS":                   "lms",
}


def _normalize_sampler(name: str) -> str:
    """将 SD WebUI / 用户输入的采样器名称统一为 ComfyUI 格式。"""
    return _SAMPLER_MAP.get(name, name.lower().replace(" ", "_").replace("+", "p"))


class ComfyUIProvider(BaseImageProvider):
    """ComfyUI 图片生成 Provider（WebSocket 进度 + HTTP 轮询降级）。"""

    def __init__(self, base_url: str, workflow_path: str = "", model: str = ""):
        self._base_url  = base_url.rstrip("/")
        self._model     = model
        self._workflow  = self._load_workflow(workflow_path)
        self._client_id = _CLIENT_ID   # 全局稳定，保证进度状态跨请求可读

    @property
    def provider_name(self) -> str:
        return "comfyui"

    @property
    def current_model(self) -> str:
        return self._model or "(ComfyUI 当前加载模型)"

    def is_available(self) -> bool:
        try:
            r = httpx.get(f"{self._base_url}/system_stats", timeout=5)
            return r.status_code == 200
        except Exception:
            return False

    async def list_models(self) -> list:
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                r = await client.get(f"{self._base_url}/object_info/CheckpointLoaderSimple")
                r.raise_for_status()
                data   = r.json()
                inputs = data.get("CheckpointLoaderSimple", {}).get("input", {}).get("required", {})
                names  = inputs.get("ckpt_name", [[], {}])[0]
                return [{"name": n} for n in names]
        except Exception as e:
            logger.warning(f"[ComfyUI] 获取模型列表失败: {e}")
            return []

    def get_progress(self) -> dict:
        """返回当前生成进度（由 WS 监听写入）。"""
        with _progress_lock:
            return {
                "progress":   _progress_state.get("progress", 0.0),
                "step":       _progress_state.get("step", 0),
                "max":        _progress_state.get("max", 0),
                "prompt_id":  _progress_state.get("prompt_id"),
            }

    @staticmethod
    def _load_workflow(path: str) -> Dict[str, Any]:
        if path and Path(path).exists():
            try:
                return json.loads(Path(path).read_text(encoding="utf-8"))
            except Exception as e:
                logger.warning(f"[ComfyUI] 加载自定义工作流失败: {e}，使用内置模板")
        return {}   # 空 = 根据请求类型动态选择内置模板

    def _build_workflow(self, req: ImageRequest, upload_filename: str = "") -> Dict[str, Any]:
        """将 ImageRequest 注入工作流模板，返回可提交副本。"""
        # 选择模板
        if self._workflow:
            base = self._workflow           # 用户自定义工作流
        elif upload_filename:
            base = _IMG2IMG_WORKFLOW        # img2img
        else:
            base = _TXT2IMG_WORKFLOW        # txt2img

        wf = copy.deepcopy(base)

        try:
            w, h = (int(x) for x in req.size.split("x"))
        except ValueError:
            w, h = 512, 512

        full_prompt = f"{req.prompt}, {req.style}" if req.style else req.prompt
        sampler     = _normalize_sampler(req.sampler_name or "euler")
        seed        = int(uuid.uuid4().int % (2**32))

        # KSampler 参数注入（节点 "3"）
        if "3" in wf:
            inp = wf["3"]["inputs"]
            inp["steps"]       = req.steps or 20
            inp["cfg"]         = req.guidance_scale or 7.0
            inp["seed"]        = seed
            inp["sampler_name"] = sampler
            if upload_filename:
                inp["denoise"] = req.denoising_strength

        # EmptyLatentImage 尺寸（节点 "5"，仅 txt2img）
        if "5" in wf:
            wf["5"]["inputs"]["width"]  = w
            wf["5"]["inputs"]["height"] = h

        # CLIP 文本（节点 "6" 正向，"7" 负向）
        if "6" in wf:
            wf["6"]["inputs"]["text"] = full_prompt
        if "7" in wf:
            wf["7"]["inputs"]["text"] = req.negative_prompt or ""

        # Checkpoint 模型（节点 "4"）
        if "4" in wf and self._model:
            wf["4"]["inputs"]["ckpt_name"] = self._model

        # LoadImage 节点（节点 "1"，img2img）
        if upload_filename and "1" in wf:
            wf["1"]["inputs"]["image"] = upload_filename

        return wf

    async def _upload_image(self, b64: str) -> Optional[str]:
        """将 base64 图片上传到 ComfyUI /upload/image，返回服务器文件名。"""
        try:
            img_bytes = base64.b64decode(b64)
            filename  = f"ia_input_{uuid.uuid4().hex[:8]}.png"
            async with httpx.AsyncClient(timeout=30) as client:
                r = await client.post(
                    f"{self._base_url}/upload/image",
                    files={"image": (filename, img_bytes, "image/png")},
                    data={"type": "input", "overwrite": "true"},
                )
                r.raise_for_status()
                data = r.json()
                return data.get("name") or filename
        except Exception as e:
            logger.warning(f"[ComfyUI] 上传图片失败: {e}")
            return None

    async def generate(self, req: ImageRequest) -> ImageResult:
        """提交工作流 → WebSocket/轮询等待完成 → 下载图片。"""
        # img2img：先上传底图
        upload_filename = ""
        if req.init_image_base64:
            upload_filename = await self._upload_image(req.init_image_base64) or ""
            if not upload_filename:
                return self.unavailable_result("img2img 底图上传失败")

        workflow = self._build_workflow(req, upload_filename)
        payload  = {"prompt": workflow, "client_id": self._client_id}

        logger.info(f"[ComfyUI] 提交 {'img2img' if upload_filename else 'txt2img'} "
                    f"size={req.size} sampler={req.sampler_name} prompt={req.prompt[:60]}")

        # 重置进度
        with _progress_lock:
            _progress_state.update({"progress": 0.0, "step": 0, "max": 0, "prompt_id": None})

        async with httpx.AsyncClient(timeout=30) as client:
            try:
                resp = await client.post(f"{self._base_url}/prompt", json=payload)
                resp.raise_for_status()
                prompt_id = resp.json().get("prompt_id")
                if not prompt_id:
                    return self.unavailable_result("ComfyUI 未返回 prompt_id")
            except httpx.ConnectError:
                return self.unavailable_result(
                    f"无法连接 ComfyUI（{self._base_url}），请确认服务已启动：\n"
                    "  python main.py --listen 0.0.0.0 --port 8188"
                )
            except Exception as e:
                return self.unavailable_result(f"提交工作流失败: {e}")

        with _progress_lock:
            _progress_state["prompt_id"] = prompt_id
        logger.info(f"[ComfyUI] 已入队 prompt_id={prompt_id}")

        image_bytes = await self._wait_and_download(prompt_id)
        if image_bytes is None:
            return self.unavailable_result("ComfyUI 生成超时或输出为空")

        logger.info(f"[ComfyUI] 生成成功，图片大小: {len(image_bytes)} bytes")
        with _progress_lock:
            _progress_state.update({"progress": 1.0, "step": 0, "max": 0})
        return ImageResult(
            success=True,
            image_bytes=image_bytes,
            provider=self.provider_name,
            model=self.current_model,
        )

    async def _wait_and_download(self, prompt_id: str) -> Optional[bytes]:
        try:
            import websockets  # noqa
            return await self._ws_wait_and_download(prompt_id)
        except ImportError:
            logger.debug("[ComfyUI] websockets 未安装，使用 HTTP 轮询（pip install websockets 可升级）")
            return await self._poll_and_download(prompt_id)

    async def _ws_wait_and_download(self, prompt_id: str) -> Optional[bytes]:
        ws_url   = self._base_url.replace("http://", "ws://").replace("https://", "wss://")
        ws_url   = f"{ws_url}/ws?client_id={self._client_id}"
        deadline = asyncio.get_event_loop().time() + _POLL_TIMEOUT_S

        try:
            import websockets
            async with websockets.connect(ws_url, ping_interval=20) as ws:
                while asyncio.get_event_loop().time() < deadline:
                    try:
                        raw  = await asyncio.wait_for(ws.recv(), timeout=5)
                        msg  = json.loads(raw) if isinstance(raw, str) else {}
                        mtype = msg.get("type", "")
                        data  = msg.get("data", {})

                        if mtype == "progress":
                            val = data.get("value", 0)
                            mx  = data.get("max", 1)
                            with _progress_lock:
                                _progress_state.update({
                                    "progress": val / mx if mx else 0.0,
                                    "step": val, "max": mx,
                                })
                            logger.debug(f"[ComfyUI WS] 进度 {val}/{mx}")

                        elif mtype == "executing" and data.get("node") is None:
                            if data.get("prompt_id") == prompt_id:
                                logger.info(f"[ComfyUI WS] 完成 prompt_id={prompt_id}")
                                return await self._download_output(prompt_id)

                    except asyncio.TimeoutError:
                        # 新版 ComfyUI 的 "execution_success" 不通过 WS 广播（broadcast=False），
                        # 仅靠 "executing node=None" 判断完成在该版本下永不触发；
                        # 每次心跳超时顺带探测一次 /history 作为兜底，避免误判超时失败。
                        result = await self._download_output(prompt_id)
                        if result is not None:
                            logger.info(f"[ComfyUI WS] 通过 /history 兜底确认完成 prompt_id={prompt_id}")
                            return result
                        continue
        except Exception as e:
            logger.warning(f"[ComfyUI WS] 连接异常: {e}，降级轮询")
            return await self._poll_and_download(prompt_id)

        logger.error(f"[ComfyUI WS] prompt_id={prompt_id} 超时 ({_POLL_TIMEOUT_S}s)")
        return None

    async def _poll_and_download(self, prompt_id: str) -> Optional[bytes]:
        deadline = asyncio.get_event_loop().time() + _POLL_TIMEOUT_S
        elapsed  = 0.0

        async with httpx.AsyncClient(timeout=30) as client:
            while asyncio.get_event_loop().time() < deadline:
                await asyncio.sleep(_POLL_INTERVAL_S)
                elapsed += _POLL_INTERVAL_S
                # 估算进度（无 WS 时按时间粗略估算，最多 80%）
                with _progress_lock:
                    _progress_state["progress"] = min(0.8, elapsed / 60)
                try:
                    r = await client.get(f"{self._base_url}/history/{prompt_id}")
                    if r.status_code == 200 and prompt_id in r.json():
                        return await self._download_output(prompt_id)
                except Exception as e:
                    logger.warning(f"[ComfyUI] 轮询异常: {e}")

        logger.error(f"[ComfyUI] prompt_id={prompt_id} 轮询超时 ({_POLL_TIMEOUT_S}s)")
        return None

    async def _download_output(self, prompt_id: str) -> Optional[bytes]:
        async with httpx.AsyncClient(timeout=30) as client:
            r = await client.get(f"{self._base_url}/history/{prompt_id}")
            if r.status_code != 200:
                return None
            history = r.json()
            outputs = history.get(prompt_id, {}).get("outputs", {})
            for node_output in outputs.values():
                for img_info in node_output.get("images", []):
                    fname     = img_info.get("filename", "")
                    subfolder = img_info.get("subfolder", "")
                    img_type  = img_info.get("type", "output")
                    dl = await client.get(
                        f"{self._base_url}/view",
                        params={"filename": fname, "subfolder": subfolder, "type": img_type},
                    )
                    if dl.status_code == 200:
                        return dl.content
        return None
