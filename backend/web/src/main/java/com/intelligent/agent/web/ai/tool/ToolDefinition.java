package com.intelligent.agent.web.ai.tool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具元数据定义。
 *
 * @param name         工具名
 * @param description  工具描述（注入 LLM 提示词）
 * @param readOnly     只读工具（安全约束，拒绝副作用工具时使用）
 * @param requiredRole 执行所需角色；null = 不限制
 * @param timeout      单次执行超时；null = 不限制
 * @param parameters  JSON Schema 参数声明（原生工具调用 tools 字段用）；null = 空对象 schema
 * @param approvalRequired 需要用户审批（HITL 审批门，G6）；默认 false
 */
public record ToolDefinition(
        String name,
        String description,
        boolean readOnly,
        String requiredRole,
        Duration timeout,
        Map<String, Object> parameters,
        boolean approvalRequired) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        parameters = parameters == null ? emptyObjectSchema() : parameters;
    }

    /** 无参数 schema 的便捷构造（保持旧调用点兼容，默认空对象 schema）。 */
    public ToolDefinition(String name, String description, boolean readOnly,
                          String requiredRole, Duration timeout) {
        this(name, description, readOnly, requiredRole, timeout, emptyObjectSchema(), false);
    }

    /** 带参数 schema 的便捷构造（保持旧调用点兼容，默认无需审批）。 */
    public ToolDefinition(String name, String description, boolean readOnly,
                          String requiredRole, Duration timeout, Map<String, Object> parameters) {
        this(name, description, readOnly, requiredRole, timeout, parameters, false);
    }

    /** 标记为需要用户审批（HITL 审批门，G6）。 */
    public ToolDefinition requireApproval() {
        return new ToolDefinition(name, description, readOnly, requiredRole,
                timeout, parameters, true);
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
}
