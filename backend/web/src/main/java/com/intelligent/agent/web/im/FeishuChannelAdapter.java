package com.intelligent.agent.web.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.feishu.FeishuConfig;
import com.intelligent.agent.web.feishu.FeishuMessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 飞书 Channel 适配器（Java 侧）。
 *
 * 实现 ChannelAdapter 接口，委托给现有的 FeishuMessageSender。
 * 不修改 FeishuEventController，只新增此适配器类。
 */
@Slf4j
@Component
public class FeishuChannelAdapter implements ChannelAdapter {

    private final FeishuConfig config;
    private final FeishuMessageSender sender;
    private final ObjectMapper objectMapper;

    @Autowired
    public FeishuChannelAdapter(FeishuConfig config,
                                 FeishuMessageSender sender,
                                 ObjectMapper objectMapper) {
        this.config = config;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.FEISHU;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public ChannelMessage normalizeMessage(Object rawEvent) {
        // 飞书事件解析已在 FeishuEventController.routeEvent() 中完成，
        // 此方法供未来统一入口使用。
        if (rawEvent instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) rawEvent;
            ChannelMessage msg = new ChannelMessage();
            msg.setChannel(ChannelType.FEISHU);
            msg.setRawPayload(event);
            return msg;
        }
        return null;
    }

    @Override
    public SendResult sendText(String receiverId, String text, String chatType) {
        try {
            String messageId = sender.sendText(receiverId, truncateText(text));
            return new SendResult(messageId != null, messageId, null,
                    ChannelType.FEISHU, 0);
        } catch (Exception e) {
            log.error("[feishu-adapter] sendText 失败, receiverId={}: {}",
                    receiverId, e.getMessage());
            return new SendResult(false, null, e.getMessage(),
                    ChannelType.FEISHU, 0);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public SendResult sendCard(String receiverId, Map<String, Object> card,
                                String chatType) {
        try {
            String cardJson = objectMapper.writeValueAsString(card);
            String messageId = sender.sendInteractive(receiverId, cardJson);
            return new SendResult(messageId != null, messageId, null,
                    ChannelType.FEISHU, 0);
        } catch (Exception e) {
            log.error("[feishu-adapter] sendCard 失败, receiverId={}: {}",
                    receiverId, e.getMessage());
            return new SendResult(false, null, e.getMessage(),
                    ChannelType.FEISHU, 0);
        }
    }

    @Override
    public String extractRawMessageId(Map<String, Object> apiResponse) {
        // 飞书 FeishuMessageSender 直接返回 message_id 字符串
        return null;  // 由调用方处理
    }
}
