package com.intelligent.agent.web.dto;

/**
 * WebSocket 消息类型常量（前端 / Java / Python 三端共享协议）。
 * 新增消息类型只改这里，switch-case 和前端常量文件同步更新。
 *
 * 协议版本: 1
 */
public final class WebSocketMessageType {

    // ── 客户端 → 服务端 ───────────────────────────────────────
    /** 聊天消息 */
    public static final String CHAT_MESSAGE    = "chat_message";
    /** 心跳 */
    public static final String PING            = "ping";
    /** 请求系统信息 */
    public static final String GET_SYSTEM_INFO = "get_system_info";

    // ── 服务端 → 客户端 ───────────────────────────────────────
    /** 连接建立确认 */
    public static final String CONNECTION_ESTABLISHED = "connection_established";
    /** 系统信息推送 */
    public static final String SYSTEM_INFO     = "system_info";
    /** 心跳响应 */
    public static final String PONG            = "pong";
    /** 模型思考中（ReAct 循环开始） */
    public static final String THINKING        = "thinking";
    /** 单个工具启动（实时进度） */
    public static final String TOOL_CALL_START = "tool_call_start";
    /** 单轮工具调用全部完成 */
    public static final String TOOL_CALLS_DONE = "tool_calls_done";
    /** 流式 token */
    public static final String CHAT_TOKEN      = "chat_token";
    /** 流式回答结束 */
    public static final String CHAT_DONE       = "chat_done";
    /** 非流式完整回答（降级兼容） */
    public static final String CHAT_RESPONSE   = "chat_response";
    /** 错误 */
    public static final String ERROR           = "error";
    /** 任务状态更新（Python [TASK_DONE] 触发） */
    public static final String TASK_UPDATE     = "task_update";
    /** 任务被阻塞通知（Python [TASK_BLOCKED] 触发） */
    public static final String TASK_BLOCKED    = "task_blocked";
    /** 定时任务通知推送（Java 主动 push，取代前端 30s 轮询） */
    public static final String NOTIFICATION    = "notification";
    /** LLM <think>…</think> CoT 思维块（流式，逐片推送） */
    public static final String THINKING_CHUNK  = "thinking_chunk";

    /** 当前协议版本，随协议变更递增 */
    public static final int PROTOCOL_VERSION = 1;

    private WebSocketMessageType() {}
}
