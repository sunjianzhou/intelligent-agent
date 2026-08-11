package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.role.RoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 角色配置端点（本地 {@link RoleService}，JSON 文件持久化）。
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    // ── 激活管理（路径须在 /{roleId} 之前，防止被路径变量优先匹配） ─────────────

    @GetMapping("/activate")
    public ResponseEntity<Map<String, Object>> getActiveRole(HttpServletRequest req) {
        return ok(roleService.getActiveRole(UserContext.userId(req)));
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activateRole(@RequestBody Map<String, Object> body,
                                                            HttpServletRequest req) {
        return guarded(() -> roleService.activateRole(
                UserContext.userId(req), str(body.get("role_id"))));
    }

    @DeleteMapping("/activate")
    public ResponseEntity<Map<String, Object>> deactivateRole(HttpServletRequest req) {
        return ok(roleService.deactivateRole(UserContext.userId(req)));
    }

    // ── 角色 CRUD ──────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> listRoles() {
        return ok(roleService.listRoles());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody Map<String, Object> body) {
        return guarded(() -> roleService.createRole(body));
    }

    @GetMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> getRole(@PathVariable String roleId) {
        return guarded(() -> roleService.getRole(roleId));
    }

    @GetMapping("/{roleId}/card")
    public ResponseEntity<Map<String, Object>> getRoleCard(@PathVariable String roleId) {
        return guarded(() -> roleService.getRoleCard(roleId));
    }

    @PutMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> updateRole(@PathVariable String roleId,
                                                          @RequestBody Map<String, Object> body) {
        return guarded(() -> roleService.updateRole(roleId, body));
    }

    @PatchMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> patchRole(@PathVariable String roleId,
                                                         @RequestBody Map<String, Object> body) {
        return guarded(() -> roleService.patchRole(roleId, body));
    }

    @PostMapping("/{roleId}/memory")
    public ResponseEntity<Map<String, Object>> appendMemory(@PathVariable String roleId,
                                                            @RequestBody Map<String, Object> body) {
        return guarded(() -> roleService.appendMemory(
                roleId, str(body.get("content")), str(body.getOrDefault("type", "commitment"))));
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Map<String, Object>> deleteRole(@PathVariable String roleId) {
        return guarded(() -> roleService.deleteRole(roleId));
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

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
