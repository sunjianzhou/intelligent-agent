package com.intelligent.agent.web.ai.agent;

import java.util.List;
import java.util.Map;

/**
 * 单次 Agent 请求上下文（请求级，不进入任何单例字段）。
 *
 * @param userId    用户 ID
 * @param message   用户消息
 * @param model     请求模型名；null = 使用默认模型
 * @param persona   角色名；null = 默认角色
 * @param projectId 项目 ID（可为 null）
 * @param sessionId 会话 ID（可为 null）
 * @param useTools  是否允许工具调用
 * @param useMemory 是否使用记忆（Plan 2 接入）
 * @param channel   请求来源渠道（web / feishu_im / ...）
 * @param options   模型参数覆盖
 * @param imageBase64 多模态图片（base64，不含 data URL 前缀；可为 null）
 * @param sceneChatType 多人会话场景标记（group / p2p；可为 null）
 * @param sceneMentioned group 场景下是否被显式 @ 提及
 * @param pendingTasks  项目待处理任务列表（前端随请求传入，注入 [TASKS] 上下文；
 *                      2026-08-15 补齐，对齐 Python pending_tasks）
 * @param requestId     请求 traceID（G4 可观测性；可为 null）
 * @param replyAddress  渠道回执地址（R-09：IM 审批卡片发往的 chat_id/open_id，可为 null）
 */
public record AgentRequestContext(
        String userId,
        String message,
        String model,
        String persona,
        String projectId,
        String sessionId,
        boolean useTools,
        boolean useMemory,
        String channel,
        Map<String, Object> options,
        String imageBase64,
        String sceneChatType,
        boolean sceneMentioned,
        List<Map<String, Object>> pendingTasks,
        String requestId,
        String replyAddress) {

    public AgentRequestContext {
        userId = userId == null ? "" : userId;
        message = message == null ? "" : message;
        options = options == null ? Map.of() : Map.copyOf(options);
        pendingTasks = pendingTasks == null ? List.of()
                : List.copyOf(pendingTasks.stream().filter(java.util.Objects::nonNull).toList());
    }

    /** 无图片/场景标记的便捷构造（保持旧调用点兼容）。 */
    public AgentRequestContext(String userId, String message, String model, String persona,
                               String projectId, String sessionId, boolean useTools,
                               boolean useMemory, String channel, Map<String, Object> options) {
        this(userId, message, model, persona, projectId, sessionId, useTools, useMemory,
                channel, options, null, null, false, List.of(), null, null);
    }

    /** 13 参便捷构造（无 pendingTasks/requestId），保持旧调用点兼容。 */
    public AgentRequestContext(String userId, String message, String model, String persona,
                               String projectId, String sessionId, boolean useTools,
                               boolean useMemory, String channel, Map<String, Object> options,
                               String imageBase64, String sceneChatType, boolean sceneMentioned) {
        this(userId, message, model, persona, projectId, sessionId, useTools, useMemory,
                channel, options, imageBase64, sceneChatType, sceneMentioned, List.of(), null, null);
    }

    public static AgentRequestContext of(String userId, String message) {
        return new AgentRequestContext(
                userId, message, null, null, null, null, true, true, null, Map.of(),
                null, null, false);
    }
}
