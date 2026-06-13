package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 任务调度代理端点（转发到 Python Agent /api/tasks/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
public class TaskProxyController extends AbstractProxyController {

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> tasksList(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/tasks/list")
                .queryParam("limit", limit);
        if (status != null) builder.queryParam("status", status);
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("tasks", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGetAbsolute(builder.build().toUriString(), req, fallback);
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createTask(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/tasks/create", body, req);
    }

    @PatchMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> updateTask(
            @PathVariable String taskId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return proxyPatch("/api/tasks/" + taskId, body, req);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(
            @PathVariable String taskId, HttpServletRequest req) {
        return proxyDelete("/api/tasks/" + taskId, req);
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(
            @PathVariable String taskId, HttpServletRequest req) {
        return proxyPost("/api/tasks/" + taskId + "/cancel", "{}", req);
    }

    @PostMapping("/{taskId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(
            @PathVariable String taskId, HttpServletRequest req) {
        return proxyPost("/api/tasks/" + taskId + "/execute", "{}", req);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> taskStats(HttpServletRequest req) {
        return proxyGet("/api/tasks/stats", req);
    }

    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> taskActions(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("actions", Collections.emptyList());
        return proxyGet("/api/tasks/actions", req, fallback);
    }
}
