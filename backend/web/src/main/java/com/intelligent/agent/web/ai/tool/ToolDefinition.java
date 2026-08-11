package com.intelligent.agent.web.ai.tool;

import java.time.Duration;
import java.util.Objects;

/**
 * 工具元数据定义。
 *
 * @param name         工具名
 * @param description  工具描述（注入 LLM 提示词）
 * @param readOnly     只读工具（安全约束，拒绝副作用工具时使用）
 * @param requiredRole 执行所需角色；null = 不限制
 * @param timeout      单次执行超时；null = 不限制
 */
public record ToolDefinition(
        String name,
        String description,
        boolean readOnly,
        String requiredRole,
        Duration timeout) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
    }
}
