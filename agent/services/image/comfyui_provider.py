"""ComfyUI 图片生成 Provider（骨架）

ComfyUI 采用工作流 (workflow) 驱动，每次生成都提交一个 JSON 工作流到队列，
通过 WebSocket 监听进度，完成后下载图片。

接入步骤（TODO-IMG-2 实现后完善）：
1. 安装并启动 ComfyUI：
     git clone https://github.com/comfyanonymous/ComfyUI
     cd ComfyUI && pip install -r requirements.txt
     python main.py --listen 0.0.0.0 --port 8188
2. 在 .env 中配置：
     IMAGE_GEN_PROVIDER=comfyui
     IMAGE_GEN_BASE_URL=http://localhost:8188
3. （可选）自定义工作流：
     IMAGE_GEN_COMFYUI_WORKFLOW=/path/to/workflow.json

关键 API：
  POST /prompt        — 提交工作流到队列，返回 {prompt_id}
  GET  /history/{id}  — 查询指定 prompt_id 的完成状态和输出文件
  GET  /view          — 下载生成的图片（?filename=x&subfolder=y&type=output）
  WS   /ws?client_id= — 实时进度推送（TODO-IMG-2 中实现）

注意：ComfyUI 工作流节点 ID 可能因安装的自定义节点不同而变化，
内置 SDXL / SD1.5 工作流需分别维护。
"""
from __future__ import annotations

import asyncio
import json
import uuid
from pathlib import Path
from typing import Any, Dict, Optional

import httpx
from loguru import logger

from services.image.base_image_provider import BaseImageProvider, ImageRequest, ImageResult

# 内置的最简 SD1.5 txt2img 工作流模板（节点 ID 与官方示例一致）
_DEFAULT_WORKFLOW: Dict[str, Any] = {
    "3": {
        "class_type": "KSampler",
        "inputs": {
            "cfg": 7.0,
            "denoise": 1.0,
            "latent_image": ["5", 0],
            "model": ["4", 0],
            "negative": ["7", 0],
            "positive": ["6", 0],
            "sampler_name": "euler",
            "scheduler": "normal",
            "seed": 0,        # 将在运行时替换为随机值
            "steps": 20,      # 将在运行时替换为 req.steps
        },
    },
    "4": {
        "class_type": "CheckpointLoaderSimple",
        "inputs": {"ckpt_name": "v1-5-pruned-emaonly.ckpt"},
    },
    "5": {
        "class_type": "EmptyLatentImage",
        "inputs": {"batch_size": 1, "height": 512, "width": 512},
    },
    "6": {
        "class_type": "CLIPTextEncode",
        "inputs": {"clip": ["4", 1], "text": ""},   # prompt 在运行时填入
    },
    "7": {
        "class_type": "CLIPTextEncode",
        "inputs": {"clip": ["4", 1], "text": ""},   # negative_prompt 在运行时填入
    },
    "8": {
        "class_type": "VAEDecode",
        "inputs": {"samples": ["3", 0], "vae": ["4", 2]},
    },
    "9": {
        "class_type": "SaveImage",
        "inputs": {"filename_prefix": "ia_gen", "images": ["8", 0]},
    },
}

# ComfyUI 默认轮询超时 / 间隔
_POLL_TIMEOUT_S = 300
_POLL_INTERVAL_S = 2


class ComfyUIProvider(BaseImageProvider):
    """
    通过 ComfyUI REST API 生成图片。

    当前实现为 *轮询模式*（提交 → 轮询 /history/{id} → 下载）。
    TODO-IMG-2 会将其升级为 WebSocket 实时进度模式。
    """

    def __init__(self, base_url: str, workflow_path: str = "", model: str = ""):
        self._base_url = base_url.rstrip("/")
        self._model    = model
        self._workflow = self._load_workflow(workflow_path)
        self._client_id = uuid.uuid4().hex

    @property
    def provider_name(self) -> str:
        return "comfyui"

    @property
    def current_model(self) -> str:
        return self._model or "(ComfyUI 当前加载模型)"

    def is_available(self) -> bool:
        """GET /system_stats 探活"""
        try:
            r = httpx.get(f"{self._base_url}/system_stats", timeout=5)
            return r.status_code == 200
        except Exception:
            return False

    async def list_models(self) -> list:
        """通过 /object_info/CheckpointLoaderSimple 获取可用检查点列表。"""
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                r = await client.get(f"{self._base_url}/object_info/CheckpointLoaderSimple")
                r.raise_for_status()
                data   = r.json()
                inputs = data.get("CheckpointLoaderSimple", {}).get("input", {}).get("required", {})
                names  = inputs.get("ckpt_name", [[], {}])[0]  # [["model1.safetensors", ...], {...}]
                return [{"name": n} for n in names]
        except Exception as e:
            logger.warning(f"[ComfyUI] 获取模型列表失败: {e}")
            return []

    @staticmethod
    def _load_workflow(path: str) -> Dict[str, Any]:
        if path and Path(path).exists():
            try:
                return json.loads(Path(path).read_text(encoding="utf-8"))
            except Exception as e:
                logger.warning(f"[ComfyUI] 加载自定义工作流失败: {e}，使用内置模板")
        return _DEFAULT_WORKFLOW

    def _build_workflow(self, req: ImageRequest) -> Dict[str, Any]:
        """将 ImageRequest 参数注入工作流模板，返回可提交的副本。"""
        import copy
        wf = copy.deepcopy(self._workflow)

        try:
            w, h = (int(x) for x in req.size.split("x"))
        except ValueError:
            w, h = 512, 512

        full_prompt = f"{req.prompt}, {req.style}" if req.style else req.prompt

        # 注入到已知节点（内置模板专用；自定义工作流需对应修改）
        if "3" in wf:
            wf["3"]["inputs"]["steps"] = req.steps or 20
            wf["3"]["inputs"]["cfg"]   = req.guidance_scale or 7.0
            wf["3"]["inputs"]["seed"]  = int(uuid.uuid4().int % (2**32))
        if "5" in wf:
            wf["5"]["inputs"]["width"]  = w
            wf["5"]["inputs"]["height"] = h
        if "6" in wf:
            wf["6"]["inputs"]["text"] = full_prompt
        if "7" in wf:
            wf["7"]["inputs"]["text"] = req.negative_prompt or ""
        if "4" in wf and self._model:
            wf["4"]["inputs"]["ckpt_name"] = self._model

        return wf

    async def generate(self, req: ImageRequest) -> ImageResult:
        """提交工作流 → 轮询完成 → 下载图片。"""
        workflow = self._build_workflow(req)
        payload  = {"prompt": workflow, "client_id": self._client_id}

        logger.info(f"[ComfyUI] 提交生成请求 size={req.size} prompt={req.prompt[:60]}")

        async with httpx.AsyncClient(timeout=30) as client:
            # 1. 提交到队列
            try:
                resp = await client.post(f"{self._base_url}/prompt", json=payload)
                resp.raise_for_status()
                prompt_id = resp.json().get("prompt_id")
                if not prompt_id:
                    return self.unavailable_result("ComfyUI 未返回 prompt_id")
            except httpx.ConnectError:
                return self.unavailable_result(
                    f"无法连接 ComfyUI（{self._base_url}），请确认服务已启动"
                )
            except Exception as e:
                return self.unavailable_result(f"提交工作流失败: {e}")

            logger.info(f"[ComfyUI] 已入队 prompt_id={prompt_id}")

        # 2. 等待完成（WebSocket 优先，降级轮询）
        image_bytes = await self._wait_and_download(prompt_id)
        if image_bytes is None:
            return self.unavailable_result("ComfyUI 生成超时或输出为空")

        logger.info(f"[ComfyUI] 生成成功，图片大小: {len(image_bytes)} bytes")
        return ImageResult(
            success=True,
            image_bytes=image_bytes,
            provider=self.provider_name,
            model=self.current_model,
        )

    async def _wait_and_download(self, prompt_id: str) -> Optional[bytes]:
        """
        优先用 WebSocket 实时监听完成事件；若 websockets 未安装则降级为轮询。
        """
        try:
            import websockets  # noqa
            return await self._ws_wait_and_download(prompt_id)
        except ImportError:
            logger.debug("[ComfyUI] websockets 未安装，使用 HTTP 轮询模式（pip install websockets 可升级）")
            return await self._poll_and_download(prompt_id)

    async def _ws_wait_and_download(self, prompt_id: str) -> Optional[bytes]:
        """通过 WebSocket 监听进度事件，完成后下载图片。"""
        import json as _json
        ws_url = self._base_url.replace("http://", "ws://").replace("https://", "wss://")
        ws_url = f"{ws_url}/ws?client_id={self._client_id}"
        deadline = asyncio.get_event_loop().time() + _POLL_TIMEOUT_S

        try:
            import websockets
            async with websockets.connect(ws_url, ping_interval=20) as ws:
                while asyncio.get_event_loop().time() < deadline:
                    try:
                        raw = await asyncio.wait_for(ws.recv(), timeout=5)
                        msg = _json.loads(raw) if isinstance(raw, str) else {}
                        mtype = msg.get("type", "")
                        data  = msg.get("data", {})

                        if mtype == "progress":
                            val = data.get("value", 0)
                            mx  = data.get("max", 1)
                            logger.debug(f"[ComfyUI WS] 进度 {val}/{mx}")

                        elif mtype == "executing" and data.get("node") is None:
                            # 执行完成信号
                            if data.get("prompt_id") == prompt_id:
                                logger.info(f"[ComfyUI WS] prompt_id={prompt_id} 完成")
                                return await self._download_output(prompt_id)

                    except asyncio.TimeoutError:
                        continue  # 无消息时继续等待
        except Exception as e:
            logger.warning(f"[ComfyUI WS] 连接异常: {e}，降级轮询")
            return await self._poll_and_download(prompt_id)

        logger.error(f"[ComfyUI WS] prompt_id={prompt_id} 超时 ({_POLL_TIMEOUT_S}s)")
        return None

    async def _poll_and_download(self, prompt_id: str) -> Optional[bytes]:
        """HTTP 轮询历史记录（WebSocket 不可用时的降级方案）。"""
        deadline = asyncio.get_event_loop().time() + _POLL_TIMEOUT_S

        async with httpx.AsyncClient(timeout=30) as client:
            while asyncio.get_event_loop().time() < deadline:
                await asyncio.sleep(_POLL_INTERVAL_S)
                try:
                    r = await client.get(f"{self._base_url}/history/{prompt_id}")
                    if r.status_code == 200 and prompt_id in r.json():
                        return await self._download_output(prompt_id)
                except Exception as e:
                    logger.warning(f"[ComfyUI] 轮询异常: {e}")

        logger.error(f"[ComfyUI] prompt_id={prompt_id} 轮询超时 ({_POLL_TIMEOUT_S}s)")
        return None

    async def _download_output(self, prompt_id: str) -> Optional[bytes]:
        """从 /history/{prompt_id} 读取输出并下载第一张图片。"""
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
                        params={"filename": fname, "subfolder": subfolder, "type": img_type}
                    )
                    if dl.status_code == 200:
                        return dl.content
        return None
