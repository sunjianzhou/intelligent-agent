package com.intelligent.agent.web.im;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨 channel 统一消息模型（与 Python ChannelMessage 语义一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelMessage {

    private ChannelType channel;
    private String senderId;
    private String content;
    /** 归一化格式：channel_type:original_id */
    private String messageId = "";
    /** 去重键：request_id 或 hash(content+ts) */
    private String dedupKey = "";
    private String msgType = "text";
    private String chatId;
    private String chatType;  // "p2p" / "group"
    private boolean mentioned;
    /** 消息生命周期状态 */
    private String status = "pending";
    private Map<String, Object> rawPayload;
}
