package com.intelligent.agent.web.ai.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用：工具名 + 参数。
 *
 * @param name      工具名
 * @param arguments 参数表
 */
public record ToolCall(String name, Map<String, Object> arguments) {

    public ToolCall {
        Objects.requireNonNull(name, "name must not be null");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static ToolCall of(String name, Map<String, Object> arguments) {
        return new ToolCall(name, arguments);
    }
}
