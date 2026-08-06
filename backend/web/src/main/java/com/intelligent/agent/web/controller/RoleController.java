package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 角色配置代理端点 — 所有请求转发到 Python Agent /api/roles/*。
 * Python Agent 使用文件系统持久化角色 JSON，不依赖 Java 内存存储。
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    // ── 激活管理（路径须在 /{roleId} 之前，防止被路径变量优先匹配） ─────────────

    @GetMapping("/activate")
    public ResponseEntity<Map<String, Object>> getActiveRole(HttpServletRequest req) {
        return forward("GET", "/api/roles/activate", null, req);
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activateRole(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest req) {
        return forward("POST", "/api/roles/activate", body, req);
    }

    @DeleteMapping("/activate")
    public ResponseEntity<Map<String, Object>> deactivateRole(HttpServletRequest req) {
        return forward("DELETE", "/api/roles/activate", null, req);
    }

    // ── 角色 CRUD ──────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> listRoles(HttpServletRequest req) {
        return forward("GET", "/api/roles", null, req);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest req) {
        return forward("POST", "/api/roles", body, req);
    }

    @GetMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> getRole(@PathVariable String roleId,
                                                        HttpServletRequest req) {
        return forward("GET", "/api/roles/" + roleId, null, req);
    }

    @GetMapping("/{roleId}/card")
    public ResponseEntity<Map<String, Object>> getRoleCard(@PathVariable String roleId,
                                                            HttpServletRequest req) {
        return forward("GET", "/api/roles/" + roleId + "/card", null, req);
    }

    @PutMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> updateRole(@PathVariable String roleId,
                                                           @RequestBody Map<String, Object> body,
                                                           HttpServletRequest req) {
        return forward("PUT", "/api/roles/" + roleId, body, req);
    }

    @PatchMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> patchRole(@PathVariable String roleId,
                                                          @RequestBody Map<String, Object> body,
                                                          HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch("/api/roles/" + roleId, json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            return ResponseEntity.status(res.getStatusCode())
                    .body(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("PATCH /api/roles/{} 失败", roleId, e);
            return error("局部更新角色失败");
        }
    }

    @PostMapping("/{roleId}/memory")
    public ResponseEntity<Map<String, Object>> appendMemory(@PathVariable String roleId,
                                                             @RequestBody Map<String, Object> body,
                                                             HttpServletRequest req) {
        return forward("POST", "/api/roles/" + roleId + "/memory", body, req);
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> deleteRole(@PathVariable String roleId,
                                                           HttpServletRequest req) {
        return forward("DELETE", "/api/roles/" + roleId, null, req);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> forward(String method, String path,
                                                         Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res;
            switch (method) {
                case "POST":   res = proxy.post(path, body, userId);   break;
                case "PUT":    res = proxy.put(path, body, userId);    break;
                case "DELETE": res = proxy.delete(path, userId);       break;
                default:       res = proxy.get(path, userId);          break;  // GET
            }
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            return ResponseEntity.status(res.getStatusCode())
                    .body(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("{} {} 失败", method, path, e);
            return error(method + " " + path + " 失败");
        }
    }

    private ResponseEntity<Map<String, Object>> error(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("message", msg);
        return ResponseEntity.internalServerError().body(m);
    }
}
