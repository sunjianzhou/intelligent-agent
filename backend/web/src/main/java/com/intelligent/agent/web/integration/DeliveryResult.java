package com.intelligent.agent.web.integration;

/**
 * 通道投递结果。
 *
 * @param accepted    是否被通道接受（含重试成功）
 * @param messageId   归一化 message_id（channel_type:original_id）
 * @param error       失败原因（accepted=false 时）
 * @param channelType 通道类型
 */
public record DeliveryResult(boolean accepted, String messageId, String error, String channelType) {

    public static DeliveryResult accepted(String channelType, String messageId) {
        return new DeliveryResult(true, messageId, null, channelType);
    }

    public static DeliveryResult failed(String channelType, String error) {
        return new DeliveryResult(false, null, error, channelType);
    }
}
