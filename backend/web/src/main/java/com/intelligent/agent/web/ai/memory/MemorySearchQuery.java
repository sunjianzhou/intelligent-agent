package com.intelligent.agent.web.ai.memory;

/**
 * 作用域检索条件。userId 必填（用户隔离底线）；
 * roleId / projectId / type / minImportance 均为可选过滤。
 */
public record MemorySearchQuery(
        String userId,
        String roleId,
        String projectId,
        String type,
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

        public Builder minImportance(double minImportance) {
            this.minImportance = minImportance;
            return this;
        }

        public MemorySearchQuery build() {
            return new MemorySearchQuery(userId, roleId, projectId, type, minImportance, text, limit);
        }
    }
}
