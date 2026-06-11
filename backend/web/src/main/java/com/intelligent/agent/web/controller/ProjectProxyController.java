package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 项目代理端点（转发到 Python Agent /api/project/* 和 /api/projects/*）。
 */
@Slf4j
@RestController
public class ProjectProxyController {

    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    // ── 项目 CRUD (/api/projects) ──────────────────────────────────────────────

    @GetMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> listProjects(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(proxy.getBaseUrl() + "/api/projects", true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取项目列表失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false); err.put("projects", new java.util.ArrayList<>());
        return ResponseEntity.ok(err);
    }

    @PostMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> createProject(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.post("/api/projects", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("创建项目失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false); err.put("message", "创建项目失败");
        return ResponseEntity.ok(err);
    }

    @GetMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> getProject(@PathVariable String projectId,
            HttpServletRequest req) {
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
        err.put("success", false); err.put("message", "获取项目失败");
        return ResponseEntity.ok(err);
    }

    @PutMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> updateProject(@PathVariable String projectId,
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.put("/api/projects/" + projectId, json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("更新项目失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false); err.put("message", "更新项目失败");
        return ResponseEntity.ok(err);
    }

    @DeleteMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> deleteProject(@PathVariable String projectId,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete("/api/projects/" + projectId, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("删除项目失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false); err.put("message", "删除项目失败");
        return ResponseEntity.ok(err);
    }

    // ── 旧 /api/project/* 端点（规格/上下文/任务分解）─────────────────────────

    @PutMapping("/api/project/spec")
    public ResponseEntity<Map<String, Object>> putProjectSpec(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.put("/api/project/spec", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("保存项目规格失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "保存项目规格失败");
        return ResponseEntity.ok(err);
    }

    @GetMapping("/spec")
    public ResponseEntity<Map<String, Object>> getProjectSpec(@RequestParam String project_id,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/project/spec")
                    .queryParam("project_id", project_id)
                    .build().toUriString();
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取项目规格失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("project_id", project_id);
        fallback.put("content", "");
        fallback.put("version", 0);
        return ResponseEntity.ok(fallback);
    }

    @PostMapping("/context/extract")
    public ResponseEntity<Map<String, Object>> extractContext(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.post("/api/project/context/extract", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("提取项目上下文失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "提取失败");
        return ResponseEntity.ok(err);
    }

    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> getContext(
            @RequestParam String project_id,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "5") int limit,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/project/context")
                    .queryParam("project_id", project_id)
                    .queryParam("query", query)
                    .queryParam("limit", limit)
                    .build().toUriString();
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取项目上下文失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("context", "");
        return ResponseEntity.ok(fallback);
    }

    @PostMapping("/tasks/decompose")
    public ResponseEntity<Map<String, Object>> decomposeTasks(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.post("/api/project/tasks/decompose", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("任务分解失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "任务分解失败");
        return ResponseEntity.ok(err);
    }

    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getProjectTasks(@RequestParam String project_id,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/project/tasks")
                    .queryParam("project_id", project_id)
                    .build().toUriString();
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取项目任务失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("tasks", new java.util.ArrayList<>());
        return ResponseEntity.ok(fallback);
    }
}
