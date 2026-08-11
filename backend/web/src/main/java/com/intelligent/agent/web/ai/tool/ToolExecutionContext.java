package com.intelligent.agent.web.ai.tool;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次请求的工具执行上下文：用户 / 角色 / 轮次计数。
 * 实例按请求创建，不放入任何单例共享字段。
 */
public final class ToolExecutionContext {

    private final String userId;
    private final String role;
    private final AtomicInteger round = new AtomicInteger(0);

    private ToolExecutionContext(String userId, String role) {
        this.userId = userId == null ? "" : userId;
        this.role = role == null || role.isBlank() ? "user" : role;
    }

    public static ToolExecutionContext of(String userId, String role) {
        return new ToolExecutionContext(userId, role);
    }

    public String userId() {
        return userId;
    }

    public String role() {
        return role;
    }

    public int currentRound() {
        return round.get();
    }

    public int nextRound() {
        return round.incrementAndGet();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolExecutionContext other)) {
            return false;
        }
        return userId.equals(other.userId) && role.equals(other.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, role);
    }
}
