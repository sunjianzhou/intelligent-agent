package com.intelligent.agent.web.integration.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.im.ChannelMessage;
import com.intelligent.agent.web.im.RetryConfig;
import com.intelligent.agent.web.im.TokenBucket;
import com.intelligent.agent.web.integration.ChannelClient;
import com.intelligent.agent.web.integration.DeliveryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Telegram 通道客户端（Plan 2 / Task 5）：
 * POST /bot{token}/sendMessage，30/s 限流 + 重试，解析 result.message_id。
 */
@Slf4j
public class TelegramChannelClient implements ChannelClient {

    private static final String DEFAULT_BASE_URL = "https://api.telegram.org";

    private final String botToken;
    private final String baseUrl;
    private final boolean enabled;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TokenBucket rateLimiter = new TokenBucket(30, 10);
    private final RetryConfig retryConfig;

    public TelegramChannelClient(String botToken, boolean enabled) {
        this(botToken, DEFAULT_BASE_URL, enabled, RetryConfig.DEFAULT);
    }

    public TelegramChannelClient(String botToken, String baseUrl, boolean enabled,
                                 RetryConfig retryConfig) {
        this.botToken = botToken;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.retryConfig = retryConfig;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String channelType() {
        return "telegram";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public DeliveryResult send(ChannelMessage message) {
        if (!enabled) {
            return DeliveryResult.failed("telegram", "telegram 通道未启用");
        }
        if (!rateLimiter.acquire()) {
            return DeliveryResult.failed("telegram", "telegram 限流（30/s）");
        }
        String chatId = message.getChatId() == null || message.getChatId().isBlank()
                ? message.getSenderId() : message.getChatId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", message.getContent());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "bot" + botToken + "/sendMessage";

        Exception lastError = null;
        for (int attempt = 1; attempt <= retryConfig.maxRetries(); attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("HTTP " + response.getStatusCode());
                }
                Map<?, ?> parsed = objectMapper.readValue(response.getBody(), Map.class);
                Object result = parsed.get("result");
                String messageId = result instanceof Map
                        ? String.valueOf(((Map<?, ?>) result).get("message_id")) : null;
                return DeliveryResult.accepted("telegram",
                        messageId == null ? null : "telegram:" + messageId);
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
        log.error("Telegram 消息发送失败 chatId={}: {}", chatId,
                lastError == null ? "unknown" : lastError.getMessage());
        return DeliveryResult.failed("telegram",
                lastError == null ? "send failed" : lastError.getMessage());
    }
}
