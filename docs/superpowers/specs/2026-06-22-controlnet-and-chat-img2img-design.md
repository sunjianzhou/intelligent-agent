# ControlNet 支持 + 聊天工具 img2img/sampler 补全

日期：2026-06-22
对应 TODO：TODOS.md「TODO-IMG-1（归档）」唯一未完成项（ControlNet），以及调研中发现的隐藏缺口（聊天内 `image_tool.py` 不支持 img2img/sampler）

## 背景

核查 TODO-IMG-1 时发现：进度查询、img2img、采样器选择已经在专门的图片生成页（`ImageView.vue` + `/api/image/generate`）全栈实现，TODOS.md 记录已过时。真正缺失只有两块：

1. SD WebUI 的 ControlNet 支持（TODO-IMG-1 列出但未做）
2. 聊天里直接说"画一只猫"触发的 `ImageGenerationTool`（`agent/tools/builtin_tools/image_tool.py`）完全没有 `sampler_name`/`init_image_base64`/`denoising_strength`，只有专门页面能用这些参数

## 范围

- 仅 SD WebUI provider 支持 ControlNet（ComfyUI/diffusers 不在本次范围）
- ControlNet 复用现有 img2img 底图上传，不新增独立上传组件
- 聊天工具的 img2img 走自动注入（上下文里有图就用），不让 LLM 传图片 base64

## 设计

### A. ControlNet 支持（SD WebUI）

**数据流**：用户在图片生成页上传一张底图（已有 img2img 上传组件），勾选「同时用作 ControlNet 控制图」后追加 module/model 下拉框 + weight 滑块。这张图同时走 img2img（混合去噪）和 ControlNet（结构引导）——不做"纯 txt2img + controlnet 不混合像素"的模式，避免再加一套上传 UI。

**改动文件**：

- `agent/services/image/base_image_provider.py`
  - `ImageRequest` 新增字段：`controlnet_enabled: bool = False`、`controlnet_module: str = "none"`、`controlnet_model: str = ""`、`controlnet_weight: float = 1.0`

- `agent/services/image/sd_webui_provider.py`
  - 新增 `list_controlnet_modules() -> list`：GET `/controlnet/module_list`，返回 `module_list` 字段
  - 新增 `list_controlnet_models() -> list`：GET `/controlnet/model_list`，返回 `model_list` 字段
  - `generate()` 中：当 `req.controlnet_enabled and req.init_image_base64` 为真时，在 payload（img2img 分支）里追加：
    ```python
    payload["alwayson_scripts"] = {
        "controlnet": {
            "args": [{
                "input_image": req.init_image_base64,
                "module": req.controlnet_module,
                "model": req.controlnet_model,
                "weight": req.controlnet_weight,
            }]
        }
    }
    ```
  - 两个新方法异常时返回 `[]`（与现有 `list_models()` 容错风格一致），失败不影响主生成流程

- `agent/api/image_router.py`
  - 新增 `GET /api/image/controlnet/modules`：仅 `provider == sd_webui` 时调用 `list_controlnet_modules()`，否则返回 `{"success": True, "modules": [], "note": "..."}`
  - 新增 `GET /api/image/controlnet/models`：同上，调用 `list_controlnet_models()`
  - `generate_image()` 中 `ImageRequest` 构造追加 4 个字段，从 `body` 读取（`controlnet_enabled` 默认 `False`，其余字段沿用 `ImageRequest` 默认值）

- `frontend/src/services/api.js`
  - 新增 `getControlnetModules()`、`getControlnetModels()`

- `frontend/src/views/ImageView.vue`
  - 仅当 `providerName.value === 'sd_webui'` 且已上传底图（`form.initImageB64` 非空）时显示 ControlNet 区块
  - 区块内容：勾选框「同时用作 ControlNet 控制图」+ module 下拉（数据源 `getControlnetModules()`）+ model 下拉（数据源 `getControlnetModels()`）+ weight 滑块（0~2，step 0.05，默认 1.0）
  - 下拉框在组件挂载且 provider 为 sd_webui 时各拉取一次（参考现有 `SAMPLER_OPTIONS` 拉取时机）
  - 生成请求 body 追加 4 个字段

### B. 聊天工具补全 sampler/img2img

**关键约束**：LLM function-calling 的参数只能是文本，模型不可能生成图片 base64 当参数传。因此 `init_image_base64` 不作为 LLM 可调用参数，而是从当前请求上下文自动读取（用户在聊天里发的图，如果有）。

**改动文件**：

- `agent/core/_context_vars.py`
  - 新增 `_request_image_b64_ctx: contextvars.ContextVar = contextvars.ContextVar('_request_image_b64_ctx', default=None)`，注释说明用途同其余三个 ContextVar

- `agent/core/conversation_flow.py`
  - `chat()` 方法开头（与 `_request_provider_ctx.set(...)` 同一处）追加：`_request_image_b64_ctx.set(image_base64)`
  - `chat_stream()` 同样位置追加相同语句
  - 两处都是无条件 set（即使 `image_base64` 为 `None` 也要 set，清空上一次可能残留的值——尽管 ContextVar 在并发请求间已隔离，但同一上下文如果被复用需要显式清空）

- `agent/tools/builtin_tools/image_tool.py`
  - `ImageGenerationTool.__init__` 的 `description` 追加两个可选参数说明：
    - `sampler_name`（可选，采样器名称，如 "DPM++ 2M Karras" / "euler"，不填用 provider 默认）
    - `denoising_strength`（可选，0~1，仅当用户发的图要求"在此基础上改"时填，默认 0.75）
    - 同时补充说明："若用户在本轮消息中附带了图片，会自动作为底图用于图生图（img2img），不需要也不能传图片内容"
  - `execute_async` 签名追加 `sampler_name: Optional[str] = None, denoising_strength: float = 0.75`
  - 函数内部：
    ```python
    from core._context_vars import _request_image_b64_ctx
    init_image_b64 = _request_image_b64_ctx.get()
    ```
  - `ImageRequest` 构造追加 `sampler_name=sampler_name or "DPM++ 2M Karras"`、`init_image_base64=init_image_b64`、`denoising_strength=denoising_strength`
  - `execute()`（同步包装）签名同步追加这两个参数并透传给 `execute_async`

**为什么不改 `tool_dispatcher.py`**：`_execute_tool_round` 用 `asyncio.gather()` 并发执行工具调用，`gather` 内部通过 `ensure_future` 创建子 Task，子 Task 在创建时会复制当前 context（含所有 ContextVar 的值），所以只要 `_request_image_b64_ctx.set()` 在工具执行前完成（确实如此，在 `chat()`/`chat_stream()` 入口处），`image_tool.py` 内部 `.get()` 就能拿到正确值，无需在调用链上显式传参。

## 测试

- `sd_webui_provider.py` 新方法：mock `httpx.AsyncClient.get` 返回 module_list/model_list，校验解析；`generate()` 注入 controlnet payload 的单测
- `image_router.py` 新端点：sd_webui 走 happy path，其他 provider 返回空列表
- `image_tool.py`：用 `_request_image_b64_ctx.set("fakebase64")` 模拟聊天带图场景，断言传入 provider 的 `ImageRequest.init_image_base64` 等于设置值；未 set 时为 `None`
- 前端不写自动化测试（项目里 `ImageView.vue` 此前也没有），人工过一遍：上传图 → 勾选 ControlNet → 下拉框能拉到数据 → 生成请求带上 4 个字段（可在浏览器 Network 面板确认，不依赖真实 SD WebUI 服务也可以验证请求体）

## 不做的事

- ComfyUI / diffusers 的 ControlNet（无现成需求来源，TODOS.md 只提了 SD WebUI）
- 独立的 ControlNet 控制图上传组件（复用 img2img 上传）
- 纯 txt2img + ControlNet（不混合像素）模式
- 聊天工具暴露 `controlnet_enabled` 等参数给 LLM（ControlNet 仍只在专门生成页可用，聊天工具只补 sampler/img2img 自动注入）
