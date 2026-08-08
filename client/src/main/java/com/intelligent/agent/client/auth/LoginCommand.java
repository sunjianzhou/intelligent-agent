package com.intelligent.agent.client.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * agent-cli login：向后端 /api/auth/cli-token 换取 scoped token 并安全落盘。
 */
@Command(name = "login", description = "Login and store a scoped CLI token")
public class LoginCommand implements Callable<Integer> {

    @Option(names = "--url", defaultValue = "http://localhost:8080",
            description = "Backend base URL (default: ${DEFAULT-VALUE})")
    private String url;

    @Option(names = "--username", required = true, description = "Username")
    private String username;

    @Option(names = "--password", required = true, interactive = true,
            description = "Password (prompted if not supplied)")
    private String password;

    @Option(names = "--token-file", description = "Override token file path")
    private java.nio.file.Path tokenFile;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "/api/auth/cli-token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        new ObjectMapper().writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, Object> result = new ObjectMapper().readValue(
                response.body(), new TypeReference<Map<String, Object>>() {});
        if (response.statusCode() != 200 || !Boolean.TRUE.equals(result.get("success"))) {
            System.err.println("Login failed: " + result.getOrDefault("message", response.statusCode()));
            return 1;
        }

        String token = String.valueOf(result.get("token"));
        TokenStore store = tokenFile != null ? new TokenStore(tokenFile) : TokenStore.defaultStore();
        store.save(token);
        System.out.println("Login OK: " + result.get("username")
                + " (scope=" + result.getOrDefault("scope", "cli") + ")");
        System.out.println("Token saved to " + store.path());
        return 0;
    }
}
