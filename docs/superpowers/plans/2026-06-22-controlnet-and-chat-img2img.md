# ControlNet 支持 + 聊天工具 img2img/sampler 补全 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ControlNet support to the SD WebUI image provider, and let the chat-triggered image tool automatically use sampler selection and an attached chat image for img2img.

**Architecture:** Backend-first: extend `ImageRequest` with ControlNet fields, wire them through `SDWebUIProvider.generate()` and two new `image_router.py` endpoints, then surface in `ImageView.vue`. Separately, add a per-request `ContextVar` carrying the chat-attached image so `ImageGenerationTool` can pull it without the LLM ever handling image bytes.

**Tech Stack:** Python 3.10, FastAPI, httpx (async), pytest + pytest-asyncio, unittest.mock; Vue 3 `<script setup>`, Element Plus.

## Global Constraints

- ControlNet support is SD WebUI only — do not touch `comfyui_provider.py` or `diffusers_provider.py`.
- ControlNet reuses the existing img2img base-image upload — no new upload widget in `ImageView.vue`.
- `init_image_base64` must never be an LLM-callable parameter on `ImageGenerationTool` — it is read from `_request_image_b64_ctx` only.
- Follow existing test style in `agent/tests/`: plain `pytest`/`pytest.mark.asyncio` + `unittest.mock`, no FastAPI `TestClient` — call router/provider functions directly.
- All new Python dataclass fields get defaults so existing call sites (`ImageRequest(prompt=...)`) keep working unchanged.

---

### Task 1: `ImageRequest` ControlNet fields

**Files:**
- Modify: `agent/services/image/base_image_provider.py`
- Test: `agent/tests/test_image_controlnet.py` (new file, also used by Task 2/3)

**Interfaces:**
- Produces: `ImageRequest` dataclass now has `controlnet_enabled: bool = False`, `controlnet_module: str = "none"`, `controlnet_model: str = ""`, `controlnet_weight: float = 1.0` — every later task constructs `ImageRequest` using these exact names.

- [ ] **Step 1: Write the failing test**

Create `agent/tests/test_image_controlnet.py`:

```python
"""Tests for ControlNet support in ImageRequest / SDWebUIProvider / image_router."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from services.image.base_image_provider import ImageRequest


def test_image_request_controlnet_defaults():
    req = ImageRequest(prompt="a cat")
    assert req.controlnet_enabled is False
    assert req.controlnet_module == "none"
    assert req.controlnet_model == ""
    assert req.controlnet_weight == 1.0


def test_image_request_controlnet_explicit():
    req = ImageRequest(
        prompt="a cat",
        controlnet_enabled=True,
        controlnet_module="canny",
        controlnet_model="control_v11p_sd15_canny",
        controlnet_weight=0.8,
    )
    assert req.controlnet_enabled is True
    assert req.controlnet_module == "canny"
    assert req.controlnet_model == "control_v11p_sd15_canny"
    assert req.controlnet_weight == 0.8
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent && python -m pytest tests/test_image_controlnet.py -v`
Expected: FAIL — `TypeError: __init__() got an unexpected keyword argument 'controlnet_enabled'`

- [ ] **Step 3: Write minimal implementation**

In `agent/services/image/base_image_provider.py`, the `ImageRequest` dataclass currently ends with:

```python
    sampler_name: str = "DPM++ 2M Karras"   # 采样器（SD WebUI / ComfyUI 支持）
    init_image_base64: Optional[str] = None  # img2img 底图（纯 base64，不含前缀）
    denoising_strength: float = 0.75         # img2img 去噪强度 0~1
    extra: dict = field(default_factory=dict)
```

Add four fields right before `extra`:

```python
    sampler_name: str = "DPM++ 2M Karras"   # 采样器（SD WebUI / ComfyUI 支持）
    init_image_base64: Optional[str] = None  # img2img 底图（纯 base64，不含前缀）
    denoising_strength: float = 0.75         # img2img 去噪强度 0~1
    controlnet_enabled: bool = False         # 是否将 init_image_base64 同时用作 ControlNet 控制图（仅 SD WebUI）
    controlnet_module: str = "none"          # ControlNet 预处理器，如 canny/depth/openpose
    controlnet_model: str = ""               # ControlNet 模型名（来自 /controlnet/model_list）
    controlnet_weight: float = 1.0           # ControlNet 引导权重 0~2
    extra: dict = field(default_factory=dict)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent && python -m pytest tests/test_image_controlnet.py -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/services/image/base_image_provider.py agent/tests/test_image_controlnet.py
git commit -m "feat(image): add ControlNet fields to ImageRequest"
```

---

### Task 2: SD WebUI ControlNet methods + payload injection

**Files:**
- Modify: `agent/services/image/sd_webui_provider.py`
- Test: `agent/tests/test_image_controlnet.py` (append)

**Interfaces:**
- Consumes: `ImageRequest` fields from Task 1.
- Produces: `SDWebUIProvider.list_controlnet_modules() -> list[str]`, `SDWebUIProvider.list_controlnet_models() -> list[str]` (both async, return `[]` on any error) — Task 3's router endpoints call these by exact name.
- Produces: `SDWebUIProvider.generate()` now adds `payload["alwayson_scripts"]["controlnet"]["args"]` when `req.controlnet_enabled and req.init_image_base64` — no change to the method's existing return type (`ImageResult`).

- [ ] **Step 1: Write the failing tests**

Append to `agent/tests/test_image_controlnet.py`:

```python
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from services.image.sd_webui_provider import SDWebUIProvider


def _make_provider():
    return SDWebUIProvider(base_url="http://localhost:7860", model="sd15")


@pytest.mark.asyncio
async def test_list_controlnet_modules_success():
    provider = _make_provider()
    fake_resp = MagicMock()
    fake_resp.json.return_value = {"module_list": ["none", "canny", "depth"]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.get = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.list_controlnet_modules()

    assert result == ["none", "canny", "depth"]
    mock_client.get.assert_called_once_with("http://localhost:7860/controlnet/module_list")


@pytest.mark.asyncio
async def test_list_controlnet_modules_failure_returns_empty():
    provider = _make_provider()
    with patch("httpx.AsyncClient", side_effect=RuntimeError("boom")):
        result = await provider.list_controlnet_modules()
    assert result == []


@pytest.mark.asyncio
async def test_list_controlnet_models_success():
    provider = _make_provider()
    fake_resp = MagicMock()
    fake_resp.json.return_value = {"model_list": ["control_v11p_sd15_canny"]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.get = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.list_controlnet_models()

    assert result == ["control_v11p_sd15_canny"]
    mock_client.get.assert_called_once_with("http://localhost:7860/controlnet/model_list")


@pytest.mark.asyncio
async def test_generate_injects_controlnet_payload_when_enabled():
    from services.image.base_image_provider import ImageRequest
    import base64

    provider = _make_provider()
    req = ImageRequest(
        prompt="a cat",
        init_image_base64="ZmFrZWJhc2U2NA==",
        controlnet_enabled=True,
        controlnet_module="canny",
        controlnet_model="control_v11p_sd15_canny",
        controlnet_weight=0.8,
    )

    fake_resp = MagicMock()
    fake_resp.json.return_value = {"images": [base64.b64encode(b"img").decode()]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.generate(req)

    assert result.success is True
    sent_kwargs = mock_client.post.call_args
    sent_payload = sent_kwargs.kwargs.get("json") or sent_kwargs[1].get("json")
    assert sent_payload["alwayson_scripts"]["controlnet"]["args"][0] == {
        "input_image": "ZmFrZWJhc2U2NA==",
        "module": "canny",
        "model": "control_v11p_sd15_canny",
        "weight": 0.8,
    }


@pytest.mark.asyncio
async def test_generate_omits_controlnet_payload_when_disabled():
    from services.image.base_image_provider import ImageRequest
    import base64

    provider = _make_provider()
    req = ImageRequest(prompt="a cat", init_image_base64="ZmFrZWJhc2U2NA==")

    fake_resp = MagicMock()
    fake_resp.json.return_value = {"images": [base64.b64encode(b"img").decode()]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.generate(req)

    assert result.success is True
    sent_kwargs = mock_client.post.call_args
    sent_payload = sent_kwargs.kwargs.get("json") or sent_kwargs[1].get("json")
    assert "alwayson_scripts" not in sent_payload
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent && python -m pytest tests/test_image_controlnet.py -v`
Expected: FAIL on the new tests — `AttributeError: 'SDWebUIProvider' object has no attribute 'list_controlnet_modules'`

- [ ] **Step 3: Write minimal implementation**

In `agent/services/image/sd_webui_provider.py`, add two new methods right after `switch_model()` (after line 77, before `get_progress()`):

```python
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
```

Then, in `generate()`, the img2img branch currently reads:

```python
        # img2img 模式
        if req.init_image_base64:
            endpoint = f"{self._base_url}/sdapi/v1/img2img"
            payload  = {
                **base_payload,
                "init_images":        [req.init_image_base64],
                "denoising_strength": req.denoising_strength,
                "resize_mode":        0,
            }
            logger.info(f"[SDWebUI] img2img 请求 size={req.size} sampler={sampler}")
        else:
            endpoint = f"{self._base_url}/sdapi/v1/txt2img"
            payload  = base_payload
            logger.info(f"[SDWebUI] txt2img 请求 size={req.size} sampler={sampler} prompt={full_prompt[:60]}")
```

Replace it with (adds ControlNet injection after `payload` is built, only when `init_image_base64` is present):

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent && python -m pytest tests/test_image_controlnet.py -v`
Expected: PASS (7 tests total)

- [ ] **Step 5: Commit**

```bash
git add agent/services/image/sd_webui_provider.py agent/tests/test_image_controlnet.py
git commit -m "feat(image): SD WebUI ControlNet module/model listing + payload injection"
```

---

### Task 3: `image_router.py` ControlNet endpoints + generate passthrough

**Files:**
- Modify: `agent/api/image_router.py`
- Test: `agent/tests/test_image_controlnet.py` (append)

**Interfaces:**
- Consumes: `SDWebUIProvider.list_controlnet_modules()` / `list_controlnet_models()` from Task 2.
- Produces: `GET /api/image/controlnet/modules`, `GET /api/image/controlnet/models` route handlers `list_controlnet_modules()` / `list_controlnet_models()` in `image_router.py` (module-level async functions, same naming convention as existing `list_image_models`) — frontend Task 5 calls these by URL, not by Python name.

- [ ] **Step 1: Write the failing tests**

Append to `agent/tests/test_image_controlnet.py`:

```python
from unittest.mock import patch as _patch


@pytest.mark.asyncio
async def test_controlnet_modules_endpoint_sd_webui(monkeypatch):
    import api.image_router as image_router
    from config.settings import settings

    monkeypatch.setattr(settings, "image_gen_provider", "sd_webui")
    with _patch(
        "services.image.sd_webui_provider.SDWebUIProvider.list_controlnet_modules",
        new=AsyncMock(return_value=["none", "canny"]),
    ):
        result = await image_router.list_controlnet_modules()

    assert result == {"success": True, "modules": ["none", "canny"]}


@pytest.mark.asyncio
async def test_controlnet_modules_endpoint_non_sd_webui(monkeypatch):
    import api.image_router as image_router
    from config.settings import settings

    monkeypatch.setattr(settings, "image_gen_provider", "comfyui")
    result = await image_router.list_controlnet_modules()

    assert result["success"] is True
    assert result["modules"] == []


@pytest.mark.asyncio
async def test_controlnet_models_endpoint_sd_webui(monkeypatch):
    import api.image_router as image_router
    from config.settings import settings

    monkeypatch.setattr(settings, "image_gen_provider", "sd_webui")
    with _patch(
        "services.image.sd_webui_provider.SDWebUIProvider.list_controlnet_models",
        new=AsyncMock(return_value=["control_v11p_sd15_canny"]),
    ):
        result = await image_router.list_controlnet_models()

    assert result == {"success": True, "models": ["control_v11p_sd15_canny"]}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent && python -m pytest tests/test_image_controlnet.py -v`
Expected: FAIL — `AttributeError: module 'api.image_router' has no attribute 'list_controlnet_modules'`

- [ ] **Step 3: Write minimal implementation**

In `agent/api/image_router.py`, add two new endpoints right after `list_image_models()` (after line 109, before `get_image_progress()`):

```python
@router.get("/api/image/controlnet/modules")
async def list_controlnet_modules():
    """列出 SD WebUI ControlNet 可用预处理器（仅 sd_webui 支持）。"""
    if settings.image_gen_provider.lower() != "sd_webui":
        return {"success": True, "modules": [], "note": "ControlNet 仅 SD WebUI 支持"}
    from services.image.sd_webui_provider import SDWebUIProvider
    p = SDWebUIProvider(
        base_url=settings.image_gen_base_url,
        model=settings.image_gen_model,
        username=settings.image_gen_sd_user,
        password=settings.image_gen_sd_pass,
    )
    modules = await p.list_controlnet_modules()
    return {"success": True, "modules": modules}


@router.get("/api/image/controlnet/models")
async def list_controlnet_models():
    """列出 SD WebUI ControlNet 可用模型（仅 sd_webui 支持）。"""
    if settings.image_gen_provider.lower() != "sd_webui":
        return {"success": True, "models": [], "note": "ControlNet 仅 SD WebUI 支持"}
    from services.image.sd_webui_provider import SDWebUIProvider
    p = SDWebUIProvider(
        base_url=settings.image_gen_base_url,
        model=settings.image_gen_model,
        username=settings.image_gen_sd_user,
        password=settings.image_gen_sd_pass,
    )
    models = await p.list_controlnet_models()
    return {"success": True, "models": models}
```

Then, in `generate_image()`, the `ImageRequest` construction currently is:

```python
    req = ImageRequest(
        prompt             = prompt,
        size               = body.get("size")               or settings.image_gen_size,
        steps              = int(body.get("steps", 0))      or settings.image_gen_steps,
        guidance_scale     = float(body.get("cfg", 0))      or 7.5,
        negative_prompt    = body.get("negative_prompt")    or "",
        style              = body.get("style")              or None,
        sampler_name       = body.get("sampler_name")       or "DPM++ 2M Karras",
        init_image_base64  = body.get("init_image_base64")  or None,
        denoising_strength = float(body.get("denoising_strength", 0.75)),
    )
```

Add four lines before the closing `)`:

```python
    req = ImageRequest(
        prompt             = prompt,
        size               = body.get("size")               or settings.image_gen_size,
        steps              = int(body.get("steps", 0))      or settings.image_gen_steps,
        guidance_scale     = float(body.get("cfg", 0))      or 7.5,
        negative_prompt    = body.get("negative_prompt")    or "",
        style              = body.get("style")              or None,
        sampler_name       = body.get("sampler_name")       or "DPM++ 2M Karras",
        init_image_base64  = body.get("init_image_base64")  or None,
        denoising_strength = float(body.get("denoising_strength", 0.75)),
        controlnet_enabled = bool(body.get("controlnet_enabled", False)),
        controlnet_module  = body.get("controlnet_module")  or "none",
        controlnet_model   = body.get("controlnet_model")   or "",
        controlnet_weight  = float(body.get("controlnet_weight", 1.0)),
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent && python -m pytest tests/test_image_controlnet.py -v`
Expected: PASS (10 tests total)

- [ ] **Step 5: Commit**

```bash
git add agent/api/image_router.py agent/tests/test_image_controlnet.py
git commit -m "feat(image): ControlNet module/model list endpoints + generate passthrough"
```

---

### Task 4: Frontend API client — ControlNet endpoints

**Files:**
- Modify: `frontend/src/services/api.js`

**Interfaces:**
- Consumes: `GET /api/image/controlnet/modules`, `GET /api/image/controlnet/models` from Task 3.
- Produces: `getControlnetModules()`, `getControlnetModels()` exported functions — Task 5 imports these exact names.

- [ ] **Step 1: Add the two API functions**

In `frontend/src/services/api.js`, right after the existing `export const getImageProgress = ...` line (around line 238), add:

```js
export const getControlnetModules = () => request(`${BASE}/image/controlnet/modules`)
export const getControlnetModels  = () => request(`${BASE}/image/controlnet/models`)
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && node --check src/services/api.js`
Expected: no output (exit code 0)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/api.js
git commit -m "feat(image): add ControlNet module/model API client functions"
```

---

### Task 5: `ImageView.vue` ControlNet UI section

**Files:**
- Modify: `frontend/src/views/ImageView.vue`

**Interfaces:**
- Consumes: `getControlnetModules()`, `getControlnetModels()` from Task 4; `form.initImageB64`, `form.initImagePreview`, `providerName` already defined in the component.
- Produces: `form.controlnetEnabled`, `form.controlnetModule`, `form.controlnetModel`, `form.controlnetWeight` reactive fields sent in `doGenerate()`'s `generateImage()` call.

- [ ] **Step 1: Add form fields and ControlNet option lists**

In `frontend/src/views/ImageView.vue`, the `form` ref (around line 274) currently ends with:

```js
const form = ref({
  prompt:            '',
  negativePrompt:    'ugly, blurry, low quality, watermark, text',
  style:             '',
  size:              '512x512',
  steps:             20,
  cfg:               7,
  sampler:           'euler',          // ComfyUI 默认；切换到 sd_webui 时会重置
  initImagePreview:  null,             // Data URL for display
  initImageB64:      null,             // pure base64 for API
  denoisingStrength: 0.75,
})
```

Add four ControlNet fields:

```js
const form = ref({
  prompt:            '',
  negativePrompt:    'ugly, blurry, low quality, watermark, text',
  style:             '',
  size:              '512x512',
  steps:             20,
  cfg:               7,
  sampler:           'euler',          // ComfyUI 默认；切换到 sd_webui 时会重置
  initImagePreview:  null,             // Data URL for display
  initImageB64:      null,             // pure base64 for API
  denoisingStrength: 0.75,
  controlnetEnabled: false,
  controlnetModule:  'none',
  controlnetModel:   '',
  controlnetWeight:  1.0,
})
```

Add two new refs right after `const previewImg = ref(null)` (around line 299):

```js
const controlnetModules = ref([])
const controlnetModels  = ref([])
```

Update the imports at the top of `<script setup>` (around line 241-245) to add the two new API functions:

```js
import {
  getImageProviderStatus, listImageModels, switchImageModel,
  generateImage, listGeneratedImages, deleteGeneratedImage,
  getImageProgress, getControlnetModules, getControlnetModels,
} from '@/services/api'
```

- [ ] **Step 2: Load ControlNet option lists when provider is sd_webui**

The `watch(providerName, ...)` block (around line 312-315) currently is:

```js
// 切换 provider 时重置采样器默认值
watch(providerName, (name) => {
  if (name === 'sd_webui') form.value.sampler = 'DPM++ 2M Karras'
  else form.value.sampler = 'euler'
})
```

Extend it to also load ControlNet lists, and reset the toggle when leaving sd_webui:

```js
// 切换 provider 时重置采样器默认值
watch(providerName, async (name) => {
  if (name === 'sd_webui') {
    form.value.sampler = 'DPM++ 2M Karras'
    const [modulesRes, modelsRes] = await Promise.all([
      getControlnetModules().catch(() => null),
      getControlnetModels().catch(() => null),
    ])
    controlnetModules.value = modulesRes?.modules || []
    controlnetModels.value  = modelsRes?.models || []
  } else {
    form.value.sampler = 'euler'
    form.value.controlnetEnabled = false
    controlnetModules.value = []
    controlnetModels.value  = []
  }
})
```

- [ ] **Step 3: Add the ControlNet UI block in the template**

In the `<template>`, the img2img block (around lines 110-130) currently ends with:

```html
          <label v-else class="img2img-upload-btn">
            <i class="fas fa-upload" /> 上传底图
            <input type="file" accept="image/*" style="display:none" @change="onImg2imgFile" />
          </label>
        </div>
```

Add the ControlNet block right after that closing `</div>` (still inside `.param-section` siblings, before the generate button section):

```html
          <label v-else class="img2img-upload-btn">
            <i class="fas fa-upload" /> 上传底图
            <input type="file" accept="image/*" style="display:none" @change="onImg2imgFile" />
          </label>
        </div>

        <!-- ControlNet（仅 SD WebUI，需先上传底图） -->
        <div class="param-section" v-if="providerName === 'sd_webui' && form.initImageB64">
          <label class="param-label">
            <input type="checkbox" v-model="form.controlnetEnabled" />
            同时用作 ControlNet 控制图
          </label>
          <div v-if="form.controlnetEnabled" class="controlnet-options">
            <div class="param-item">
              <label class="param-label">预处理器</label>
              <select v-model="form.controlnetModule" class="sampler-select">
                <option v-for="m in controlnetModules" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>
            <div class="param-item">
              <label class="param-label">ControlNet 模型</label>
              <select v-model="form.controlnetModel" class="sampler-select">
                <option v-for="m in controlnetModels" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>
            <div class="param-item">
              <label class="param-label">引导权重 <span class="param-val">{{ form.controlnetWeight }}</span></label>
              <input type="range" v-model.number="form.controlnetWeight" min="0" max="2" step="0.05" class="steps-slider" />
            </div>
          </div>
        </div>
```

- [ ] **Step 4: Send ControlNet fields in the generate request**

In `doGenerate()`, the `generateImage()` call (around lines 445-455) currently is:

```js
    const res = await generateImage({
      prompt:             prompt,
      negative_prompt:    form.value.negativePrompt,
      style:              form.value.style || undefined,
      size:               form.value.size,
      steps:              form.value.steps,
      cfg:                form.value.cfg,
      sampler_name:       form.value.sampler,
      init_image_base64:  form.value.initImageB64 || undefined,
      denoising_strength: form.value.denoisingStrength,
    })
```

Add the four ControlNet fields:

```js
    const res = await generateImage({
      prompt:             prompt,
      negative_prompt:    form.value.negativePrompt,
      style:              form.value.style || undefined,
      size:               form.value.size,
      steps:              form.value.steps,
      cfg:                form.value.cfg,
      sampler_name:       form.value.sampler,
      init_image_base64:  form.value.initImageB64 || undefined,
      denoising_strength: form.value.denoisingStrength,
      controlnet_enabled: form.value.controlnetEnabled,
      controlnet_module:  form.value.controlnetModule,
      controlnet_model:   form.value.controlnetModel,
      controlnet_weight:  form.value.controlnetWeight,
    })
```

- [ ] **Step 5: Add minimal styling for the ControlNet options block**

Near the existing `.denoising-row { margin-top: var(--space-2); }` rule in the `<style>` section, add:

```css
.controlnet-options { margin-top: var(--space-2); display: flex; flex-direction: column; gap: var(--space-2); }
```

- [ ] **Step 6: Verify no build errors**

The project has no type-check script; use the production build to catch template/script syntax errors.

Run: `cd frontend && npm run build`
Expected: build completes without errors (warnings about chunk size are pre-existing and fine).

For full confidence, also do a manual check: run `cd frontend && npm run dev`, open `/image` in a browser with `IMAGE_GEN_PROVIDER=sd_webui` configured, upload a base image, and confirm the "同时用作 ControlNet 控制图" checkbox appears and toggling it reveals the two dropdowns + slider without console errors.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/views/ImageView.vue
git commit -m "feat(image): ControlNet UI in ImageView (SD WebUI only)"
```

---

### Task 6: `_request_image_b64_ctx` ContextVar

**Files:**
- Modify: `agent/core/_context_vars.py`

**Interfaces:**
- Produces: `_request_image_b64_ctx: contextvars.ContextVar` (default `None`) — Task 7 calls `.set()`, Task 8 calls `.get()`.

- [ ] **Step 1: Add the ContextVar**

In `agent/core/_context_vars.py`, after the existing `_last_message_vec_ctx` definition (end of file), add:

```python

# Per-request chat-attached image (base64, no data: prefix): lets ImageGenerationTool
# auto-use the image the user attached this turn for img2img, without the LLM ever
# handling image bytes as a function-call argument.
_request_image_b64_ctx: contextvars.ContextVar = contextvars.ContextVar(
    '_request_image_b64_ctx', default=None
)
```

- [ ] **Step 2: Verify it imports cleanly**

Run: `cd agent && python -c "from core._context_vars import _request_image_b64_ctx; print(_request_image_b64_ctx.get())"`
Expected output: `None`

- [ ] **Step 3: Commit**

```bash
git add agent/core/_context_vars.py
git commit -m "feat(core): add _request_image_b64_ctx ContextVar"
```

---

### Task 7: Set `_request_image_b64_ctx` in `chat()`/`chat_stream()`

**Files:**
- Modify: `agent/core/conversation_flow.py`

**Interfaces:**
- Consumes: `_request_image_b64_ctx` from Task 6.
- Produces: the ContextVar holds the current request's `image_base64` (or `None`) by the time `_call_model_with_tools` → tool execution runs — Task 8 relies on this being set before any tool call.

- [ ] **Step 1: Update imports**

In `agent/core/conversation_flow.py`, the import block (lines 18-22) currently is:

```python
from core._context_vars import (
    _request_provider_ctx,
    _request_persona_ctx,
    _last_message_vec_ctx,
)
```

Add the new ContextVar:

```python
from core._context_vars import (
    _request_provider_ctx,
    _request_persona_ctx,
    _last_message_vec_ctx,
    _request_image_b64_ctx,
)
```

- [ ] **Step 2: Set it in `chat()`**

In `chat()`, the existing block (lines 276-279) is:

```python
        if provider_override is not None:
            _request_provider_ctx.set(provider_override)
        if persona_override is not None:
            _request_persona_ctx.set(persona_override)
```

Add the unconditional set right after:

```python
        if provider_override is not None:
            _request_provider_ctx.set(provider_override)
        if persona_override is not None:
            _request_persona_ctx.set(persona_override)
        _request_image_b64_ctx.set(image_base64)
```

- [ ] **Step 3: Set it in `chat_stream()`**

In `chat_stream()`, the existing block (lines 508-511) is:

```python
        if provider_override is not None:
            _request_provider_ctx.set(provider_override)
        if persona_override is not None:
            _request_persona_ctx.set(persona_override)
```

Add the same line:

```python
        if provider_override is not None:
            _request_provider_ctx.set(provider_override)
        if persona_override is not None:
            _request_persona_ctx.set(persona_override)
        _request_image_b64_ctx.set(image_base64)
```

- [ ] **Step 4: Verify it imports and runs cleanly**

Run: `cd agent && python -c "import core.conversation_flow"`
Expected: no output, exit code 0 (import succeeds, no syntax/circular-import errors)

- [ ] **Step 5: Commit**

```bash
git add agent/core/conversation_flow.py
git commit -m "feat(core): populate _request_image_b64_ctx from chat()/chat_stream() image_base64"
```

---

### Task 8: `image_tool.py` sampler_name/denoising_strength + auto img2img

**Files:**
- Modify: `agent/tools/builtin_tools/image_tool.py`
- Test: `agent/tests/test_image_tool_chat_img2img.py` (new file)

**Interfaces:**
- Consumes: `_request_image_b64_ctx` from Task 6/7; `ImageRequest` fields from Task 1 (`sampler_name`, `init_image_base64`, `denoising_strength` already existed before this plan — only the wiring is new).
- Produces: `ImageGenerationTool.execute_async(prompt, size=None, negative_prompt="", style=None, sampler_name=None, denoising_strength=0.75)` — new signature; `execute()` wraps it with the same new params.

- [ ] **Step 1: Write the failing tests**

Create `agent/tests/test_image_tool_chat_img2img.py`:

```python
"""Tests for ImageGenerationTool auto-reading chat-attached image via ContextVar."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from core._context_vars import _request_image_b64_ctx
from tools.builtin_tools.image_tool import ImageGenerationTool


@pytest.mark.asyncio
async def test_execute_async_uses_context_image_for_img2img():
    token = _request_image_b64_ctx.set("ZmFrZWJhc2U2NA==")
    try:
        tool = ImageGenerationTool()
        fake_provider = MagicMock()
        fake_provider.is_available.return_value = True
        fake_result = MagicMock(success=True, image_bytes=b"img", image_url="", provider="sd_webui", model="sd15")
        fake_provider.generate = AsyncMock(return_value=fake_result)

        with patch("services.image.create_image_provider", return_value=fake_provider), \
             patch("tools.builtin_tools.image_tool._persist_image", new=AsyncMock(return_value="/api/images/x.png")):
            await tool.execute_async(prompt="a cat, watercolor", sampler_name="euler", denoising_strength=0.5)

        sent_req = fake_provider.generate.call_args[0][0]
        assert sent_req.init_image_base64 == "ZmFrZWJhc2U2NA=="
        assert sent_req.sampler_name == "euler"
        assert sent_req.denoising_strength == 0.5
    finally:
        _request_image_b64_ctx.reset(token)


@pytest.mark.asyncio
async def test_execute_async_without_context_image_is_txt2img():
    token = _request_image_b64_ctx.set(None)
    try:
        tool = ImageGenerationTool()
        fake_provider = MagicMock()
        fake_provider.is_available.return_value = True
        fake_result = MagicMock(success=True, image_bytes=b"img", image_url="", provider="sd_webui", model="sd15")
        fake_provider.generate = AsyncMock(return_value=fake_result)

        with patch("services.image.create_image_provider", return_value=fake_provider), \
             patch("tools.builtin_tools.image_tool._persist_image", new=AsyncMock(return_value="/api/images/x.png")):
            await tool.execute_async(prompt="a cat")

        sent_req = fake_provider.generate.call_args[0][0]
        assert sent_req.init_image_base64 is None
    finally:
        _request_image_b64_ctx.reset(token)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent && python -m pytest tests/test_image_tool_chat_img2img.py -v`
Expected: FAIL — `TypeError: execute_async() got an unexpected keyword argument 'sampler_name'`

- [ ] **Step 3: Write minimal implementation**

In `agent/tools/builtin_tools/image_tool.py`, the `__init__` description (lines 36-44) currently is:

```python
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
```

Replace with:

```python
    def __init__(self):
        super().__init__(
            description=(
                "根据文字描述生成图片。参数: "
                "prompt(图片描述,英文效果更好,必填), "
                "size(尺寸,可选,如 1024x1024 / 512x512 / 768x1024,默认1024x1024), "
                "negative_prompt(不希望出现的内容,可选), "
                "style(风格提示词,可选,如 photorealistic/anime/oil painting), "
                "sampler_name(采样器名称,可选,如 'DPM++ 2M Karras'/'euler',不填用默认), "
                "denoising_strength(去噪强度0~1,可选,仅当用户要求'在刚发的图基础上改'时设置,默认0.75). "
                "若用户在本轮消息中附带了图片,会自动作为底图用于图生图(img2img),不需要也不能把图片内容当作参数传入"
            )
        )
```

Then replace `execute_async` and `execute` (lines 46-98) entirely:

```python
    async def execute_async(
        self,
        prompt: str,
        size: Optional[str] = None,
        negative_prompt: str = "",
        style: Optional[str] = None,
        sampler_name: Optional[str] = None,
        denoising_strength: float = 0.75,
    ) -> str:
        from services.image import create_image_provider, ImageRequest
        from config.settings import settings
        from core._context_vars import _request_image_b64_ctx

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

        init_image_b64 = _request_image_b64_ctx.get()
        req = ImageRequest(
            prompt=prompt,
            size=size or settings.image_gen_size,
            steps=settings.image_gen_steps,
            style=style,
            negative_prompt=negative_prompt,
            sampler_name=sampler_name or "DPM++ 2M Karras",
            init_image_base64=init_image_b64,
            denoising_strength=denoising_strength,
        )
        result = await provider.generate(req)

        if not result.success:
            return f"❌ 图片生成失败（{result.provider}）：{result.error}"

        # 统一持久化到本地，确保图片在历史对话中长期可访问
        filename  = f"gen_{uuid.uuid4().hex[:12]}.png"
        local_url = await _persist_image(result, filename, _get_image_dir())

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
        sampler_name: Optional[str] = None,
        denoising_strength: float = 0.75,
    ) -> str:
        import asyncio
        return asyncio.run(self.execute_async(
            prompt, size, negative_prompt, style, sampler_name, denoising_strength
        ))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent && python -m pytest tests/test_image_tool_chat_img2img.py -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Run the full image-related test suite to check for regressions**

Run: `cd agent && python -m pytest tests/test_image_controlnet.py tests/test_image_tool_chat_img2img.py -v`
Expected: PASS (12 tests total)

- [ ] **Step 6: Commit**

```bash
git add agent/tools/builtin_tools/image_tool.py agent/tests/test_image_tool_chat_img2img.py
git commit -m "feat(image-tool): chat tool supports sampler_name + auto img2img via attached image"
```

---

## Final Verification

- [ ] Run the entire agent test suite to confirm no regressions: `cd agent && python -m pytest tests/ -v 2>&1 | tail -60`
- [ ] Manually exercise ControlNet end-to-end if a local SD WebUI with the ControlNet extension is available: upload a base image on `/image`, check "同时用作 ControlNet 控制图", pick a module/model, generate, confirm the image reflects structural guidance.
- [ ] Manually exercise the chat path: attach an image in the chat UI, say "把这张图改成水彩风格", confirm the assistant's `ImageGenerationTool` call result reflects img2img (visually similar composition, different style) rather than a fresh unrelated image.
