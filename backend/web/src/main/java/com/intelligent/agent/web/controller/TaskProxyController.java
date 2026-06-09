package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 任务调度代理端点（转发到 Python Agent /api/tasks/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
public class TaskProxyController {


    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> tasksList(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/tasks/list")
                    .queryParam("limit", limit);
            if (status != null) builder.queryParam("status", status);
            ResponseEntity<String> res = proxy.get(builder.build().toUriString(), true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取任务列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("tasks", new ArrayList<>());
        fallback.put("count", 0);
        return ResponseEntity.ok(fallback);
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/tasks/create", body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("创建任务失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "创建任务失败");
        return ResponseEntity.ok(err);
    }

    @PatchMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> updateTask(
            @PathVariable String taskId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch("/api/tasks/" + taskId, json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("更新任务失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "更新任务失败");
        return ResponseEntity.ok(err);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String taskId,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete("/api/tasks/" + taskId, userId);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("删除任务失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "删除任务失败");
        return ResponseEntity.ok(err);
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable String taskId,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/tasks/" + taskId + "/cancel", "{}", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("取消任务失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @PostMapping("/{taskId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(@PathVariable String taskId,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/tasks/" + taskId + "/execute", "{}", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("立即执行任务失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> taskStats(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/tasks/stats", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取任务统计失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> taskActions(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/tasks/actions", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取动作列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("actions", new ArrayList<>());
        return ResponseEntity.ok(fallback);
    }
}
