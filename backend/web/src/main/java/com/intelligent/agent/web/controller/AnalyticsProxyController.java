package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.analytics.AnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 统计分析端点（本地 {@link AnalyticsService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsProxyController {

    private final AnalyticsService analyticsService;

    public AnalyticsProxyController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> addFeedback(@RequestBody Map<String, Object> body) {
        String username = str(body == null ? null : body.get("username"));
        return ResponseEntity.ok(analyticsService.addFeedback(
                username == null || username.isBlank() ? "default" : username, body));
    }

    @GetMapping("/stats/{username}")
    public ResponseEntity<Map<String, Object>> analyticsStats(@PathVariable String username) {
        return ResponseEntity.ok(analyticsService.feedbackStats(username));
    }

    @GetMapping("/records/{username}")
    public ResponseEntity<Map<String, Object>> analyticsRecords(
            @PathVariable String username,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String rating) {
        return ResponseEntity.ok(analyticsService.feedbackRecords(username, limit, rating));
    }

    @GetMapping("/skill-logs/{username}")
    public ResponseEntity<Map<String, Object>> skillLogs(
            @PathVariable String username,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String skill_name) {
        return ResponseEntity.ok(analyticsService.skillLogs(username, limit, skill_name));
    }

    @GetMapping("/skill-stats/{username}")
    public ResponseEntity<Map<String, Object>> skillStats(@PathVariable String username) {
        return ResponseEntity.ok(analyticsService.skillStats(username));
    }

    @GetMapping("/tool-calls")
    public ResponseEntity<Map<String, Object>> toolCalls(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String tool_name) {
        return ResponseEntity.ok(analyticsService.toolCalls(limit, tool_name));
    }

    @GetMapping("/tool-stats")
    public ResponseEntity<Map<String, Object>> toolStats() {
        return ResponseEntity.ok(analyticsService.toolStats());
    }

    /** R-10：用量/成本统计（按用户 + 月份，含每日趋势与限额余量）。 */
    @GetMapping("/usage/{username}")
    public ResponseEntity<Map<String, Object>> usageStats(
            @PathVariable String username,
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(analyticsService.usageStats(username, month));
    }

    /** R-10：当前月是否已达用量限额。 */
    @GetMapping("/usage-quota/{username}")
    public ResponseEntity<Map<String, Object>> usageQuota(@PathVariable String username) {
        Map<String, Object> stats = analyticsService.usageStats(username, null);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("success", true);
        result.put("exceeded", analyticsService.usageQuotaExceeded(username));
        result.put("monthly_limit_cny", stats.get("monthly_limit_cny"));
        result.put("total_cost_cny", stats.get("total_cost_cny"));
        result.put("month", stats.get("month"));
        return ResponseEntity.ok(result);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
