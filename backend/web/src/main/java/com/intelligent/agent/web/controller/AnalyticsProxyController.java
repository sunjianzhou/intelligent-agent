package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 统计分析代理端点（转发到 Python Agent /api/analytics/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsProxyController {


    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> addFeedback(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/analytics/feedback", body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("提交反馈失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @GetMapping("/stats/{username}")
    public ResponseEntity<Map<String, Object>> analyticsStats(@PathVariable String username,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/analytics/stats/" + username, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取统计失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @GetMapping("/records/{username}")
    public ResponseEntity<Map<String, Object>> analyticsRecords(
            @PathVariable String username,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String rating,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/records/" + username)
                    .queryParam("limit", limit);
            if (rating != null) builder.queryParam("rating", rating);
            ResponseEntity<String> res = proxy.get(builder.build().toUriString(), true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取记录失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @GetMapping("/skill-logs/{username}")
    public ResponseEntity<Map<String, Object>> skillLogs(
            @PathVariable String username,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String skill_name,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/skill-logs/" + username)
                    .queryParam("limit", limit);
            if (skill_name != null) builder.queryParam("skill_name", skill_name);
            ResponseEntity<String> res = proxy.get(builder.build().toUriString(), true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取 Skill 日志失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @GetMapping("/skill-stats/{username}")
    public ResponseEntity<Map<String, Object>> skillStats(@PathVariable String username,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/analytics/skill-stats/" + username, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取 Skill 统计失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @GetMapping("/tool-calls")
    public ResponseEntity<Map<String, Object>> toolCalls(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String tool_name,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/analytics/tool-calls")
                    .queryParam("limit", limit);
            if (tool_name != null) builder.queryParam("tool_name", tool_name);
            ResponseEntity<String> res = proxy.get(builder.build().toUriString(), true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取工具调用记录失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @GetMapping("/tool-stats")
    public ResponseEntity<Map<String, Object>> toolStats(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/analytics/tool-stats", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取工具统计失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }
}
