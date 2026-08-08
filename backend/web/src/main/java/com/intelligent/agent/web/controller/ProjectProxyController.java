package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.project.ProjectService;
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
 * 项目端点。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link ProjectService}；</li>
 *   <li>python 运行时：转发到 Python Agent /api/project/* 与 /api/projects/*。</li>
 * </ul>
 */
@Slf4j
@RestController
public class ProjectProxyController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final String runtimeMode;

    public ProjectProxyController(PythonProxyService proxy,
                                  ObjectMapper objectMapper,
                                  ProjectService projectService,
                                  @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.runtimeMode = runtimeMode;
    }

    // ── 项目 CRUD (/api/projects) ──────────────────────────────────────────────

    @GetMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> listProjects(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(projectService.listProjects(userId(req)));
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("projects", Collections.emptyList());
        return proxyGet(proxy.getBaseUrl() + "/api/projects", req, fallback);
    }

    @PostMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> createProject(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> projectService.createProject(userId(req), body));
        }
        return proxyPost("/api/projects", body, req);
    }

    @GetMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> getProject(
            @PathVariable String projectId, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> projectService.getProject(userId(req), projectId));
        }
        return proxyGet("/api/projects/" + projectId, req, Map.of("success", false, "message", "获取项目失败"));
    }

    @PutMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> updateProject(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> projectService.updateProject(userId(req), projectId, body));
        }
        return proxyPut("/api/projects/" + projectId, body, req);
    }

    @DeleteMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> deleteProject(
            @PathVariable String projectId, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> projectService.deleteProject(userId(req), projectId));
        }
        return proxyDelete("/api/projects/" + projectId, req);
    }

    // ── 旧 /api/project/* 端点（规格/上下文/任务分解）─────────────────────────

    @PutMapping("/api/project/spec")
    public ResponseEntity<Map<String, Object>> putProjectSpec(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> projectService.putSpec(userId(req),
                    str(body.get("project_id")),
                    str(body.get("content")),
                    body.get("version") == null ? 1 : ((Number) body.get("version")).intValue()));
        }
        return proxyPut("/api/project/spec", body, req);
    }

    @GetMapping("/api/project/spec")
    public ResponseEntity<Map<String, Object>> getProjectSpec(
            @RequestParam String project_id, HttpServletRequest req) {
        if (localRuntime()) {
            return ok(projectService.getSpec(userId(req), project_id));
        }
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/project/spec")
                .queryParam("project_id", project_id)
                .build().toUriString();
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("project_id", project_id);
        fallback.put("content", "");
        fallback.put("version", 0);
        return proxyGet(url, req, fallback);
    }

    @PostMapping("/api/project/context/extract")
    public ResponseEntity<Map<String, Object>> extractContext(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> projectService.extractContext(userId(req), str(body.get("project_id"))));
        }
        return proxyPost("/api/project/context/extract", body, req);
    }

    @GetMapping("/api/project/context")
    public ResponseEntity<Map<String, Object>> getContext(
            @RequestParam String project_id,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "5") int limit,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ok(projectService.getContext(userId(req), project_id, query, limit));
        }
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/project/context")
                .queryParam("project_id", project_id)
                .queryParam("query", query)
                .queryParam("limit", limit)
                .build().toUriString();
        return proxyGet(url, req, Collections.singletonMap("context", ""));
    }

    @PostMapping("/api/project/tasks/decompose")
    public ResponseEntity<Map<String, Object>> decomposeTasks(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> projectService.decomposeTasks(
                    userId(req), str(body.get("project_id")), str(body.get("task_description"))));
        }
        return proxyPost("/api/project/tasks/decompose", body, req);
    }

    @GetMapping("/api/project/tasks")
    public ResponseEntity<Map<String, Object>> getProjectTasks(
            @RequestParam String project_id, HttpServletRequest req) {
        if (localRuntime()) {
            return ok(projectService.getProjectTasks(userId(req), project_id));
        }
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/project/tasks")
                .queryParam("project_id", project_id)
                .build().toUriString();
        return proxyGet(url, req,
                Collections.singletonMap("tasks", Collections.emptyList()));
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private String userId(HttpServletRequest req) {
        if (proxy != null) {
            String userId = proxy.extractUserIdFromRequest(req);
            if (userId != null) {
                return userId;
            }
        }
        return "default";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> guarded(
            java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (NotFoundException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(error(e.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }

    // ── Python 代理回退 ───────────────────────────────────────

    private ResponseEntity<Map<String, Object>> proxyGet(String path, HttpServletRequest req,
                                                         Map<String, Object> fallback) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
            if (res.getStatusCode().value() == 404) {
                return ResponseEntity.notFound().build();
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

    private ResponseEntity<Map<String, Object>> proxyPut(String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.put(path, body, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("PUT {} 失败", path, e);
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
            if (res.getStatusCode().value() == 404) {
                return ResponseEntity.notFound().build();
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
