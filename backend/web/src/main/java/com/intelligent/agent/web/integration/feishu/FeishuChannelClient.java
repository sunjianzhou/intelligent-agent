package com.intelligent.agent.web.integration.feishu;

import com.intelligent.agent.web.feishu.FeishuMessageSender;
import com.intelligent.agent.web.im.ChannelMessage;
import com.intelligent.agent.web.im.RetryConfig;
import com.intelligent.agent.web.im.TokenBucket;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import com.intelligent.agent.web.integration.ChannelClient;
import com.intelligent.agent.web.integration.DeliveryResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书通道客户端（Plan 2 / Task 5）：
 * 委托 {@link FeishuMessageSender} 发送，文本 50/s 限流 + 指数退避重试，
 * 并持久化用户 OAuth token（data/feishu_tokens.json）。
 */
@Slf4j
public class FeishuChannelClient implements ChannelClient {

    private final FeishuMessageSender sender;
    private final JsonFileStore store;
    private final boolean enabled;
    private final TokenBucket rateLimiter = new TokenBucket(50, 10);
    private final RetryConfig retryConfig;
    private final SecretCrypto crypto;

    public FeishuChannelClient(FeishuMessageSender sender, Path dataDir, boolean enabled) {
        this(sender, dataDir, enabled, RetryConfig.DEFAULT);
    }

    public FeishuChannelClient(FeishuMessageSender sender, Path dataDir, boolean enabled,
                               RetryConfig retryConfig) {
        this(sender, dataDir, enabled, retryConfig, SecretCrypto.disabled());
    }

    public FeishuChannelClient(FeishuMessageSender sender, Path dataDir, boolean enabled,
                               RetryConfig retryConfig, SecretCrypto crypto) {
        this.sender = sender;
        this.store = new JsonFileStore(dataDir);
        this.enabled = enabled;
        this.retryConfig = retryConfig;
        this.crypto = crypto == null ? SecretCrypto.disabled() : crypto;
    }

    @Override
    public String channelType() {
        return "feishu_im";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public DeliveryResult send(ChannelMessage message) {
        if (!enabled) {
            return DeliveryResult.failed("feishu_im", "feishu 通道未启用");
        }
        if (!rateLimiter.acquire()) {
            return DeliveryResult.failed("feishu_im", "feishu 文本限流（50/s）");
        }
        String chatId = message.getChatId();
        if (chatId == null || chatId.isBlank()) {
            chatId = message.getSenderId();
        }
        Exception lastError = null;
        for (int attempt = 1; attempt <= retryConfig.maxRetries(); attempt++) {
            try {
                String rawId = sender.sendText(chatId, message.getContent());
                return DeliveryResult.accepted("feishu_im",
                        rawId == null ? null : "feishu_im:" + rawId);
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
        log.error("飞书消息发送失败 chatId={}: {}", chatId,
                lastError == null ? "unknown" : lastError.getMessage());
        return DeliveryResult.failed("feishu_im",
                lastError == null ? "send failed" : lastError.getMessage());
    }

    // ── OAuth token 持久化（data/feishu_tokens.json） ─────────

    public void saveUserToken(String userId, String accessToken, String refreshToken, long refreshExpiresAt) {
        Map<String, Object> tokens = tokens();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("access_token", crypto.encrypt(accessToken));
        entry.put("refresh_token", crypto.encrypt(refreshToken));
        entry.put("refresh_expires_at", refreshExpiresAt);
        tokens.put(userId, entry);
        store.write(new String[]{"feishu_tokens.json"}, tokens);
    }

    public Map<String, Object> getUserToken(String userId) {
        Object entry = tokens().get(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> token = entry instanceof Map ? (Map<String, Object>) entry : Map.of();
        Map<String, Object> decrypted = new LinkedHashMap<>(token);
        if (decrypted.get("access_token") != null) {
            decrypted.put("access_token", crypto.decrypt(String.valueOf(decrypted.get("access_token"))));
        }
        if (decrypted.get("refresh_token") != null) {
            decrypted.put("refresh_token", crypto.decrypt(String.valueOf(decrypted.get("refresh_token"))));
        }
        return decrypted;
    }

    private Map<String, Object> tokens() {
        Map<String, Object> tokens = store.read("feishu_tokens.json");
        return tokens == null ? new LinkedHashMap<>() : tokens;
    }
}
