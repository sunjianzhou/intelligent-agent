package com.intelligent.agent.web.im;

import java.util.Map;
import java.util.Optional;

/**
 * Channel 适配器接口（Java 侧）。
 *
 * 职责：
 *   1. 外部消息归一化（Controller 收到原始事件 → normalizeMessage → ChannelMessage）
 *   2. 回复消息发送（sendText / sendCard / sendFile）
 *   3. 生命周期管理（start / stop）
 *
 * 每个 channel 实现一个 @Component，由 ChannelAdapterManager 统一管理。
 *
 * 异步/同步边界：
 *   - Java 侧所有方法为同步（Spring MVC 线程模型）
 *   - 需要并行时用 CompletableFuture.supplyAsync() 包装
 *   - Python 侧所有方法为 async（FastAPI 事件循环）
 */
public interface ChannelAdapter {

    /** channel 类型标识 */
    ChannelType channelType();

    /** 是否启用（从配置读取） */
    boolean isEnabled();

    // ── 限流 ──────────────────────────────────────────────

    /** 重试配置 */
    default RetryConfig retryConfig() { return RetryConfig.DEFAULT; }

    // ── 消息归一化 ────────────────────────────────────────

    /**
     * 将 channel 原始事件归一化为 ChannelMessage。
     * Controller 收到外部消息后调用此方法，然后传给 AgentService.chatFull()。
     */
    ChannelMessage normalizeMessage(Object rawEvent);

    // ── 发送 ──────────────────────────────────────────────

    /** 发送文本消息 */
    SendResult sendText(String receiverId, String text, String chatType);

    /** 发送卡片/富文本消息 */
    SendResult sendCard(String receiverId, Map<String, Object> card, String chatType);

    /** 发送文件 */
    default SendResult sendFile(String receiverId, String filePath,
                                String fileName, String chatType) {
        return new SendResult(false, null, "file 未实现", channelType(), 0);
    }

    /** 发送图片 */
    default SendResult sendImage(String receiverId, byte[] imageData,
                                 String chatType) {
        return new SendResult(false, null, "image 未实现", channelType(), 0);
    }

    // ── 用户信息 ──────────────────────────────────────────

    /** 获取用户信息 */
    default Optional<UserInfo> getUserInfo(String userId) {
        return Optional.empty();
    }

    // ── 消息验证 ──────────────────────────────────────────

    /** 从 API 响应中提取原始 message_id */
    default String extractRawMessageId(Map<String, Object> apiResponse) {
        if (apiResponse == null) return null;
        Object data = apiResponse.get("data");
        if (data instanceof Map) {
            return (String) ((Map<?, ?>) data).get("message_id");
        }
        return null;
    }

    /** 归一化 message_id → "channel_type:original_id" */
    default String extractMessageId(Map<String, Object> apiResponse) {
        String rawId = extractRawMessageId(apiResponse);
        if (rawId == null || rawId.trim().isEmpty()) return null;
        return channelType().getValue() + ":" + rawId;
    }

    // ── 指标 ──────────────────────────────────────────────

    default ChannelMetric getMetrics() {
        return new ChannelMetric(channelType());
    }

    // ── 配置 ──────────────────────────────────────────────

    /** 单条文本最大字符数 */
    default int maxTextLength() { return 4000; }

    /** 截断文本 */
    default String truncateText(String text) {
        if (text == null) return "";
        int max = maxTextLength();
        if (text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }

    // ── 生命周期 ──────────────────────────────────────────

    default void start() {}
    default void stop() {}
}
