package com.intelligent.agent.web.ai.llm;

import java.util.List;
import java.util.Map;

/**
 * 模型无关的一次对话请求。
 *
 * @param userId   请求所属用户（用户隔离与路由用，空串表示匿名）
 * @param model    请求的模型名
 * @param messages 对话消息
 * @param options  模型参数（temperature / num_ctx 等）
 * @param images   多模态图片（base64 列表，不含 data URL 前缀；非多模态模型时忽略）
 */
public record ChatTurn(
        String userId,
        String model,
        List<ChatMessage> messages,
        Map<String, Object> options,
        List<String> images) {

    public ChatTurn {
        userId = userId == null ? "" : userId;
        model = model == null ? "" : model;
        messages = messages == null ? List.of() : List.copyOf(messages);
        options = options == null ? Map.of() : Map.copyOf(options);
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** 无图片的便捷构造（保持旧调用点兼容）。 */
    public ChatTurn(String userId, String model, List<ChatMessage> messages,
                    Map<String, Object> options) {
        this(userId, model, messages, options, List.of());
    }

    public static ChatTurn of(String model, List<ChatMessage> messages) {
        return new ChatTurn(null, model, messages, Map.of());
    }
}
