package com.intelligent.agent.web.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.ToolResult;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 连接管理器契约（G2）：真实 HTTP JSON-RPC mock 服务器上的
 * 连接/工具注册/工具调用/断开/持久化（apiKey 加密）。
 */
class McpConnectionManagerTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretCrypto crypto = new SecretCrypto("test-secret-0123456789abcdef");

    @BeforeEach
    void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void connectRegistersToolsAndCallRoutesToServer() throws Exception {
        ToolExecutor toolExecutor = new ToolExecutor(List.of());
        McpConnectionManager manager =
                new McpConnectionManager(tempDir, crypto, toolExecutor);

        Map<String, Object> created = manager.create(Map.of(
                "name", "mock",
                "base_url", baseUrl,
                "api_key", "sk-test"));
        assertThat(created.get("success")).isEqualTo(true);
        String id = String.valueOf(((Map<?, ?>) created.get("server")).get("id"));

        Map<String, Object> connected = manager.connect(id);
        assertThat(connected.get("success")).isEqualTo(true);
        assertThat(connected.get("tool_count")).isEqualTo(1);

        // 工具已注册进 ToolExecutor，LLM 可直接调用
        assertThat(toolExecutor.definitions())
                .extracting(d -> d.name()).contains("mock_echo");

        ToolResult result = toolExecutor.execute(
                ToolCall.of("mock_echo", Map.of("text", "hi")),
                ToolExecutionContext.of("alice", "user"));
        assertThat(result.status()).isEqualTo(ToolResult.SUCCESS);
        assertThat(String.valueOf(result.data())).isEqualTo("mock_echo:hi");

        manager.disconnect(id);
        assertThat(toolExecutor.definitions())
                .extracting(d -> d.name()).doesNotContain("mock_echo");
    }

    @Test
    void apiKeyIsEncryptedAtRestAndDecryptedOnConnect() throws Exception {
        ToolExecutor toolExecutor = new ToolExecutor(List.of());
        McpConnectionManager manager =
                new McpConnectionManager(tempDir, crypto, toolExecutor);
        manager.create(Map.of("name", "mock", "base_url", baseUrl, "api_key", "sk-secret"));

        String persisted = Files.readString(tempDir.resolve("mcp_servers.json"));
        assertThat(persisted).contains("enc:").doesNotContain("sk-secret");

        // 重载后仍能连接（解密后带鉴权头访问 mock）
        McpConnectionManager reloaded =
                new McpConnectionManager(tempDir, crypto, toolExecutor);
        String id = reloaded.list().get(0).get("id").toString();
        Map<String, Object> connected = reloaded.connect(id);
        assertThat(connected.get("success")).isEqualTo(true);
    }

    @Test
    void connectFailureReturnsErrorAndRegistersNoTools() {
        ToolExecutor toolExecutor = new ToolExecutor(List.of());
        McpConnectionManager manager =
                new McpConnectionManager(tempDir, crypto, toolExecutor);
        manager.create(Map.of("name", "dead", "base_url", "http://127.0.0.1:1", "enabled", false));
        String id = manager.list().get(0).get("id").toString();

        Map<String, Object> connected = manager.connect(id);
        assertThat(connected.get("success")).isEqualTo(false);
        assertThat(toolExecutor.definitions()).isEmpty();
    }

    @Test
    void duplicateToolNameIsSkipped() throws Exception {
        ToolExecutor toolExecutor = new ToolExecutor(List.of());
        McpConnectionManager manager =
                new McpConnectionManager(tempDir, crypto, toolExecutor);
        // 预注册同名工具制造冲突
        toolExecutor.register(new EchoStub("mock_echo"));
        manager.create(Map.of("name", "mock", "base_url", baseUrl, "enabled", false));
        String id = manager.list().get(0).get("id").toString();

        Map<String, Object> connected = manager.connect(id);
        assertThat(connected.get("success")).isEqualTo(true);
        assertThat(connected.get("tool_count")).isEqualTo(0);
    }

    /** 预注册冲突桩。 */
    static class EchoStub implements AgentTool {
        private final String name;

        EchoStub(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "stub", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return "stub";
        }
    }

    // ── mock JSON-RPC 服务器 ─────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String responseBody;
        String sessionHeader = null;
        try {
            Map<?, ?> request = mapper.readValue(body, Map.class);
            Object methodObj = request.get("method");
            String method = methodObj == null ? "" : String.valueOf(methodObj);
            responseBody = switch (method) {
                case "initialize" -> {
                    sessionHeader = "sess-mock-1";
                    yield "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                            + ",\"result\":{\"protocolVersion\":\"2024-11-05\","
                            + "\"capabilities\":{},\"serverInfo\":{\"name\":\"mock\",\"version\":\"1\"}}}";
                }
                case "notifications/initialized" -> "";
                case "tools/list" -> "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                        + ",\"result\":{\"tools\":[{\"name\":\"mock_echo\","
                        + "\"description\":\"回显测试工具\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"text\":{\"type\":\"string\"}}}}]}}";
                case "tools/call" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (Map<String, Object>) request.get("params");
                    String toolName = String.valueOf(params.get("name"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = params.get("arguments") instanceof Map
                            ? (Map<String, Object>) params.get("arguments") : Map.of();
                    yield "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                            + toolName + ":" + args.getOrDefault("text", "") + "\"}],\"isError\":false}}";
                }
                default -> "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                        + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
            };
        } catch (Exception e) {
            responseBody = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"parse error\"}}";
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (sessionHeader != null) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", sessionHeader);
        }
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseBody.isEmpty() ? 202 : 200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }
}
