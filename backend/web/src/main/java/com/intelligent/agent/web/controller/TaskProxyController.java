package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.task.TaskService;
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
 * 任务调度端点。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link TaskService}（内存注册表，Task 5 接调度器）；</li>
 *   <li>python 运行时：转发到 Python Agent /api/tasks/*（向后兼容）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
public class TaskProxyController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final String runtimeMode;

    public TaskProxyController(PythonProxyService proxy,
                               ObjectMapper objectMapper,
                               TaskService taskService,
                               @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.runtimeMode = runtimeMode;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> tasksList(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.listTasks(status, limit));
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/tasks/list")
                .queryParam("limit", limit);
        if (status != null) builder.queryParam("status", status);
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("tasks", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGet(builder.build().toUriString(), req, fallback);
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createTask(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.createTask(body));
        }
        return proxyPost("/api/tasks/create", body, req);
    }

    @PatchMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> updateTask(
            @PathVariable String taskId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.updateTask(taskId, body));
        }
        return proxyPatch("/api/tasks/" + taskId, body, req);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(
            @PathVariable String taskId, HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.deleteTask(taskId));
        }
        return proxyDelete("/api/tasks/" + taskId, req);
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(
            @PathVariable String taskId, HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.cancelTask(taskId));
        }
        return proxyPost("/api/tasks/" + taskId + "/cancel", "{}", req);
    }

    @PostMapping("/{taskId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(
            @PathVariable String taskId, HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.executeTask(taskId));
        }
        return proxyPost("/api/tasks/" + taskId + "/execute", "{}", req);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> taskStats(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.stats());
        }
        return proxyGet("/api/tasks/stats", req, Map.of());
    }

    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> taskActions(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(taskService.actions());
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("actions", Collections.emptyList());
        return proxyGet("/api/tasks/actions", req, fallback);
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
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

    private ResponseEntity<Map<String, Object>> proxyPatch(String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch(path, json, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("PATCH {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private ResponseEntity<Map<String, Object>> proxyDelete(String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("DELETE {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private static Map<String, Object> errResponse() {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return err;
    }
}
