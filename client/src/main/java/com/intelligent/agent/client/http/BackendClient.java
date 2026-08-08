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
import java.util.List;
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
        this(baseUrl, token, 600);
    }

    public BackendClient(String baseUrl, String token, int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private final int timeoutSeconds;

    public String complete(String message, Map<String, Object> options) throws Exception {
        Map<String, Object> body = requestBody(message, options);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
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

    /** 撤回会话中的指定消息（!retract）。 */
    public RetractResult retract(String sessionId, List<String> messageIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message_ids", messageIds);
        HttpResponse<String> response = sendJson(
                "POST", "/api/conversations/" + sessionId + "/retract", body);
        Map<String, Object> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        return new RetractResult(
                Boolean.TRUE.equals(result.get("success")),
                number(result.get("requested")),
                number(result.get("deleted")),
                stringList(result.get("deleted_ids")));
    }

    /** 可用模型列表（!models）。 */
    public List<String> models() throws Exception {
        HttpResponse<String> response = sendJson("GET", "/api/models", null);
        Map<String, Object> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        return stringList(result.get("available_models"));
    }

    /** 切换模型（!model）。 */
    public boolean switchModel(String modelName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        HttpResponse<String> response = sendJson("POST", "/api/model/switch", body);
        Map<String, Object> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        return Boolean.TRUE.equals(result.get("success"));
    }

    /** 激活角色（!persona）。 */
    public boolean activatePersona(String roleId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role_id", roleId);
        HttpResponse<String> response = sendJson("POST", "/api/roles/activate", body);
        Map<String, Object> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        return Boolean.TRUE.equals(result.get("success"));
    }

    /** 会话列表（!sessions）。 */
    public List<Map<String, Object>> listConversations() throws Exception {
        HttpResponse<String> response = sendJson("GET", "/api/conversations", null);
        Map<String, Object> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        Object sessions = result.get("sessions");
        if (sessions instanceof List) {
            return ((List<?>) sessions).stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    /** 角色列表（!personas）：返回 [{role_id, name}]。 */
    public List<Map<String, String>> personas() throws Exception {
        HttpResponse<String> response = sendJson("GET", "/api/roles", null);
        Map<String, Object> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        Object roles = result.get("roles");
        if (!(roles instanceof List)) {
            return List.of();
        }
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Object roleObj : (List<?>) roles) {
            if (!(roleObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> role = (Map<String, Object>) roleObj;
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role_id", String.valueOf(role.getOrDefault("role_id", "")));
            Object card = role.get("role_card");
            String name = card instanceof Map
                    ? String.valueOf(((Map<String, Object>) card).getOrDefault("name", "")) : "";
            entry.put("name", name);
            out.add(entry);
        }
        return out;
    }

    private HttpResponse<String> sendJson(String method, String path, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 流式聊天：逐行解析 SSE 事件并回调；返回完整回复文本。 */
    public String stream(String message, Map<String, Object> options,
                         Consumer<SseEvent> onEvent) throws Exception {
        Map<String, Object> body = requestBody(message, options);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat/stream"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
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

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        return ((List<Object>) value).stream().map(String::valueOf).toList();
    }
}
