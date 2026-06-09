/**
 * WebSocket 消息类型常量（与 Java WebSocketMessageType.java 保持一致）
 * 协议版本: 1
 *
 * 新增消息类型时同步修改 Java 端的 WebSocketMessageType.java
 */

// 客户端 → 服务端
export const WS_CHAT_MESSAGE    = 'chat_message'
export const WS_PING            = 'ping'
export const WS_GET_SYSTEM_INFO = 'get_system_info'

// 服务端 → 客户端
export const WS_CONNECTION_ESTABLISHED = 'connection_established'
export const WS_SYSTEM_INFO     = 'system_info'
export const WS_PONG            = 'pong'
export const WS_THINKING        = 'thinking'
export const WS_TOOL_CALL_START = 'tool_call_start'
export const WS_TOOL_CALLS_DONE = 'tool_calls_done'
export const WS_CHAT_TOKEN      = 'chat_token'
export const WS_CHAT_DONE       = 'chat_done'
export const WS_CHAT_RESPONSE   = 'chat_response'
export const WS_ERROR           = 'error'

export const PROTOCOL_VERSION = 1
