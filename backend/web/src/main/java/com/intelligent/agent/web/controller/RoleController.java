package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.role.RoleService;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 角色配置端点。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link RoleService}（JSON 文件持久化）；</li>
 *   <li>python 运行时：转发到 Python Agent /api/roles/*（旧路径，向后兼容）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;
    private final String runtimeMode;

    public RoleController(PythonProxyService proxy,
                          ObjectMapper objectMapper,
                          RoleService roleService,
                          @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.roleService = roleService;
        this.runtimeMode = runtimeMode;
    }

    // ── 激活管理（路径须在 /{roleId} 之前，防止被路径变量优先匹配） ─────────────

    @GetMapping("/activate")
    public ResponseEntity<Map<String, Object>> getActiveRole(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(roleService.getActiveRole(userId(req)));
        }
        return forward("GET", "/api/roles/activate", null, req);
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activateRole(@RequestBody Map<String, Object> body,
                                                            HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.activateRole(userId(req), str(body.get("role_id"))));
        }
        return forward("POST", "/api/roles/activate", body, req);
    }

    @DeleteMapping("/activate")
    public ResponseEntity<Map<String, Object>> deactivateRole(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(roleService.deactivateRole(userId(req)));
        }
        return forward("DELETE", "/api/roles/activate", null, req);
    }

    // ── 角色 CRUD ──────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> listRoles(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(roleService.listRoles());
        }
        return forward("GET", "/api/roles", null, req);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.createRole(body));
        }
        return forward("POST", "/api/roles", body, req);
    }

    @GetMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> getRole(@PathVariable String roleId,
                                                       HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.getRole(roleId));
        }
        return forward("GET", "/api/roles/" + roleId, null, req);
    }

    @GetMapping("/{roleId}/card")
    public ResponseEntity<Map<String, Object>> getRoleCard(@PathVariable String roleId,
                                                           HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.getRoleCard(roleId));
        }
        return forward("GET", "/api/roles/" + roleId + "/card", null, req);
    }

    @PutMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> updateRole(@PathVariable String roleId,
                                                          @RequestBody Map<String, Object> body,
                                                          HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.updateRole(roleId, body));
        }
        return forward("PUT", "/api/roles/" + roleId, body, req);
    }

    @PatchMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> patchRole(@PathVariable String roleId,
                                                         @RequestBody Map<String, Object> body,
                                                         HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.patchRole(roleId, body));
        }
        return forward("PATCH", "/api/roles/" + roleId, body, req);
    }

    @PostMapping("/{roleId}/memory")
    public ResponseEntity<Map<String, Object>> appendMemory(@PathVariable String roleId,
                                                            @RequestBody Map<String, Object> body,
                                                            HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.appendMemory(
                    roleId, str(body.get("content")), str(body.getOrDefault("type", "commitment"))));
        }
        return forward("POST", "/api/roles/" + roleId + "/memory", body, req);
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> deleteRole(@PathVariable String roleId,
                                                          HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> roleService.deleteRole(roleId));
        }
        return forward("DELETE", "/api/roles/" + roleId, null, req);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

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

    private static ResponseEntity<Map<String, Object>> guarded(java.util.function.Supplier<Map<String, Object>> action) {
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

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> forward(String method, String path,
                                                        Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res;
            switch (method) {
                case "POST":   res = proxy.post(path, body, userId);   break;
                case "PUT":    res = proxy.put(path, body, userId);    break;
                case "PATCH":  res = proxy.patch(path, objectMapper.writeValueAsString(body), userId); break;
                case "DELETE": res = proxy.delete(path, userId);       break;
                default:       res = proxy.get(path, userId);          break;  // GET
            }
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            return ResponseEntity.status(res.getStatusCode())
                    .body(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("{} {} 失败", method, path, e);
            return errorResponse();
        }
    }

    private static ResponseEntity<Map<String, Object>> errorResponse() {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("message", "角色操作失败");
        return ResponseEntity.internalServerError().body(m);
    }
}
