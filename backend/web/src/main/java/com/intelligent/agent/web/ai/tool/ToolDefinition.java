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
 */
public record ToolDefinition(
        String name,
        String description,
        boolean readOnly,
        String requiredRole,
        Duration timeout,
        Map<String, Object> parameters) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        parameters = parameters == null ? emptyObjectSchema() : parameters;
    }

    /** 无参数 schema 的便捷构造（保持旧调用点兼容，默认空对象 schema）。 */
    public ToolDefinition(String name, String description, boolean readOnly,
                          String requiredRole, Duration timeout) {
        this(name, description, readOnly, requiredRole, timeout, emptyObjectSchema());
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
}
