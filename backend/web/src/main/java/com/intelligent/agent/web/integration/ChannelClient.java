package com.intelligent.agent.web.integration;

import com.intelligent.agent.web.im.ChannelMessage;

/**
 * 具名通道客户端（Plan 2 / Task 5）：飞书 / 企微 / Telegram 等外部 IM 通道。
 * 每个实现负责协议、限流、重试与消息 id 归一化。
 */
public interface ChannelClient {

    /** 通道类型标识（feishu / wechat / telegram ...）。 */
    String channelType();

    boolean isEnabled();

    DeliveryResult send(ChannelMessage message);
}
