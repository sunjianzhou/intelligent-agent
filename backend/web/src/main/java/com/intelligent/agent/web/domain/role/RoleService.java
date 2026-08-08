package com.intelligent.agent.web.domain.role;

import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色领域服务（Plan 2 / Task 3）：
 * JSON 文件持久化，与 Python roles_router 响应形状保持一致。
 * <ul>
 *   <li>角色配置 → data/roles/{role_id}.json（全局，非 per-user）</li>
 *   <li>激活状态 → data/user_active_roles.json（per-user）</li>
 * </ul>
 */
@Slf4j
public class RoleService {

    private final JsonFileStore store;

    public RoleService(Path dataDir) {
        this.store = new JsonFileStore(dataDir);
    }

    // ── 激活管理 ──────────────────────────────────────────────

    public Map<String, Object> getActiveRole(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("role_id", activeRoles().get(safe(userId)));
        return result;
    }

    public Map<String, Object> activateRole(String userId, String roleId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (roleId == null || roleId.isBlank() || load(roleId) == null) {
            throw new NotFoundException("角色不存在: " + roleId);
        }
        Map<String, Object> active = activeRoles();
        active.put(safe(userId), roleId);
        store.write(new String[]{"user_active_roles.json"}, active);
        result.put("success", true);
        result.put("role_id", roleId);
        return result;
    }

    public Map<String, Object> deactivateRole(String userId) {
        Map<String, Object> active = activeRoles();
        String old = (String) active.remove(safe(userId));
        store.write(new String[]{"user_active_roles.json"}, active);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("deactivated", old);
        return result;
    }

    // ── 角色 CRUD ─────────────────────────────────────────────

    public Map<String, Object> listRoles() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String roleId : roleIds()) {
            Map<String, Object> role = load(roleId);
            if (role != null) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("role_id", roleId);
                card.put("role_card", role.getOrDefault("role_card", Map.of()));
                cards.add(card);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("roles", cards);
        result.put("count", cards.size());
        return result;
    }

    public Map<String, Object> getRole(String roleId) {
        Map<String, Object> role = load(roleId);
        if (role == null) {
            throw new NotFoundException("角色不存在: " + roleId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("role", role);
        return result;
    }

    public Map<String, Object> getRoleCard(String roleId) {
        Map<String, Object> role = load(roleId);
        if (role == null) {
            throw new NotFoundException("角色不存在: " + roleId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("card", role.getOrDefault("role_card", Map.of()));
        return result;
    }

    public Map<String, Object> createRole(Map<String, Object> body) {
        Map<String, Object> role = normalizeRole(body);
        if (role == null) {
            throw new InvalidRequestException("角色数据格式错误: 需要 role_id 与 role_card.name");
        }
        role.put("created_at", now());
        role.put("updated_at", now());
        store.write(new String[]{"roles", (String) role.get("role_id") + ".json"}, role);
        log.info("角色已创建: {}", role.get("role_id"));
        return successWithRole(role);
    }

    public Map<String, Object> updateRole(String roleId, Map<String, Object> body) {
        Map<String, Object> role = normalizeRole(body);
        if (role == null) {
            throw new InvalidRequestException("角色数据格式错误: 需要 role_card.name");
        }
        role.put("role_id", roleId);
        role.put("updated_at", now());
        store.write(new String[]{"roles", roleId + ".json"}, role);
        return successWithRole(role);
    }

    public Map<String, Object> patchRole(String roleId, Map<String, Object> body) {
        Map<String, Object> existing = load(roleId);
        if (existing == null) {
            throw new NotFoundException("角色不存在: " + roleId);
        }
        deepMerge(existing, body);
        existing.put("role_id", roleId);
        existing.put("updated_at", now());
        store.write(new String[]{"roles", roleId + ".json"}, existing);
        return successWithRole(existing);
    }

    public Map<String, Object> appendMemory(String roleId, String content, String memoryType) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            throw new InvalidRequestException("content 不能为空");
        }
        Map<String, Object> role = load(roleId);
        if (role == null) {
            throw new NotFoundException("角色不存在: " + roleId);
        }
        if ("commitment".equals(memoryType)) {
            Map<String, Object> commitment = new LinkedHashMap<>();
            commitment.put("content", content);
            commitment.put("timestamp", now());
            commitment.put("status", "active");
            Map<String, Object> roleMemory = map(role.get("role_memory"));
            List<Object> commitments = list(roleMemory.get("commitments"));
            commitments.add(commitment);
            roleMemory.put("commitments", commitments);
            role.put("role_memory", roleMemory);
            role.put("updated_at", now());
            store.write(new String[]{"roles", roleId + ".json"}, role);
            result.put("success", true);
            result.put("role_id", roleId);
            return result;
        }
        if ("long_term".equals(memoryType)) {
            // Java 侧长期记忆由 MemoryRepository 承载（Plan 2 Task 1 端口）；
            // 这里把内容写入角色的长期记忆集合字段，后续由记忆服务消费。
            Map<String, Object> roleMemory = map(role.get("role_memory"));
            List<Object> longTerm = list(roleMemory.get("long_term_notes"));
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("content", content);
            note.put("timestamp", now());
            longTerm.add(note);
            roleMemory.put("long_term_notes", longTerm);
            role.put("role_memory", roleMemory);
            role.put("updated_at", now());
            store.write(new String[]{"roles", roleId + ".json"}, role);
            result.put("success", true);
            result.put("role_id", roleId);
            return result;
        }
        result.put("success", false);
        result.put("message", "不支持的 memory type: " + memoryType);
        return result;
    }

    public Map<String, Object> deleteRole(String roleId) {
        boolean deleted = store.delete("roles", roleId + ".json");
        if (!deleted) {
            throw new NotFoundException("角色不存在: " + roleId);
        }
        Map<String, Object> active = activeRoles();
        boolean changed = active.values().removeIf(roleId::equals);
        if (changed) {
            store.write(new String[]{"user_active_roles.json"}, active);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    // ── 内部辅助 ──────────────────────────────────────────────

    private Map<String, Object> load(String roleId) {
        return store.read("roles", roleId + ".json");
    }

    private List<String> roleIds() {
        List<String> ids = new ArrayList<>();
        java.io.File rolesDir = store.baseDir().resolve("roles").toFile();
        var files = rolesDir.listFiles();
        if (files == null) {
            return ids;
        }
        for (java.io.File f : files) {
            String name = f.getName();
            if (name.endsWith(".json")) {
                ids.add(name.substring(0, name.length() - 5));
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private Map<String, Object> activeRoles() {
        Map<String, Object> active = store.read("user_active_roles.json");
        return active == null ? new LinkedHashMap<>() : active;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeRole(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Map<String, Object> role = new LinkedHashMap<>(body);
        String roleId = (String) role.get("role_id");
        Object cardObj = role.get("role_card");
        if (roleId == null || roleId.isBlank() || !(cardObj instanceof Map)) {
            return null;
        }
        Map<String, Object> card = (Map<String, Object>) cardObj;
        Object name = card.get("name");
        if (name == null || String.valueOf(name).isBlank()) {
            return null;
        }
        return role;
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            Object patchValue = entry.getValue();
            Object targetValue = target.get(entry.getKey());
            if (patchValue instanceof Map && targetValue instanceof Map) {
                deepMerge((Map<String, Object>) targetValue, (Map<String, Object>) patchValue);
            } else {
                target.put(entry.getKey(), patchValue);
            }
        }
    }

    private Map<String, Object> successWithRole(Map<String, Object> role) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("role_id", role.get("role_id"));
        result.put("role", role);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String safe(String userId) {
        return JsonFileStore.safe(userId);
    }
}
