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
 * 项目规格代理端点（转发到 Python Agent /api/project/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/project")
public class ProjectProxyController {

    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @PutMapping("/spec")
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
