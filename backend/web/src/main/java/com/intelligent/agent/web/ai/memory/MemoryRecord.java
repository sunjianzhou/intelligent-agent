package com.intelligent.agent.web.ai.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆项。过滤字段（userId / roleId / projectId / type）为显式字段，
 * 不依赖 metadata 解析，保证仓库端口可按用户/角色/项目/类型/重要性过滤。
 */
public record MemoryRecord(
        String id,
        String userId,
        String roleId,
        String projectId,
        String type,
        String content,
        Map<String, Object> metadata,
        double importance,
        Instant createdAt,
        Instant updatedAt,
        int accessCount
) {

    public MemoryRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        importance = Math.max(0.0, Math.min(1.0, importance));
    }

    /** 便捷构造：仅 id / userId / content / metadata，importance 默认 0.5。 */
    public MemoryRecord(String id, String userId, String content, Map<String, Object> metadata) {
        this(id, userId, null, null, null, content, metadata, 0.5, Instant.now(), Instant.now(), 0);
    }

    /** 便捷构造：带 importance。 */
    public MemoryRecord(String id, String userId, String content, Map<String, Object> metadata, double importance) {
        this(id, userId, null, null, null, content, metadata, importance, Instant.now(), Instant.now(), 0);
    }

    /** 便捷构造：带 roleId / projectId / type / importance。 */
    public MemoryRecord(String id, String userId, String content,
                        String roleId, String projectId, String type,
                        Map<String, Object> metadata, double importance) {
        this(id, userId, roleId, projectId, type, content, metadata, importance, Instant.now(), Instant.now(), 0);
    }

    public MemoryRecord withAccessCount(int newAccessCount) {
        return new MemoryRecord(id, userId, roleId, projectId, type, content, metadata,
                importance, createdAt, Instant.now(), newAccessCount);
    }

    public MemoryRecord withImportance(double newImportance) {
        return new MemoryRecord(id, userId, roleId, projectId, type, content, metadata,
                newImportance, createdAt, updatedAt, accessCount);
    }
}
