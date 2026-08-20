package com.intelligent.agent.web.integration.mcp;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.ToolResult;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stdio MCP 服务器经连接管理器接入：创建（transport=stdio）→ 连接 →
 * 工具注册 → 调用 → 断开清理。
 */
class McpStdioConnectionTest {

    @TempDir
    Path tempDir;

    private final SecretCrypto crypto = new SecretCrypto("test-secret-0123456789abcdef");

    @Test
    void connectsStdioServerRegistersAndCallsTools() {
        ToolExecutor toolExecutor = new ToolExecutor(List.of());
        McpConnectionManager manager =
                new McpConnectionManager(tempDir, crypto, toolExecutor);

        Map<String, Object> created = manager.create(Map.of(
                "name", "stdio-mock",
                "transport", "stdio",
                "command", javaCommand(),
                "args", List.of("-cp", classpath(), FakeMcpServer.class.getName())));
        assertThat(created.get("success")).isEqualTo(true);
        String id = String.valueOf(((Map<?, ?>) created.get("server")).get("id"));

        Map<String, Object> connected = manager.connect(id);
        assertThat(connected.get("success")).isEqualTo(true);
        assertThat(connected.get("tool_count")).isEqualTo(1);
        assertThat(toolExecutor.definitions())
                .extracting(d -> d.name()).contains("fake_echo");

        ToolResult result = toolExecutor.execute(
                ToolCall.of("fake_echo", Map.of("text", "hi")),
                ToolExecutionContext.of("alice", "user"));
        assertThat(result.status()).isEqualTo(ToolResult.SUCCESS);
        assertThat(String.valueOf(result.data())).isEqualTo("fake_echo:hi");

        manager.disconnect(id);
        assertThat(toolExecutor.definitions())
                .extracting(d -> d.name()).doesNotContain("fake_echo");
    }

    @Test
    void stdioServerRequiresCommand() {
        ToolExecutor toolExecutor = new ToolExecutor(List.of());
        McpConnectionManager manager =
                new McpConnectionManager(tempDir, crypto, toolExecutor);

        Map<String, Object> created = manager.create(Map.of(
                "name", "bad-stdio", "transport", "stdio"));

        assertThat(created.get("success")).isEqualTo(false);
        assertThat(String.valueOf(created.get("message"))).contains("command");
    }

    private static String javaCommand() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java").toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }
}
