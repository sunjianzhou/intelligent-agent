package com.intelligent.agent.web.ai.memory;

import java.util.Set;

/**
 * 作用域检索条件。userId 必填（用户隔离底线）；
 * roleId / projectId / type / excludedTypes / minImportance 均为可选过滤。
 */
public record MemorySearchQuery(
        String userId,
        String roleId,
        String projectId,
        String type,
        Set<String> excludedTypes,
        double minImportance,
        String text,
        int limit
) {

    public MemorySearchQuery {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (limit <= 0) {
            limit = 5;
        }
        excludedTypes = excludedTypes == null || excludedTypes.isEmpty()
                ? null : Set.copyOf(excludedTypes);
    }

    public static MemorySearchQuery of(String userId, String text, int limit) {
        return builder(userId, text, limit).build();
    }

    public static Builder builder(String userId, String text, int limit) {
        return new Builder(userId, text, limit);
    }

    public static final class Builder {
        private final String userId;
        private final String text;
        private final int limit;
        private String roleId;
        private String projectId;
        private String type;
        private Set<String> excludedTypes;
        private double minImportance;

        private Builder(String userId, String text, int limit) {
            this.userId = userId;
            this.text = text;
            this.limit = limit;
        }

        public Builder roleId(String roleId) {
            this.roleId = roleId;
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /** 排除指定类型（G5 分层检索用，如 semantic 排除 summary）。 */
        public Builder excludeTypes(Set<String> excludedTypes) {
            this.excludedTypes = excludedTypes;
            return this;
        }

        public Builder minImportance(double minImportance) {
            this.minImportance = minImportance;
            return this;
        }

        public MemorySearchQuery build() {
            return new MemorySearchQuery(userId, roleId, projectId, type,
                    excludedTypes, minImportance, text, limit);
        }
    }
}
