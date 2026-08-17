package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class FeishuMessageSender {

    private final String feishuBase;
    private final FeishuConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static class TokenCache {
        final String token;
        final long expiryMs;
        TokenCache(String token, long expiryMs) {
            this.token = token;
            this.expiryMs = expiryMs;
        }
    }

    private final AtomicReference<TokenCache> tokenRef = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    @Autowired
    public FeishuMessageSender(FeishuConfig config, RestTemplate restTemplate,
                                ObjectMapper objectMapper) {
        this(config, restTemplate, objectMapper, "https://open.feishu.cn");
    }

    FeishuMessageSender(FeishuConfig config, RestTemplate restTemplate,
                        ObjectMapper objectMapper, String feishuBase) {
        this.config       = config;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.feishuBase   = feishuBase.endsWith("/")
                ? feishuBase.substring(0, feishuBase.length() - 1) : feishuBase;
    }

    public String getTenantAccessToken() {
        TokenCache cache = tokenRef.get();
        if (cache != null && System.currentTimeMillis() < cache.expiryMs - 300_000L) {
            return cache.token;
        }
        return refreshToken();
    }

    private String refreshToken() {
        refreshLock.lock();
        try {
            TokenCache cache = tokenRef.get();
            if (cache != null && System.currentTimeMillis() < cache.expiryMs - 300_000L) {
                return cache.token;
            }
            return doRefreshToken();
        } finally {
            refreshLock.unlock();
        }
    }

    private String doRefreshToken() {
        String url = feishuBase + "/open-apis/auth/v3/tenant_access_token/internal";
        Map<String, String> body = new HashMap<>();
        body.put("app_id",     config.getAppId());
        body.put("app_secret", config.getAppSecret());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ResponseEntity<String> res = restTemplate.exchange(
                        url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                if (res.getStatusCode().is2xxSuccessful()) {
                    Map<?, ?> json = objectMapper.readValue(res.getBody(), Map.class);
                    String token   = (String)  json.get("tenant_access_token");
                    int    expire  = (Integer) json.get("expire");
                    long   expiry  = System.currentTimeMillis() + (expire - 300L) * 1000L;
                    tokenRef.set(new TokenCache(token, expiry));
                    log.info("飞书 tenant_access_token 刷新成功，有效期 {}s", expire);
                    return token;
                }
            } catch (Exception e) {
                log.warn("刷新 tenant_access_token 第 {} 次失败: {}", attempt + 1, e.getMessage());
            }
        }
        throw new RuntimeException("飞书 tenant_access_token 刷新失败（2 次重试后）");
    }

    @Scheduled(fixedDelay = 6_600_000)
    public void scheduledRefresh() {
        if (config.isEnabled()) {
            try { doRefreshToken(); } catch (Exception e) {
                log.error("定时刷新 token 失败", e);
            }
        }
    }

    public String sendText(String chatId, String text) {
        Map<String, Object> content = new HashMap<>();
        content.put("text", text);
        return sendWithRetry(chatId, "text", content);
    }

    public String sendPost(String chatId, Map<String, Object> content) {
        return sendWithRetry(chatId, "post", content);
    }

    public String sendInteractive(String chatId, String cardJson) {
        try {
            Map<?, ?> card = objectMapper.readValue(cardJson, Map.class);
            return sendWithRetry(chatId, "interactive", card);
        } catch (Exception e) {
            log.error("sendInteractive 解析 cardJson 失败，chatId={}", chatId, e);
            return null;
        }
    }

    /** 调用飞书官方撤回消息 API。method/path 已知早期文档版本为
     *  DELETE /open-apis/im/v1/messages/{message_id}——落地前请对照飞书开放平台
     *  当前文档核实一遍，如有出入只需改这里的 HttpMethod/url 拼接。 */
    public void recall(String messageId) {
        String url   = feishuBase + "/open-apis/im/v1/messages/" + messageId;
        String token = getTenantAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));

        ResponseEntity<String> res = restTemplate.exchange(
                url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("飞书撤回 API 返回 " + res.getStatusCode() + ": " + res.getBody());
        }
    }

    /** 给指定消息添加表情回复（TODO-81 群聊表情回应）。
     *  POST /open-apis/im/v1/messages/{message_id}/reactions
     *  需应用具备 im:message 或 im:message.reactions:write_only 权限。
     *  返回表情回复 ID（reaction_id）；失败抛异常由调用方决定降级。 */
    public String sendReaction(String messageId, String emojiType) {
        String url = feishuBase + "/open-apis/im/v1/messages/" + messageId + "/reactions";
        String token = getTenantAccessToken();

        Map<String, String> reactionType = new HashMap<>();
        reactionType.put("emoji_type", emojiType);
        Map<String, Object> body = new HashMap<>();
        body.put("reaction_type", reactionType);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));

        ResponseEntity<String> res = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("飞书添加表情回复 API 返回 " + res.getStatusCode() + ": " + res.getBody());
        }

        try {
            Map<?, ?> json = objectMapper.readValue(res.getBody(), Map.class);
            Map<?, ?> data = (Map<?, ?>) json.get("data");
            return data != null ? (String) data.get("reaction_id") : null;
        } catch (Exception e) {
            log.warn("解析飞书 reaction_id 失败: {}", e.getMessage());
            return null;
        }
    }

    private String sendWithRetry(String chatId, String msgType, Object content) {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try {
                return doSend(chatId, msgType, content);
            } catch (Exception e) {
                lastEx = e;
                log.warn("发送飞书消息第 {} 次失败，chatId={}: {}", i + 1, chatId, e.getMessage());
                if (i < 2) {
                    try { Thread.sleep(1000L << i); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        log.error("发送消息 3 次全部失败，chatId={}，发送 fallback", chatId, lastEx);
        try {
            return doSend(chatId, "text", Collections.singletonMap("text", "网络繁忙，请重试 🙏"));
        } catch (Exception e) {
            log.error("fallback 消息也发送失败，chatId={}", chatId, e);
            return null;
        }
    }

    private String doSend(String chatId, String msgType, Object content) throws Exception {
        // ── 发送前验证（TODO-93 失职自查钩子）────────────────────
        verifyMessageContent(msgType, content);

        String url   = feishuBase + "/open-apis/im/v1/messages?receive_id_type=chat_id";
        String token = getTenantAccessToken();

        Map<String, Object> body = new HashMap<>();
        body.put("receive_id", chatId);
        body.put("msg_type",   msgType);
        body.put("content",    objectMapper.writeValueAsString(content));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));

        ResponseEntity<String> res = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("飞书 API 返回 " + res.getStatusCode() + ": " + res.getBody());
        }

        String messageId = extractMessageId(res.getBody());

        // ── 发送后验证：message_id 缺失时抛异常触发 retry（TODO-93）──
        verifyMessageId(messageId, chatId, msgType);

        return messageId;
    }

    // ── TODO-93 失职自查钩子：发送前内容验证 ──────────────────────
    private static final int MAX_TEXT_LENGTH = 15000;

    private void verifyMessageContent(String msgType, Object content) {
        if (content == null) {
            log.warn("[feishu pre-send] content 为 null");
            return;
        }
        if ("text".equals(msgType) && content instanceof Map) {
            Map<?, ?> c = (Map<?, ?>) content;
            Object textObj = c.get("text");
            if (textObj == null || String.valueOf(textObj).trim().isEmpty()) {
                log.warn("[feishu pre-send] text 消息内容为空");
            } else if (String.valueOf(textObj).length() > MAX_TEXT_LENGTH) {
                log.warn("[feishu pre-send] text 消息过长 ({} > {})，飞书可能截断",
                        String.valueOf(textObj).length(), MAX_TEXT_LENGTH);
            }
        }
        // 验证 content 可序列化
        try {
            objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            log.warn("[feishu pre-send] content JSON 序列化失败: {}", e.getMessage());
        }
    }

    /** 发送后验证 message_id 非空；若为空则抛异常触发 sendWithRetry 的重试机制。*/
    private void verifyMessageId(String messageId, String chatId, String msgType) {
        if (messageId == null || messageId.trim().isEmpty()) {
            String errMsg = String.format(
                    "[feishu post-send] message_id 缺失 (chatId=%s, msgType=%s)，触发重试",
                    chatId, msgType);
            log.warn(errMsg);
            throw new RuntimeException(errMsg);
        }
    }

    private String extractMessageId(String responseBody) {
        try {
            Map<?, ?> json = objectMapper.readValue(responseBody, Map.class);
            Map<?, ?> data = (Map<?, ?>) json.get("data");
            return data != null ? (String) data.get("message_id") : null;
        } catch (Exception e) {
            log.warn("解析飞书 message_id 失败: {}", e.getMessage());
            return null;
        }
    }
}
