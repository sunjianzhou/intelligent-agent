package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.integration.mcp.McpToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具列表端点（本地 {@link ToolExecutor} + MCP 注册表）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
public class ToolProxyController {

    private final ToolExecutor toolExecutor;
    private final McpToolRegistry mcpToolRegistry;

    public ToolProxyController(ToolExecutor toolExecutor, McpToolRegistry mcpToolRegistry) {
        this.toolExecutor = toolExecutor;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> toolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition definition : toolExecutor.definitions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", definition.name());
            entry.put("description", definition.description());
            entry.put("read_only", definition.readOnly());
            tools.add(entry);
        }
        tools.addAll(mcpToolRegistry.listTools());
        Map<String, Object> result = new HashMap<>();
        result.put("tools", tools);
        result.put("count", tools.size());
        return ResponseEntity.ok(result);
    }
}
