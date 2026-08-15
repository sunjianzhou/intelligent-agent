package com.intelligent.agent.web.integration.mcp;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.time.Duration;
import java.util.Map;

/**
 * MCP 工具适配为 {@link AgentTool}（G2）：注册进 ToolExecutor 后，LLM 可直接
 * 按工具名调用，执行时回传连接管理器路由到对应 MCP 服务器。
 */
public class McpAgentTool implements AgentTool {

    private final McpConnectionManager connectionManager;
    private final String serverId;
    private final McpClient.McpToolInfo toolInfo;

    public McpAgentTool(McpConnectionManager connectionManager, String serverId,
                        McpClient.McpToolInfo toolInfo) {
        this.connectionManager = connectionManager;
        this.serverId = serverId;
        this.toolInfo = toolInfo;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                toolInfo.name(),
                toolInfo.description(),
                false, null, Duration.ofSeconds(60),
                toolInfo.inputSchema().isEmpty()
                        ? Map.of("type", "object", "properties", Map.of())
                        : toolInfo.inputSchema());
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        Map<String, Object> result = connectionManager.callTool(
                serverId, toolInfo.name(), arguments);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Object content = result.getOrDefault("content", "");
            return content == null || String.valueOf(content).isBlank()
                    ? "MCP 工具执行完成（无文本输出）" : content;
        }
        return "MCP 工具调用失败: " + result.getOrDefault("error", "未知错误");
    }
}
