package com.intelligent.agent.web.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 最小 HTTP JSON-RPC 客户端（G2）：
 * initialize → tools/list → tools/call；支持 Bearer apiKey 与 MCP session id；
 * 响应兼容普通 JSON 与 text/event-stream（SSE）两种 Content-Type。
 */
@Slf4j
public class McpClient {

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicLong requestId = new AtomicLong(1);
    private volatile String sessionId;

    public McpClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Duration.ofSeconds(30));
    }

    public McpClient(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    /** MCP initialize 握手（带 session 建立通知）。 */
    public boolean initialize(String protocolVersion) {
        Map<String, Object> result = rpc("initialize", Map.of(
                "protocolVersion", protocolVersion == null ? "2024-11-05" : protocolVersion,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "intelligent-agent", "version", "1.0")));
        if (result == null) {
            return false;
        }
        // initialized 通知（无 id，不等待响应）
        postNotification("notifications/initialized", Map.of());
        return true;
    }

    public List<McpToolInfo> listTools() {
        Map<String, Object> result = rpc("tools/list", Map.of());
        List<McpToolInfo> tools = new ArrayList<>();
        if (result == null || !(result.get("tools") instanceof List)) {
            return tools;
        }
        for (Object item : (List<?>) result.get("tools")) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) item;
            String name = String.valueOf(t.getOrDefault("name", ""));
            if (name.isBlank()) {
                continue;
            }
            String description = t.get("description") == null
                    ? "" : String.valueOf(t.get("description"));
            Object schema = t.get("inputSchema");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchema = schema instanceof Map
                    ? (Map<String, Object>) schema : Map.of();
            tools.add(new McpToolInfo(name, description, inputSchema));
        }
        return tools;
    }

    /** 调用工具：返回 {success, content, error}。 */
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> result = rpc("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments));
        if (result == null) {
            return Map.of("success", false, "error", "MCP 调用无响应");
        }
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        StringBuilder content = new StringBuilder();
        if (result.get("content") instanceof List) {
            for (Object item : (List<?>) result.get("content")) {
                if (item instanceof Map && ((Map<?, ?>) item).get("text") != null) {
                    content.append(((Map<?, ?>) item).get("text"));
                }
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("success", !isError);
        out.put("content", content.toString());
        if (isError || content.isEmpty()) {
            Object err = result.get("isError") == null ? null : result.get("error");
            out.put("error", err == null ? "MCP 工具返回错误" : String.valueOf(err));
        }
        return out;
    }

    public String sessionId() {
        return sessionId;
    }

    // ── JSON-RPC 传输 ────────────────────────────────────────

    private Map<String, Object> rpc(String method, Map<String, Object> params) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", requestId.getAndIncrement());
        body.put("method", method);
        body.put("params", params);
        HttpResponse<String> response = post(body);
        if (response == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(extractPayload(response));
            JsonNode result = node.get("result");
            if (result != null && !result.isNull()) {
                return mapper.convertValue(result,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            JsonNode error = node.get("error");
            log.warn("MCP RPC 错误 {}: {}", method,
                    error == null ? "unknown" : error.toString());
            return null;
        } catch (Exception e) {
            log.warn("MCP RPC 解析失败 {}: {}", method, e.getMessage());
            return null;
        }
    }

    private void postNotification(String method, Map<String, Object> params) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        post(body);
    }

    private HttpResponse<String> post(Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            HttpResponse<String> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            String newSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (newSession != null && !newSession.isBlank()) {
                sessionId = newSession;
            }
            return response;
        } catch (Exception e) {
            log.warn("MCP HTTP 请求失败 {}: {}", baseUrl, e.getMessage());
            return null;
        }
    }

    /** SSE 响应时取 data: 行（MCP HTTP 传输可能以 SSE 帧返回 JSON-RPC）。 */
    private String extractPayload(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        String body = response.body();
        if (contentType.contains("text/event-stream")) {
            StringBuilder json = new StringBuilder();
            for (String line : body.split("\\r?\\n")) {
                if (line.startsWith("data:")) {
                    json.append(line.substring(5).trim());
                }
            }
            return json.toString();
        }
        return body;
    }

    /** MCP 工具元数据（name/description/inputSchema）。 */
    public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
    }
}
