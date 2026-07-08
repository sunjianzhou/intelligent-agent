package com.intelligent.agent.web.im;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送操作统一结果（与 Python SendResult 语义一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendResult {

    private boolean success;
    /** 归一化格式 channel_type:original_id */
    private String messageId;
    private String error;
    private ChannelType channel;
    /** 发送耗时（毫秒） */
    private double latencyMs;
}
