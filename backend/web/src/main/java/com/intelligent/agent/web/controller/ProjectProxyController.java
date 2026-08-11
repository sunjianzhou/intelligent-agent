package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.project.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 项目端点（本地 {@link ProjectService}）。
 */
@Slf4j
@RestController
public class ProjectProxyController {

    private final ProjectService projectService;

    public ProjectProxyController(ProjectService projectService) {
        this.projectService = projectService;
    }

    // ── 项目 CRUD (/api/projects) ──────────────────────────────────────────────

    @GetMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> listProjects(HttpServletRequest req) {
        return ok(projectService.listProjects(UserContext.userId(req)));
    }

    @PostMapping("/api/projects")
    public ResponseEntity<Map<String, Object>> createProject(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return guarded(() -> projectService.createProject(UserContext.userId(req), body));
    }

    @GetMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> getProject(
            @PathVariable String projectId, HttpServletRequest req) {
        return guarded(() -> projectService.getProject(UserContext.userId(req), projectId));
    }

    @PutMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> updateProject(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return guarded(() -> projectService.updateProject(
                UserContext.userId(req), projectId, body));
    }

    @DeleteMapping("/api/projects/{projectId}")
    public ResponseEntity<Map<String, Object>> deleteProject(
            @PathVariable String projectId, HttpServletRequest req) {
        return guarded(() -> projectService.deleteProject(UserContext.userId(req), projectId));
    }

    // ── 旧 /api/project/* 端点（规格/上下文/任务分解）─────────────────────────

    @PutMapping("/api/project/spec")
    public ResponseEntity<Map<String, Object>> putProjectSpec(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return guarded(() -> projectService.putSpec(UserContext.userId(req),
                str(body.get("project_id")),
                str(body.get("content")),
                body.get("version") == null ? 1 : ((Number) body.get("version")).intValue()));
    }

    @GetMapping("/api/project/spec")
    public ResponseEntity<Map<String, Object>> getProjectSpec(
            @RequestParam String project_id, HttpServletRequest req) {
        return ok(projectService.getSpec(UserContext.userId(req), project_id));
    }

    @PostMapping("/api/project/context/extract")
    public ResponseEntity<Map<String, Object>> extractContext(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return guarded(() -> projectService.extractContext(
                UserContext.userId(req), str(body.get("project_id"))));
    }

    @GetMapping("/api/project/context")
    public ResponseEntity<Map<String, Object>> getContext(
            @RequestParam String project_id,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "5") int limit,
            HttpServletRequest req) {
        return ok(projectService.getContext(
                UserContext.userId(req), project_id, query, limit));
    }

    @PostMapping("/api/project/tasks/decompose")
    public ResponseEntity<Map<String, Object>> decomposeTasks(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return guarded(() -> projectService.decomposeTasks(
                UserContext.userId(req), str(body.get("project_id")),
                str(body.get("task_description"))));
    }

    @GetMapping("/api/project/tasks")
    public ResponseEntity<Map<String, Object>> getProjectTasks(
            @RequestParam String project_id, HttpServletRequest req) {
        return ok(projectService.getProjectTasks(UserContext.userId(req), project_id));
    }

    // ── 辅助 ──────────────────────────────────────────────────

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
}
