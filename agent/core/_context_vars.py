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
