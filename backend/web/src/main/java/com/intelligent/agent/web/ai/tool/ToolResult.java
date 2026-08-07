package com.intelligent.agent.web.ai.tool;

/**
 * 工具执行结果。
 *
 * @param status           success / denied / error / not_found / timeout
 * @param data             成功时的数据
 * @param error            失败/拒绝时的错误信息
 * @param executionTimeMs  执行耗时（毫秒）
 */
public record ToolResult(String status, Object data, String error, long executionTimeMs) {

    public static final String SUCCESS = "success";
    public static final String DENIED = "denied";
    public static final String ERROR = "error";
    public static final String NOT_FOUND = "not_found";
    public static final String TIMEOUT = "timeout";

    public static ToolResult success(Object data) {
        return new ToolResult(SUCCESS, data, null, 0);
    }

    public static ToolResult denied(String error) {
        return new ToolResult(DENIED, null, error, 0);
    }

    public static ToolResult error(String error) {
        return new ToolResult(ERROR, null, error, 0);
    }

    public static ToolResult notFound(String error) {
        return new ToolResult(NOT_FOUND, null, error, 0);
    }

    public static ToolResult timeout(String error) {
        return new ToolResult(TIMEOUT, null, error, 0);
    }
}
