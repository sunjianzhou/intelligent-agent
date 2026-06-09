package com.intelligent.agent.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.role.RoleConfigDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色配置服务。
 *
 * 当前实现：ConcurrentHashMap 内存存储。
 * ⚠️ 重启后所有角色数据丢失。
 *
 * 持久化扩展点（注释中已标注）：
 *   - 替换 store 为 JPA Repository 或 MongoDB Repository 即可
 *   - 或在 save/update 时写入 JSON 文件（参考 Python 侧 RoleManager）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final ObjectMapper objectMapper;

    /**
     * 内存存储：Map<roleId, RoleConfigDto>
     * ⚠️ 持久化扩展点：替换为数据库 Repository
     */
    private final Map<String, RoleConfigDto> store = new ConcurrentHashMap<>();

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<RoleConfigDto> listRoles() {
        return new ArrayList<>(store.values());
    }

    public Optional<RoleConfigDto> getRole(String roleId) {
        return Optional.ofNullable(store.get(roleId));
    }

    /**
     * 创建角色。roleId 不传时自动生成 UUID。
     */
    public RoleConfigDto createRole(RoleConfigDto dto) {
        if (dto.getRoleId() == null || dto.getRoleId().isBlank()) {
            dto.setRoleId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        String now = LocalDateTime.now().toString();
        dto.setCreatedAt(now);
        dto.setUpdatedAt(now);

        // ⚠️ 持久化扩展点：repository.save(dto)
        store.put(dto.getRoleId(), dto);
        log.info("角色已创建: {}", dto.getRoleId());
        return dto;
    }

    /**
     * 全量更新角色配置（PUT 语义）。
     */
    public Optional<RoleConfigDto> updateRole(String roleId, RoleConfigDto dto) {
        if (!store.containsKey(roleId)) {
            return Optional.empty();
        }
        dto.setRoleId(roleId);
        dto.setUpdatedAt(LocalDateTime.now().toString());

        // 保留原始 createdAt
        String originalCreatedAt = store.get(roleId).getCreatedAt();
        dto.setCreatedAt(originalCreatedAt);

        // ⚠️ 持久化扩展点：repository.save(dto)
        store.put(roleId, dto);
        log.info("角色已更新: {}", roleId);
        return Optional.of(dto);
    }

    /**
     * 局部更新（PATCH 语义）：仅覆盖传入的非 null 字段。
     */
    @SuppressWarnings("unchecked")
    public Optional<RoleConfigDto> patchRole(String roleId, Map<String, Object> patch) {
        RoleConfigDto existing = store.get(roleId);
        if (existing == null) {
            return Optional.empty();
        }
        try {
            // 借助 Jackson 做深层合并：existing → Map → 合并 patch → 还原为 DTO
            Map<String, Object> existingMap = objectMapper.convertValue(existing, Map.class);
            deepMerge(existingMap, patch);
            RoleConfigDto merged = objectMapper.convertValue(existingMap, RoleConfigDto.class);
            merged.setUpdatedAt(LocalDateTime.now().toString());

            // ⚠️ 持久化扩展点：repository.save(merged)
            store.put(roleId, merged);
            log.info("角色局部更新: {}, 字段={}", roleId, patch.keySet());
            return Optional.of(merged);
        } catch (Exception e) {
            log.error("角色局部更新失败: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    public boolean deleteRole(String roleId) {
        // ⚠️ 持久化扩展点：repository.deleteById(roleId)
        return store.remove(roleId) != null;
    }

    // ── 记忆追加 ──────────────────────────────────────────────────────────────

    /**
     * 追加记忆条目（Java 侧仅追加到 commitments 列表；
     * 长期记忆向量写入由 Python Agent 处理）。
     */
    public Optional<RoleConfigDto> appendMemory(String roleId, Map<String, Object> memoryEntry) {
        RoleConfigDto role = store.get(roleId);
        if (role == null) {
            return Optional.empty();
        }
        if (role.getRoleMemory() == null) {
            role.setRoleMemory(new RoleConfigDto.RoleMemoryConfigDto());
        }
        if (role.getRoleMemory().getCommitments() == null) {
            role.getRoleMemory().setCommitments(new ArrayList<>());
        }

        String content = String.valueOf(memoryEntry.getOrDefault("content", ""));
        String type = String.valueOf(memoryEntry.getOrDefault("type", "commitment"));

        if ("commitment".equals(type) && !content.isBlank()) {
            RoleConfigDto.CommitmentDto commitment = new RoleConfigDto.CommitmentDto();
            commitment.setContent(content);
            commitment.setTimestamp(LocalDateTime.now().toString());
            commitment.setStatus("active");
            role.getRoleMemory().getCommitments().add(commitment);
            role.setUpdatedAt(LocalDateTime.now().toString());

            // ⚠️ 持久化扩展点：repository.save(role)
            store.put(roleId, role);
            log.info("承诺追加: roleId={}, content={}", roleId, content);
        }
        return Optional.of(role);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        overlay.forEach((key, value) -> {
            if (value instanceof Map && base.get(key) instanceof Map) {
                deepMerge((Map<String, Object>) base.get(key), (Map<String, Object>) value);
            } else if (value != null) {
                base.put(key, value);
            }
        });
    }
}
