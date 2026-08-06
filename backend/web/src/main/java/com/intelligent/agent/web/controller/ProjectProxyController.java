package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 项目代理端点（转发到 Python Agent /api/project/* 和 /api/projects/*）。
 */
@Slf4j
@RestController
public class ProjectProxyController extends AbstractProxyController {

    // ── 项目 CRUD (/api/projects) ──────────────────────────────────────────────

    @GetMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> listProjects(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("projects", Collections.emptyList());
        return proxyGetAbsolute(proxy.getBaseUrl() + "/api/projects", req, fallback);
    }

    @PostMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> createProject(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/projects", body, req);
    }

    @GetMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> getProject(
            @PathVariable String projectId, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(
                    proxy.getBaseUrl() + "/api/projects/" + projectId, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            if (res.getStatusCode().value() == 404)
                return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("获取项目失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "获取项目失败");
        return ResponseEntity.ok(err);
    }

    @PutMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> updateProject(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return proxyPut("/api/projects/" + projectId, body, req);
    }

    @DeleteMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> deleteProject(
            @PathVariable String projectId, HttpServletRequest req) {
        return proxyDelete("/api/projects/" + projectId, req);
    }

    // ── 旧 /api/project/* 端点（规格/上下文/任务分解）─────────────────────────

    @PutMapping("/api/project/spec")
    public ResponseEntity<Map<String, Object>> putProjectSpec(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPut("/api/project/spec", body, req);
    }

    @GetMapping("/spec")
    public ResponseEntity<Map<String, Object>> getProjectSpec(
            @RequestParam String project_id, HttpServletRequest req) {
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/project/spec")
                .queryParam("project_id", project_id)
                .build().toUriString();
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("project_id", project_id);
        fallback.put("content", "");
        fallback.put("version", 0);
        return proxyGetAbsolute(url, req, fallback);
    }

    @PostMapping("/context/extract")
    public ResponseEntity<Map<String, Object>> extractContext(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/project/context/extract", body, req);
    }

    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> getContext(
            @RequestParam String project_id,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "5") int limit,
            HttpServletRequest req) {
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/project/context")
                .queryParam("project_id", project_id)
                .queryParam("query", query)
                .queryParam("limit", limit)
                .build().toUriString();
        return proxyGetAbsolute(url, req, Collections.singletonMap("context", ""));
    }

    @PostMapping("/tasks/decompose")
    public ResponseEntity<Map<String, Object>> decomposeTasks(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/project/tasks/decompose", body, req);
    }

    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getProjectTasks(
            @RequestParam String project_id, HttpServletRequest req) {
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/project/tasks")
                .queryParam("project_id", project_id)
                .build().toUriString();
        return proxyGetAbsolute(url, req,
                Collections.singletonMap("tasks", Collections.emptyList()));
    }
}
