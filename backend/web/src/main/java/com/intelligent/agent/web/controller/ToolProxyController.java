package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.integration.mcp.McpToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具列表代理端点（转发到 Python Agent /api/tools/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
public class ToolProxyController extends AbstractProxyController {

    private final ToolExecutor toolExecutor;
    private final McpToolRegistry mcpToolRegistry;
    private final String runtimeMode;

    public ToolProxyController(ToolExecutor toolExecutor,
                               McpToolRegistry mcpToolRegistry,
                               @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.toolExecutor = toolExecutor;
        this.mcpToolRegistry = mcpToolRegistry;
        this.runtimeMode = runtimeMode;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> toolsList(HttpServletRequest req) {
        if ("java".equals(runtimeMode) || "shadow".equals(runtimeMode)) {
            java.util.List<Map<String, Object>> tools = new java.util.ArrayList<>();
            for (ToolDefinition definition : toolExecutor.definitions()) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
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
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("tools", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGet("/api/tools/list", req, fallback);
    }
}

@RestController
@RequestMapping("/api/config")
class ConfigProxyController extends AbstractProxyController {

    @PatchMapping("/env")
    public ResponseEntity<Map<String, Object>> updateEnv(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPatch("/api/config/env", body, req);
    }

    @PatchMapping("/params")
    public ResponseEntity<Map<String, Object>> updateParams(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPatch("/api/config/params", body, req);
    }

    @GetMapping("/database")
    public ResponseEntity<Map<String, Object>> getDatabaseConfig(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("config", new HashMap<>());
        fallback.put("connected", false);
        return proxyGet("/api/config/database", req, fallback);
    }

    @PutMapping("/database")
    public ResponseEntity<Map<String, Object>> updateDatabaseConfig(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPut("/api/config/database", body, req);
    }
}
