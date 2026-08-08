package com.intelligent.agent.web.integration.wechat;

import com.intelligent.agent.web.im.ChannelMessage;
import com.intelligent.agent.web.im.RetryConfig;
import com.intelligent.agent.web.im.TokenBucket;
import com.intelligent.agent.web.integration.ChannelClient;
import com.intelligent.agent.web.integration.DeliveryResult;
import com.intelligent.agent.web.wecom.WeComMessageSender;
import lombok.extern.slf4j.Slf4j;

/**
 * 企业微信通道客户端（Plan 2 / Task 5）：
 * 委托 {@link WeComMessageSender}，1.67/s 限流（企微消息频率限制）+ 重试。
 */
@Slf4j
public class WeChatChannelClient implements ChannelClient {

    private final WeComMessageSender sender;
    private final boolean enabled;
    private final TokenBucket rateLimiter = new TokenBucket(1.67, 5);
    private final RetryConfig retryConfig;

    public WeChatChannelClient(WeComMessageSender sender, boolean enabled) {
        this(sender, enabled, RetryConfig.DEFAULT);
    }

    public WeChatChannelClient(WeComMessageSender sender, boolean enabled, RetryConfig retryConfig) {
        this.sender = sender;
        this.enabled = enabled;
        this.retryConfig = retryConfig;
    }

    @Override
    public String channelType() {
        return "wecom";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public DeliveryResult send(ChannelMessage message) {
        if (!enabled) {
            return DeliveryResult.failed("wechat", "企微通道未启用");
        }
        if (!rateLimiter.acquire()) {
            return DeliveryResult.failed("wechat", "企微限流（1.67/s）");
        }
        String toUser = message.getChatId() == null || message.getChatId().isBlank()
                ? message.getSenderId() : message.getChatId();
        Exception lastError = null;
        for (int attempt = 1; attempt <= retryConfig.maxRetries(); attempt++) {
            try {
                sender.sendText(toUser, message.getContent());
                return DeliveryResult.accepted("wechat", "wechat:" + toUser);
            } catch (Exception e) {
                lastError = e;
                if (attempt < retryConfig.maxRetries()) {
                    try {
                        Thread.sleep((long) (retryConfig.delayForAttempt(attempt) * 1000));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("企微消息发送失败 toUser={}: {}", toUser,
                lastError == null ? "unknown" : lastError.getMessage());
        return DeliveryResult.failed("wechat",
                lastError == null ? "send failed" : lastError.getMessage());
    }
}
