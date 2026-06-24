"""共享 ContextVar — 按请求隔离 provider/persona/embedding 缓存。

所有使用这三个变量的模块（agent.py、conversation_flow.py、tool_dispatcher.py）
均从此处导入，保证同一 asyncio Task 内的 set/get 操作作用于同一对象。
"""
import contextvars

# Per-request provider override: each asyncio Task gets its own copy, so concurrent
# requests for different users cannot interfere with each other.
_request_provider_ctx: contextvars.ContextVar = contextvars.ContextVar(
    '_request_provider_ctx', default=None
)

# Per-request persona override: persona content string or None (use model default template).
_request_persona_ctx: contextvars.ContextVar = contextvars.ContextVar(
    '_request_persona_ctx', default=None
)

# Per-request message embedding cache: avoids re-encoding the same message in build_context and
# filter_tools within a single request. ContextVar guarantees concurrent requests don't collide.
_last_message_vec_ctx: contextvars.ContextVar = contextvars.ContextVar(
    '_last_message_vec_ctx', default=None
)

# Per-request chat-attached image (base64, no data: prefix): lets ImageGenerationTool
# auto-use the image the user attached this turn for img2img, without the LLM ever
# handling image bytes as a function-call argument.
_request_image_b64_ctx: contextvars.ContextVar = contextvars.ContextVar(
    '_request_image_b64_ctx', default=None
)

# Per-request output channel ("web" / "feishu_im" / ...): lets SystemPromptBuilder decide
# whether to inject the whisper (private/unrestricted) soul section. Defaults to "web" so
# existing callers that never set it keep today's behavior unchanged.
_request_channel_ctx: contextvars.ContextVar = contextvars.ContextVar(
    '_request_channel_ctx', default="web"
)
