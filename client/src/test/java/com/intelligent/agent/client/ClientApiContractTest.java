package com.intelligent.agent.client;

import com.intelligent.agent.client.http.BackendClient;
import com.intelligent.agent.client.http.RetractResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI 功能对齐契约测试（Plan 3 / Task 3）：
 * 用 JDK HttpServer 模拟后端，验证 retract / models / persona / switch 客户端行为。
 */
class ClientApiContractTest {

    private HttpServer server;
    private BackendClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/conversations/sess1/retract", exchange -> {
            respond(exchange, "{\"success\":true,\"requested\":1,\"deleted\":1,"
                    + "\"deleted_ids\":[\"m1\"],\"memory_purged\":1}");
        });
        server.createContext("/api/models", exchange -> {
            respond(exchange, "{\"available_models\":[\"qwen2.5:7b\",\"dolphin3:8b\"],"
                    + "\"current_model\":\"qwen2.5:7b\"}");
        });
        server.createContext("/api/roles/activate", exchange -> {
            respond(exchange, "{\"success\":true,\"role_id\":\"assistant_01\"}");
        });
        server.createContext("/api/model/switch", exchange -> {
            respond(exchange, "{\"success\":true}");
        });
        server.createContext("/api/conversations", exchange -> {
            respond(exchange, "{\"success\":true,\"sessions\":[{\"session_id\":\"s1\","
                    + "\"preview\":\"hi\"}],\"count\":1}");
        });
        server.start();

        client = new BackendClient("http://localhost:" + server.getAddress().getPort(), "cli-token");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void retractReturnsSuccess() throws Exception {
        RetractResult result = client.retract("sess1", List.of("m1"));

        assertThat(result.success()).isTrue();
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.deletedIds()).containsExactly("m1");
    }

    @Test
    void modelsReturnsAvailableModels() throws Exception {
        List<String> models = client.models();

        assertThat(models).contains("qwen2.5:7b", "dolphin3:8b");
    }

    @Test
    void activatePersonaReturnsTrue() throws Exception {
        assertThat(client.activatePersona("assistant_01")).isTrue();
    }

    @Test
    void switchModelReturnsTrue() throws Exception {
        assertThat(client.switchModel("qwen2.5:7b")).isTrue();
    }

    @Test
    void conversationsReturnsSessions() throws Exception {
        List<Map<String, Object>> sessions = client.listConversations();

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("session_id")).isEqualTo("s1");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
