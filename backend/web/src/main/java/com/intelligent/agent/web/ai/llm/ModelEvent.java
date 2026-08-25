package com.intelligent.agent.web.ai.llm;

import java.util.Map;
import java.util.Objects;

/**
 * 模型无关的流式事件，序列化形状与既有 SSE 协议一致：{@code {"type":...,"data":...}}。
 * <p>
 * 公开契约仅允许以下事件类型：
 * token / tool_call_start / tool_call / tool_calls_done / plan / approval_required / done / error。
 *
 * @param type 事件类型
 * @param data 事件数据（token/error 为字符串，tool_* 为结构化对象）
 */
public record ModelEvent(String type, Object data) {

    public static final String TYPE_TOKEN = "token";
    public static final String TYPE_TOOL_CALL_START = "tool_call_start";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_CALLS_DONE = "tool_calls_done";
    public static final String TYPE_DONE = "done";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_PLAN = "plan";
    public static final String TYPE_APPROVAL_REQUIRED = "approval_required";
    public static final String TYPE_TASK_UPDATE = "task_update";
    public static final String TYPE_TASK_BLOCKED = "task_blocked";
    public static final String TYPE_MODEL_FALLBACK = "model_fallback";

    public ModelEvent {
        Objects.requireNonNull(type, "type must not be null");
    }

    public static ModelEvent token(String token) {
        return new ModelEvent(TYPE_TOKEN, token);
    }

    public static ModelEvent toolCallStart(Object toolData) {
        return new ModelEvent(TYPE_TOOL_CALL_START, toolData);
    }

    public static ModelEvent toolCall(Object toolData) {
        return new ModelEvent(TYPE_TOOL_CALL, toolData);
    }

    public static ModelEvent toolCallsDone(Object toolCalls) {
        return new ModelEvent(TYPE_TOOL_CALLS_DONE, toolCalls);
    }

    public static ModelEvent done(Object data) {
        return new ModelEvent(TYPE_DONE, data);
    }

    public static ModelEvent error(String message) {
        return new ModelEvent(TYPE_ERROR, message);
    }

    /** G6 planning 前置：执行计划事件（data 为 {@code ExecutionPlan}）。 */
    public static ModelEvent plan(Object planData) {
        return new ModelEvent(TYPE_PLAN, planData);
    }

    /** G6 HITL：工具调用需要用户审批（data 含 approval_id/tool/args）。 */
    public static ModelEvent approvalRequired(Object approvalData) {
        return new ModelEvent(TYPE_APPROVAL_REQUIRED, approvalData);
    }

    public static ModelEvent taskUpdate(Object data) {
        return new ModelEvent(TYPE_TASK_UPDATE, data);
    }

    public static ModelEvent taskBlocked(Object data) {
        return new ModelEvent(TYPE_TASK_BLOCKED, data);
    }

    /** R-02：模型降级事件（data 含 from/to/reason），前端模型徽章显示实际生效模型。 */
    public static ModelEvent modelFallback(String from, String to, String reason) {
        return new ModelEvent(TYPE_MODEL_FALLBACK, Map.of(
                "from", from == null ? "" : from,
                "to", to == null ? "" : to,
                "reason", reason == null ? "" : reason));
    }
}
