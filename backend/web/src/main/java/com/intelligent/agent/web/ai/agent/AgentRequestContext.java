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
        Map<String, Object> options) {

    public AgentRequestContext {
        userId = userId == null ? "" : userId;
        message = message == null ? "" : message;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static AgentRequestContext of(String userId, String message) {
        return new AgentRequestContext(
                userId, message, null, null, null, null, true, true, null, Map.of());
    }
}
