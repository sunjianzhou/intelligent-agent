package com.intelligent.agent.web.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class WeComMessageSender {

    private static final String WECOM_API = "https://qyapi.weixin.qq.com";

    private final WeComConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static class TokenCache {
        final String token;
        final long expiryMs;
        TokenCache(String token, long expiryMs) { this.token = token; this.expiryMs = expiryMs; }
    }

    private final AtomicReference<TokenCache> tokenRef = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    @Autowired
    public WeComMessageSender(WeComConfig config, RestTemplate restTemplate,
                               ObjectMapper objectMapper) {
        this.config       = config;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String getAccessToken() {
        TokenCache cache = tokenRef.get();
        if (cache != null && System.currentTimeMillis() < cache.expiryMs - 300_000L) {
            return cache.token;
        }
        refreshLock.lock();
        try {
            cache = tokenRef.get();
            if (cache != null && System.currentTimeMillis() < cache.expiryMs - 300_000L) {
                return cache.token;
            }
            return doRefresh();
        } finally {
            refreshLock.unlock();
        }
    }

    private String doRefresh() {
        String url = WECOM_API + "/cgi-bin/gettoken?corpid=" + config.getCorpId()
                + "&corpsecret=" + config.getSecret();
        try {
            ResponseEntity<String> res = restTemplate.getForEntity(url, String.class);
            Map<?, ?> json   = objectMapper.readValue(res.getBody(), Map.class);
            String token     = (String) json.get("access_token");
            int    expiresIn = (Integer) json.get("expires_in");
            long   expiry    = System.currentTimeMillis() + (long) expiresIn * 1000L;
            tokenRef.set(new TokenCache(token, expiry));
            log.info("企业微信 access_token 刷新成功，有效期 {}s", expiresIn);
            return token;
        } catch (Exception e) {
            log.error("企业微信 access_token 刷新失败: {}", e.getMessage());
            throw new RuntimeException("企业微信 access_token 刷新失败", e);
        }
    }

    /** 发送文本消息给指定用户（userId = WeCom open userid）。*/
    public void sendText(String toUser, String content) {
        String token = getAccessToken();
        String url   = WECOM_API + "/cgi-bin/message/send?access_token=" + token;

        Map<String, Object> text = new HashMap<>();
        text.put("content", content);

        Map<String, Object> body = new HashMap<>();
        body.put("touser",  toUser);
        body.put("msgtype", "text");
        body.put("agentid", config.getAgentId());
        body.put("text",    text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            Map<?, ?> resp = objectMapper.readValue(res.getBody(), Map.class);
            Object errcodeObj = resp.get("errcode");
            int errcode = errcodeObj instanceof Number ? ((Number) errcodeObj).intValue() : -1;
            if (errcode != 0) {
                log.error("企业微信发送失败，errcode={}, errmsg={}", errcode, resp.get("errmsg"));
            } else {
                log.info("企业微信消息发送成功，toUser={}", toUser);
            }
        } catch (Exception e) {
            log.error("企业微信发送消息异常，toUser={}: {}", toUser, e.getMessage());
        }
    }
}
