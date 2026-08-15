package com.intelligent.agent.e2e;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * E2E HTTP 客户端：登录拿 JWT 后调用 Java 后端 REST 接口（黑盒，不 mock）。
 */
public class ApiClient {

    public record Response(int status, String body, Map<String, List<String>> headers) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .filter(v -> v != null && !v.isEmpty())
                    .map(v -> String.join(",", v))
                    .findFirst().orElse("");
        }
    }

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;

    public ApiClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
    }

    public static ApiClient login(String baseUrl, String username, String password,
                                  Duration timeout) throws Exception {
        ApiClient client = new ApiClient(baseUrl, timeout);
        Response r = client.post("/api/auth/login",
                Map.of("username", username, "password", password));
        if (r.status() != 200) {
            throw new IllegalStateException("登录失败: HTTP " + r.status() + " " + r.body());
        }
        Object token = client.json(r).get("token");
        if (token == null) {
            throw new IllegalStateException("登录响应缺少 token: " + r.body());
        }
        client.token = String.valueOf(token);
        return client;
    }

    public boolean reachable() {
        try {
            return get("/api/health").status() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    public Response get(String path) throws Exception {
        return send("GET", path, null);
    }

    public Response post(String path, Object body) throws Exception {
        return send("POST", path, body);
    }

    public Response put(String path, Object body) throws Exception {
        return send("PUT", path, body);
    }

    public Response patch(String path, Object body) throws Exception {
        return send("PATCH", path, body);
    }

    public Response delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    public Map<String, Object> json(Response r) throws Exception {
        return mapper.readValue(r.body(), new TypeReference<Map<String, Object>>() {});
    }

    public String token() {
        return token;
    }

    private Response send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpRequest request = switch (method) {
            case "GET" -> builder.GET().build();
            case "DELETE" -> builder.DELETE().build();
            default -> builder.method(method, HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : mapper.writeValueAsString(body))).build();
        };
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().map());
    }
}
