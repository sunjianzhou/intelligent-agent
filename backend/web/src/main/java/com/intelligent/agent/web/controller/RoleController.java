package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.dto.response.ApiResponse;
import com.intelligent.agent.web.dto.role.RoleConfigDto;
import com.intelligent.agent.web.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 角色配置 REST API。
 *
 * 所有数据存 RoleService 内存 Map（⚠️ 重启后丢失）。
 * 鉴权由上游 JwtAuthFilter 统一处理，此处不重复校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** GET /api/roles — 获取所有角色列表 */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RoleConfigDto>>> listRoles() {
        List<RoleConfigDto> roles = roleService.listRoles();
        return ResponseEntity.ok(ApiResponse.success(roles));
    }

    /** GET /api/roles/{roleId} — 获取单个角色完整配置 */
    @GetMapping("/{roleId}")
    public ResponseEntity<ApiResponse<RoleConfigDto>> getRole(@PathVariable String roleId) {
        return roleService.getRole(roleId)
                .map(role -> ResponseEntity.ok(ApiResponse.success(role)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("角色不存在: " + roleId)));
    }

    /** GET /api/roles/{roleId}/card — 仅返回 RoleCard（前端展示用，响应轻量） */
    @GetMapping("/{roleId}/card")
    public ResponseEntity<ApiResponse<RoleConfigDto.RoleCardDto>> getRoleCard(
            @PathVariable String roleId) {
        return roleService.getRole(roleId)
                .map(role -> ResponseEntity.ok(ApiResponse.success(role.getRoleCard())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("角色不存在: " + roleId)));
    }

    /** POST /api/roles — 创建角色 */
    @PostMapping
    public ResponseEntity<ApiResponse<RoleConfigDto>> createRole(
            @RequestBody RoleConfigDto dto) {
        if (dto.getRoleCard() == null || dto.getRoleCard().getName() == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("roleCard.name 为必填项"));
        }
        RoleConfigDto created = roleService.createRole(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("角色已创建", created));
    }

    /** PUT /api/roles/{roleId} — 全量更新角色配置 */
    @PutMapping("/{roleId}")
    public ResponseEntity<ApiResponse<RoleConfigDto>> updateRole(
            @PathVariable String roleId,
            @RequestBody RoleConfigDto dto) {
        return roleService.updateRole(roleId, dto)
                .map(updated -> ResponseEntity.ok(ApiResponse.success("角色已更新", updated)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("角色不存在: " + roleId)));
    }

    /**
     * PATCH /api/roles/{roleId} — 局部更新角色配置。
     * 请求体只需包含要修改的字段，未传字段保持原值。
     */
    @PatchMapping("/{roleId}")
    public ResponseEntity<ApiResponse<RoleConfigDto>> patchRole(
            @PathVariable String roleId,
            @RequestBody Map<String, Object> patch) {
        return roleService.patchRole(roleId, patch)
                .map(updated -> ResponseEntity.ok(ApiResponse.success("角色已更新", updated)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("角色不存在: " + roleId)));
    }

    /**
     * POST /api/roles/{roleId}/memory — 追加记忆条目。
     * 请求体：{ "type": "commitment", "content": "..." }
     * type=commitment → 追加到 commitments 列表并持久化
     * type=long_term  → Java 仅记录元数据，实际向量写入由 Python Agent 处理
     */
    @PostMapping("/{roleId}/memory")
    public ResponseEntity<ApiResponse<RoleConfigDto>> appendMemory(
            @PathVariable String roleId,
            @RequestBody Map<String, Object> body) {
        if (body.get("content") == null || body.get("content").toString().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("content 不能为空"));
        }
        return roleService.appendMemory(roleId, body)
                .map(updated -> ResponseEntity.ok(ApiResponse.success("记忆已追加", updated)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("角色不存在: " + roleId)));
    }

    /** DELETE /api/roles/{roleId} — 删除角色 */
    @DeleteMapping("/{roleId}")
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable String roleId) {
        boolean deleted = roleService.deleteRole(roleId);
        if (deleted) {
            return ResponseEntity.ok(ApiResponse.success("角色已删除", null));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("角色不存在: " + roleId));
    }
}
