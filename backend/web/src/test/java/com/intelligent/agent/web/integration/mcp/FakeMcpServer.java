package com.intelligent.agent.web.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 stdio MCP 假服务器：从 stdin 读行分隔 JSON-RPC，向 stdout 应答。
 * 参数 "silent" 时只读不应答（超时测试用）。
 */
public final class FakeMcpServer {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

        boolean silent = args.length > 0 && "silent".equals(args[0]);
        String line;
        while ((line = in.readLine()) != null) {
            if (silent || line.isBlank()) {
                continue;
            }
            JsonNode request;
            try {
                request = mapper.readTree(line);
            } catch (Exception e) {
                continue;
            }
            JsonNode id = request.get("id");
            if (id == null || id.isNull()) {
                continue; // notification：不应答
            }
            String method = request.path("method").asText();
            Map<String, Object> result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", request.path("params").path("protocolVersion")
                                .asText("2024-11-05"),
                        "capabilities", Map.of(),
                        "serverInfo", Map.of("name", "fake-mcp", "version", "1.0"),
                        "_meta", Map.of("sessionId", "sess-fake-1"));
                case "tools/list" -> Map.of("tools", List.of(Map.of(
                        "name", "fake_echo",
                        "description", "echo",
                        "inputSchema", Map.of("type", "object",
                                "properties", Map.of("text", Map.of("type", "string")),
                                "required", List.of("text")))));
                case "tools/call" -> Map.of("content", List.of(Map.of("type", "text",
                        "text", "fake_echo:" + request.path("params").path("arguments")
                                .path("text").asText(""))));
                default -> Map.of();
            };
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            out.println(mapper.writeValueAsString(response));
        }
    }
}
