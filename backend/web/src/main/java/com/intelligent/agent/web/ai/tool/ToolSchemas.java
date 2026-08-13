package com.intelligent.agent.web.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生工具调用 schema 转换：把 {@link ToolDefinition} 列表映射为
 * Ollama / OpenAI 兼容的 {@code tools} 载荷（type=function + JSON Schema parameters）。
 * <p>
 * 两种协议使用同一形状：{@code [{"type":"function","function":{name,description,parameters}}]}，
 * 仅当协议有差异时由各 provider 在序列化阶段归一化。
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static List<Map<String, Object>> toPayload(List<ToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition def : definitions) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.name());
            function.put("description", def.description() == null ? "" : def.description());
            function.put("parameters", def.parameters());
            tools.add(Map.of("type", "function", "function", function));
        }
        return tools;
    }
}
