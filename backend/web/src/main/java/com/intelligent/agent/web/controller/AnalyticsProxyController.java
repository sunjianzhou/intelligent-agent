package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.analytics.AnalyticsService;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 统计分析端点。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link AnalyticsService}；</li>
 *   <li>python 运行时：转发到 Python Agent /api/analytics/*。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsProxyController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final AnalyticsService analyticsService;
    private final String runtimeMode;

    public AnalyticsProxyController(PythonProxyService proxy,
                                    ObjectMapper objectMapper,
                                    AnalyticsService analyticsService,
                                    @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.analyticsService = analyticsService;
        this.runtimeMode = runtimeMode;
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> addFeedback(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            String username = str(body == null ? null : body.get("username"));
            return ResponseEntity.ok(analyticsService.addFeedback(
                    username == null || username.isBlank() ? "default" : username, body));
        }
        return proxyPost("/api/analytics/feedback", body, req);
    }

    @GetMapping("/stats/{username}")
    public ResponseEntity<Map<String, Object>> analyticsStats(
            @PathVariable String username, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(analyticsService.feedbackStats(username));
        }
        return proxyGet("/api/analytics/stats/" + username, req, Map.of());
    }

    @GetMapping("/records/{username}")
    public ResponseEntity<Map<String, Object>> analyticsRecords(
            @PathVariable String username,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String rating,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(analyticsService.feedbackRecords(username, limit, rating));
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/records/" + username)
                .queryParam("limit", limit);
        if (rating != null) builder.queryParam("rating", rating);
        return proxyGet(builder.build().toUriString(), req, Collections.emptyMap());
    }

    @GetMapping("/skill-logs/{username}")
    public ResponseEntity<Map<String, Object>> skillLogs(
            @PathVariable String username,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String skill_name,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(analyticsService.skillLogs(username, limit, skill_name));
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/skill-logs/" + username)
                .queryParam("limit", limit);
        if (skill_name != null) builder.queryParam("skill_name", skill_name);
        return proxyGet(builder.build().toUriString(), req, Collections.emptyMap());
    }

    @GetMapping("/skill-stats/{username}")
    public ResponseEntity<Map<String, Object>> skillStats(
            @PathVariable String username, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(analyticsService.skillStats(username));
        }
        return proxyGet("/api/analytics/skill-stats/" + username, req, Map.of());
    }

    @GetMapping("/tool-calls")
    public ResponseEntity<Map<String, Object>> toolCalls(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String tool_name,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(analyticsService.toolCalls(limit, tool_name));
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/tool-calls")
                .queryParam("limit", limit);
        if (tool_name != null) builder.queryParam("tool_name", tool_name);
        return proxyGet(builder.build().toUriString(), req, Collections.emptyMap());
    }

    @GetMapping("/tool-stats")
    public ResponseEntity<Map<String, Object>> toolStats(HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(analyticsService.toolStats());
        }
        return proxyGet("/api/analytics/tool-stats", req, Map.of());
    }

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private ResponseEntity<Map<String, Object>> proxyGet(String path, HttpServletRequest req,
                                                         Map<String, Object> fallback) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("GET {} 失败", path, e);
        }
        return ResponseEntity.ok(new HashMap<>(fallback));
    }

    private ResponseEntity<Map<String, Object>> proxyPost(String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post(path, body, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("POST {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private static Map<String, Object> errResponse() {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return err;
    }
}
