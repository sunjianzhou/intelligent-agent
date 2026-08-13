package com.intelligent.agent.web.ai.tool;

import java.util.HashMap;
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
        arguments = arguments == null ? Map.of() : copyWithoutNulls(arguments);
    }

    public static ToolCall of(String name, Map<String, Object> arguments) {
        return new ToolCall(name, arguments);
    }

    /** 过滤 null 值后生成不可变拷贝（原生工具调用参数可能含 null，Map.copyOf 会 NPE）。 */
    private static Map<String, Object> copyWithoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>();
        source.forEach((key, value) -> {
            if (value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }
}
