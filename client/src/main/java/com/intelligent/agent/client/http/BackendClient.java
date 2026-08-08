package com.intelligent.agent.client.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.client.chat.SseEvent;
import com.intelligent.agent.client.chat.SseEventParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Java CLI 后端客户端（Plan 3 / Task 2）：
 * <ul>
 *   <li>complete：POST /api/chat（非流式）；</li>
 *   <li>stream：POST /api/chat/stream（SSE，逐行透传解析后的事件）。</li>
 * </ul>
 * 使用 scoped CLI token（Authorization: Bearer），绝不持有 JWT_SECRET。
 */
public class BackendClient {

    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SseEventParser parser = new SseEventParser();

    public BackendClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String complete(String message, Map<String, Object> options) throws Exception {
        Map<String, Object> body = requestBody(message, options);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        Object data = result.get("data");
        if (data instanceof Map) {
            Object responseText = ((Map<?, ?>) data).get("response");
            if (responseText != null) {
                return String.valueOf(responseText);
            }
        }
        return String.valueOf(result.getOrDefault("message", "服务异常"));
    }

    /** 流式聊天：逐行解析 SSE 事件并回调；返回完整回复文本。 */
    public String stream(String message, Map<String, Object> options,
                         Consumer<SseEvent> onEvent) throws Exception {
        Map<String, Object> body = requestBody(message, options);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat/stream"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                SseEvent event = parser.parse(line);
                if (event == null) {
                    continue;
                }
                if ("token".equals(event.type())) {
                    full.append(rawText(event.data()));
                }
                onEvent.accept(event);
            }
        }
        return full.toString();
    }

    private static Map<String, Object> requestBody(String message, Map<String, Object> options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("use_tools", options.getOrDefault("use_tools", true));
        body.put("use_memory", options.getOrDefault("use_memory", true));
        if (options.containsKey("model")) {
            body.put("model", options.get("model"));
        }
        if (options.containsKey("persona")) {
            body.put("persona", options.get("persona"));
        }
        return body;
    }

    private static String rawText(String jsonData) {
        if (jsonData == null || jsonData.isEmpty() || "{}".equals(jsonData)) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(jsonData).asText();
        } catch (Exception e) {
            String trimmed = jsonData;
            if (trimmed.startsWith("\"")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }
    }
}
