package com.intelligent.agent.web.integration.mcp;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * MCP 工具注册表（Plan 2 / Task 5）：
 * 注册 MCP 暴露的工具（name → executor），供 ToolExecutor / LLM 工具列表消费。
 * 外部 MCP 服务器连接（stdio/SSE 传输）在 Plan 3 集成时接入。
 */
@Slf4j
public class McpToolRegistry {

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();

    public void register(String name, String description,
                         Function<Map<String, Object>, Object> executor) {
        tools.put(name, new RegisteredTool(name, description, executor));
        log.info("MCP 工具已注册: {}", name);
    }

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RegisteredTool tool : tools.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            result.add(entry);
        }
        return result;
    }

    public Optional<Object> execute(String name, Map<String, Object> arguments) {
        RegisteredTool tool = tools.get(name);
        if (tool == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tool.executor().apply(arguments == null ? Map.of() : arguments));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    private record RegisteredTool(String name, String description,
                                  Function<Map<String, Object>, Object> executor) {
    }
}
