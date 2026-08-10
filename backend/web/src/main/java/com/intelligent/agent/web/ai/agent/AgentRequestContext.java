package com.intelligent.agent.web.ai.agent;

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
        boolean sceneMentioned) {

    public AgentRequestContext {
        userId = userId == null ? "" : userId;
        message = message == null ? "" : message;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** 无图片/场景标记的便捷构造（保持旧调用点兼容）。 */
    public AgentRequestContext(String userId, String message, String model, String persona,
                               String projectId, String sessionId, boolean useTools,
                               boolean useMemory, String channel, Map<String, Object> options) {
        this(userId, message, model, persona, projectId, sessionId, useTools, useMemory,
                channel, options, null, null, false);
    }

    public static AgentRequestContext of(String userId, String message) {
        return new AgentRequestContext(
                userId, message, null, null, null, null, true, true, null, Map.of(),
                null, null, false);
    }
}
