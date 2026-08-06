package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

/**
 * 统计分析代理端点（转发到 Python Agent /api/analytics/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsProxyController extends AbstractProxyController {

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> addFeedback(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/analytics/feedback", body, req);
    }

    @GetMapping("/stats/{username}")
    public ResponseEntity<Map<String, Object>> analyticsStats(
            @PathVariable String username, HttpServletRequest req) {
        return proxyGet("/api/analytics/stats/" + username, req);
    }

    @GetMapping("/records/{username}")
    public ResponseEntity<Map<String, Object>> analyticsRecords(
            @PathVariable String username,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String rating,
            HttpServletRequest req) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/records/" + username)
                .queryParam("limit", limit);
        if (rating != null) builder.queryParam("rating", rating);
        return proxyGetAbsolute(builder.build().toUriString(), req,
                Collections.<String, Object>emptyMap());
    }

    @GetMapping("/skill-logs/{username}")
    public ResponseEntity<Map<String, Object>> skillLogs(
            @PathVariable String username,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String skill_name,
            HttpServletRequest req) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/skill-logs/" + username)
                .queryParam("limit", limit);
        if (skill_name != null) builder.queryParam("skill_name", skill_name);
        return proxyGetAbsolute(builder.build().toUriString(), req,
                Collections.<String, Object>emptyMap());
    }

    @GetMapping("/skill-stats/{username}")
    public ResponseEntity<Map<String, Object>> skillStats(
            @PathVariable String username, HttpServletRequest req) {
        return proxyGet("/api/analytics/skill-stats/" + username, req);
    }

    @GetMapping("/tool-calls")
    public ResponseEntity<Map<String, Object>> toolCalls(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String tool_name,
            HttpServletRequest req) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/tool-calls")
                .queryParam("limit", limit);
        if (tool_name != null) builder.queryParam("tool_name", tool_name);
        return proxyGetAbsolute(builder.build().toUriString(), req,
                Collections.<String, Object>emptyMap());
    }

    @GetMapping("/tool-stats")
    public ResponseEntity<Map<String, Object>> toolStats(HttpServletRequest req) {
        return proxyGet("/api/analytics/tool-stats", req);
    }
}
