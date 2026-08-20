package com.intelligent.agent.web.integration.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置（G2）：HTTP JSON-RPC 传输，apiKey 落盘前经 SecretCrypto 加密。
 * stdio 传输使用 command + args 启动本地进程（如 {@code npx -y <package>}）。
 */
public record McpServerConfig(
        String id,
        String name,
        String baseUrl,
        String apiKey,          // 密文（enc: 前缀）或历史明文
        String transport,       // http / stdio
        String command,         // stdio 时必填
        List<String> args,      // stdio 参数
        boolean enabled,
        String protocolVersion,
        Instant createdAt,
        Instant updatedAt) {

    public McpServerConfig {
        name = name == null ? "" : name;
        baseUrl = baseUrl == null ? "" : baseUrl;
        transport = transport == null || transport.isBlank() ? "http" : transport.trim();
        command = command == null ? "" : command;
        args = args == null ? List.of() : List.copyOf(args);
        protocolVersion = protocolVersion == null ? "2024-11-05" : protocolVersion;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static McpServerConfig fromMap(Map<String, Object> m) {
        return new McpServerConfig(
                str(m.get("id")),
                str(m.get("name")),
                str(m.get("base_url")),
                str(m.get("api_key")),
                str(m.get("transport")),
                str(m.get("command")),
                strList(m.get("args")),
                Boolean.TRUE.equals(m.get("enabled")),
                str(m.get("protocol_version")),
                instant(m.get("created_at")),
                instant(m.get("updated_at")));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("base_url", baseUrl);
        m.put("api_key", apiKey);
        m.put("transport", transport);
        m.put("command", command);
        m.put("args", args);
        m.put("enabled", enabled);
        m.put("protocol_version", protocolVersion);
        m.put("created_at", createdAt.toString());
        m.put("updated_at", updatedAt.toString());
        return m;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(Object value) {
        return value == null ? Instant.now() : Instant.parse(String.valueOf(value));
    }

    private static List<String> strList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        return ((List<?>) value).stream()
                .map(String::valueOf)
                .toList();
    }
}
