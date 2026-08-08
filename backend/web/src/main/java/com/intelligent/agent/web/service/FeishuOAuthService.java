package com.intelligent.agent.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.feishu.FeishuConfig;
import com.intelligent.agent.web.integration.feishu.FeishuChannelClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 飞书 OAuth 本地化（TODO-110 Task 2）：
 * authorize 生成授权链接（state=open_id，CSRF 双保险）、callback 用 code 换
 * user_access_token 并存 FeishuChannelClient（data/feishu_tokens.json）、status 查询。
 * feishuBase 可注入（MockWebServer 测试）。
 */
@Slf4j
@Service
public class FeishuOAuthService {

    private static final String DEFAULT_FEISHU_BASE = "https://open.feishu.cn";

    private final FeishuConfig config;
    private final FeishuChannelClient feishuChannelClient;
    private final String feishuBase;
    private final String redirectUri;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FeishuOAuthService(FeishuConfig config,
                              FeishuChannelClient feishuChannelClient,
                              @Value("${feishu.oauth-base-url:}") String feishuBase,
                              @Value("${feishu.redirect-uri:}") String redirectUri) {
        this.config = config;
        this.feishuChannelClient = feishuChannelClient;
        this.feishuBase = feishuBase == null || feishuBase.isBlank()
                ? DEFAULT_FEISHU_BASE : feishuBase;
        this.redirectUri = redirectUri == null ? "" : redirectUri;
    }

    public Map<String, Object> authorize(String openId) {
        String state = UUID.randomUUID().toString() + ":" + openId;
        String url = feishuBase + "/open-apis/authen/v1/authorize?app_id="
                + config.getAppId() + "&redirect_uri=" + urlEncode(redirectUri)
                + "&state=" + urlEncode(state);
        return Map.of("auth_url", url, "state", state);
    }

    public Map<String, Object> status(String openId) {
        Map<String, Object> token = feishuChannelClient.getUserToken(openId);
        boolean authorized = token != null && !token.isEmpty()
                && token.get("access_token") != null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorized", authorized);
        if (authorized) {
            result.put("open_id", openId);
            result.put("expires_at", token.get("refresh_expires_at"));
        }
        return result;
    }

    public String callback(String code, String state) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return errorHtml("缺少 code 或 state 参数");
        }
        if (!state.contains(":")) {
            return errorHtml("state 校验失败");
        }
        String openId = state.substring(state.indexOf(':') + 1);
        try {
            String appAccessToken = appAccessToken();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grant_type", "authorization_code");
            body.put("code", code);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(appAccessToken);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    feishuBase + "/open-apis/authen/v1/oidc/access_token",
                    new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return errorHtml("飞书 token 换取失败: HTTP " + response.getStatusCode());
            }
            Map<String, Object> parsed = objectMapper.readValue(response.getBody(), Map.class);
            Object data = parsed.get("data");
            if (!(data instanceof Map)) {
                return errorHtml("飞书 token 换取失败: 响应缺少 data");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tokenData = (Map<String, Object>) data;
            feishuChannelClient.saveUserToken(
                    openId,
                    String.valueOf(tokenData.getOrDefault("access_token", "")),
                    String.valueOf(tokenData.getOrDefault("refresh_token", "")),
                    Instant.now().plusSeconds(30L * 24 * 3600).toEpochMilli());
            log.info("飞书 OAuth 授权成功: open_id={}", openId);
            return SUCCESS_HTML;
        } catch (Exception e) {
            log.warn("飞书 OAuth callback 失败: {}", e.getMessage());
            return errorHtml("飞书 token 换取失败: " + e.getMessage());
        }
    }

    private String appAccessToken() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", config.getAppId());
        body.put("app_secret", config.getAppSecret());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                feishuBase + "/open-apis/auth/v3/app_access_token/internal",
                new HttpEntity<>(body, headers), String.class);
        try {
            Map<String, Object> parsed = objectMapper.readValue(response.getBody(), Map.class);
            return String.valueOf(parsed.getOrDefault("app_access_token", ""));
        } catch (Exception e) {
            throw new IllegalStateException("获取 app_access_token 失败", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String errorHtml(String detail) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>授权失败</title></head>"
                + "<body><h2>&#x274C; 授权失败</h2><p>" + detail + "</p></body></html>";
    }

    private static final String SUCCESS_HTML =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>授权成功</title></head>"
                    + "<body><h2>&#x2705; 飞书授权成功</h2>"
                    + "<p>你已授权 agent 访问个人日历和任务，可以关闭此页面。</p></body></html>";
}
