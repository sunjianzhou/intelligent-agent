package com.intelligent.agent.web.integration.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ComfyUI 集成客户端（Plan 2 / Task 5）：
 * 系统状态 / 提交工作流 / 历史轮询进度（HTTP 轮询降级路径，
 * 与 Python ComfyUIProvider 的降级策略一致）。
 */
@Slf4j
public class ComfyUiClient {

    private final String baseUrl;
    private final boolean enabled;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ComfyUiClient(String baseUrl, boolean enabled, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.enabled = enabled;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Object> systemStats() {
        return getJson("system_stats");
    }

    public String submitPrompt(Map<String, Object> workflow) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", workflow);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "prompt", new HttpEntity<>(body, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("ComfyUI 提交失败: HTTP " + response.getStatusCode());
        }
        Map<?, ?> parsed = parse(response.getBody());
        Object promptId = parsed.get("prompt_id");
        return promptId == null ? null : String.valueOf(promptId);
    }

    public Map<String, Object> history(String promptId) {
        return getJson("history/" + promptId);
    }

    /** 轮询进度：完成返回 1.0；运行中返回 0.5 估算；无记录返回 0。 */
    public double progress(String promptId) {
        try {
            Map<String, Object> history = history(promptId);
            Object entry = history.get(promptId);
            if (!(entry instanceof Map)) {
                return 0.0;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> statusMap = (Map<String, Object>) ((Map<String, Object>) entry)
                    .get("status");
            if (statusMap == null) {
                return 0.0;
            }
            Object completed = statusMap.get("completed");
            Object statusStr = statusMap.get("status_str");
            if (Boolean.TRUE.equals(completed) || "success".equals(statusStr)
                    || "error".equals(statusStr)) {
                return 1.0;
            }
            return 0.5;
        } catch (Exception e) {
            log.warn("ComfyUI 进度查询失败 promptId={}: {}", promptId, e.getMessage());
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + path, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("ComfyUI GET " + path + " 失败: HTTP "
                    + response.getStatusCode());
        }
        return (Map<String, Object>) parse(response.getBody());
    }

    private Map<?, ?> parse(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("ComfyUI 响应解析失败", e);
        }
    }
}
