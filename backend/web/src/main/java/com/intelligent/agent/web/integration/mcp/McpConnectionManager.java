package com.intelligent.agent.web.integration.mcp;

import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 连接管理器（G2）：服务器配置持久化（apiKey 加密）+ 生命周期
 * （connect/disconnect）+ 工具动态注册进 {@link ToolExecutor}。
 */
@Slf4j
public class McpConnectionManager {

    private final Path dataDir;
    private final SecretCrypto secretCrypto;
    private final ToolExecutor toolExecutor;
    private final JsonFileStore store;

    private final Map<String, McpServerConfig> servers = new ConcurrentHashMap<>();
    /** 已连接服务器：id → 传输客户端（HTTP/stdio，session 池化复用）。 */
    private final Map<String, McpTransportClient> connected = new ConcurrentHashMap<>();
    /** 已连接服务器注册的工具名：id → [tool...]。 */
    private final Map<String, List<String>> registeredTools = new ConcurrentHashMap<>();

    public McpConnectionManager(Path dataDir, SecretCrypto secretCrypto,
                                ToolExecutor toolExecutor) {
        this.dataDir = dataDir;
        this.secretCrypto = secretCrypto;
        this.toolExecutor = toolExecutor;
        this.store = new JsonFileStore(dataDir);
        load();
    }

    @PostConstruct
    public void connectEnabledServers() {
        for (McpServerConfig server : servers.values()) {
            if (server.enabled()) {
                try {
                    connect(server.id());
                } catch (Exception e) {
                    log.warn("MCP 服务器启动连接失败 {} ({}): {}",
                            server.name(), server.id(), e.getMessage());
                }
            }
        }
    }

    @PreDestroy
    public void disconnectAll() {
        for (String serverId : new ArrayList<>(connected.keySet())) {
            disconnect(serverId);
        }
    }

    // ── 配置 CRUD ─────────────────────────────────────────────

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServerConfig server : servers.values()) {
            result.add(summary(server));
        }
        return result;
    }

    public Map<String, Object> get(String serverId) {
        McpServerConfig server = servers.get(serverId);
        return server == null ? null : summary(server);
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String id = "mcp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        McpServerConfig server = new McpServerConfig(
                id, str(body.get("name")), str(body.get("base_url")),
                encrypt(body.get("api_key")),
                str(body.get("transport")),
                str(body.get("command")),
                strList(body.get("args")),
                body.get("enabled") == null || Boolean.TRUE.equals(body.get("enabled")),
                str(body.get("protocol_version")), Instant.now(), Instant.now());
        if (server.name().isBlank() || server.baseUrl().isBlank()) {
            if ("stdio".equals(server.transport())) {
                if (server.name().isBlank()) {
                    return Map.of("success", false, "message", "name 必填");
                }
                if (server.command().isBlank()) {
                    return Map.of("success", false, "message", "stdio 传输必须填写 command");
                }
            } else {
                return Map.of("success", false, "message", "name 与 base_url 必填");
            }
        }
        servers.put(id, server);
        persist();
        if (server.enabled()) {
            try {
                connect(id);
            } catch (Exception e) {
                log.warn("新建 MCP 服务器连接失败: {}", e.getMessage());
            }
        }
        return Map.of("success", true, "server", summary(server));
    }

    public Map<String, Object> update(String serverId, Map<String, Object> body) {
        McpServerConfig existing = servers.get(serverId);
        if (existing == null) {
            return Map.of("success", false, "message", "服务器不存在");
        }
        String apiKey = body.get("api_key") == null
                ? existing.apiKey() : encrypt(body.get("api_key"));
        McpServerConfig updated = new McpServerConfig(
                serverId,
                body.get("name") == null ? existing.name() : str(body.get("name")),
                body.get("base_url") == null ? existing.baseUrl() : str(body.get("base_url")),
                apiKey,
                body.get("transport") == null ? existing.transport() : str(body.get("transport")),
                body.get("command") == null ? existing.command() : str(body.get("command")),
                body.get("args") == null ? existing.args() : strList(body.get("args")),
                body.get("enabled") == null ? existing.enabled()
                        : Boolean.TRUE.equals(body.get("enabled")),
                body.get("protocol_version") == null ? existing.protocolVersion()
                        : str(body.get("protocol_version")),
                existing.createdAt(), Instant.now());
        disconnect(serverId);
        servers.put(serverId, updated);
        persist();
        if (updated.enabled()) {
            try {
                connect(serverId);
            } catch (Exception e) {
                log.warn("更新后 MCP 服务器连接失败: {}", e.getMessage());
            }
        }
        return Map.of("success", true, "server", summary(updated));
    }

    public Map<String, Object> delete(String serverId) {
        disconnect(serverId);
        boolean removed = servers.remove(serverId) != null;
        if (removed) {
            persist();
        }
        return Map.of("success", removed,
                "message", removed ? "服务器已删除" : "服务器不存在");
    }

    // ── 连接生命周期 ───────────────────────────────────────────

    public Map<String, Object> connect(String serverId) {
        McpServerConfig server = servers.get(serverId);
        if (server == null) {
            return Map.of("success", false, "message", "服务器不存在");
        }
        disconnect(serverId);
        McpTransportClient client = "stdio".equals(server.transport())
                ? new McpStdioClient(server.command(), server.args(), Duration.ofSeconds(30))
                : new McpClient(server.baseUrl(),
                        secretCrypto.decrypt(server.apiKey()), Duration.ofSeconds(30));
        if (!client.initialize(server.protocolVersion())) {
            client.close();
            return Map.of("success", false, "message", "MCP 初始化失败（握手无响应）");
        }
        List<McpClient.McpToolInfo> tools = client.listTools();
        connected.put(serverId, client);
        List<String> toolNames = new ArrayList<>();
        for (McpClient.McpToolInfo tool : tools) {
            if (toolExecutor.definitions().stream().anyMatch(d -> d.name().equals(tool.name()))) {
                log.warn("MCP 工具名冲突，跳过: {} (server={})", tool.name(), server.name());
                continue;
            }
            toolExecutor.register(new McpAgentTool(this, serverId, tool));
            toolNames.add(tool.name());
        }
        registeredTools.put(serverId, toolNames);
        log.info("MCP 服务器已连接 {} ({}): {} 个工具", server.name(), serverId, toolNames.size());
        return Map.of("success", true, "tool_count", toolNames.size(), "tools", toolNames);
    }

    public Map<String, Object> disconnect(String serverId) {
        List<String> toolNames = registeredTools.remove(serverId);
        if (toolNames != null) {
            for (String name : toolNames) {
                toolExecutor.unregister(name);
            }
        }
        McpTransportClient removed = connected.remove(serverId);
        if (removed != null) {
            removed.close();
        }
        if (removed == null && toolNames == null) {
            return Map.of("success", false, "message", "服务器未连接");
        }
        return Map.of("success", true, "message", "已断开连接");
    }

    public boolean isConnected(String serverId) {
        return connected.containsKey(serverId);
    }

    /** 由 McpAgentTool 调用：路由到对应服务器的已连接客户端执行工具。 */
    public Map<String, Object> callTool(String serverId, String toolName,
                                        Map<String, Object> arguments) {
        McpTransportClient client = connected.get(serverId);
        if (client == null) {
            return Map.of("success", false, "error", "MCP 服务器未连接: " + serverId);
        }
        return client.callTool(toolName, arguments);
    }

    // ── 持久化 ───────────────────────────────────────────────

    private void load() {
        Map<String, Object> data = store.read("mcp_servers.json");
        if (data == null || !(data.get("servers") instanceof List)) {
            return;
        }
        for (Object item : (List<?>) data.get("servers")) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                McpServerConfig server = McpServerConfig.fromMap((Map<String, Object>) item);
                if (server.id() != null) {
                    servers.put(server.id(), server);
                }
            }
        }
        log.info("MCP 服务器配置已加载 {} 个", servers.size());
    }

    private void persist() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (McpServerConfig server : servers.values()) {
            list.add(server.toMap());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("servers", list);
        store.write(new String[]{"mcp_servers.json"}, data);
    }

    private Map<String, Object> summary(McpServerConfig server) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", server.id());
        map.put("name", server.name());
        map.put("base_url", server.baseUrl());
        map.put("transport", server.transport());
        map.put("command", server.command());
        map.put("args", server.args());
        map.put("api_key_masked", mask(server.apiKey()));
        map.put("enabled", server.enabled());
        map.put("protocol_version", server.protocolVersion());
        map.put("connected", connected.containsKey(server.id()));
        map.put("tool_count", registeredTools.getOrDefault(
                server.id(), List.of()).size());
        map.put("created_at", server.createdAt().toString());
        map.put("updated_at", server.updatedAt().toString());
        return map;
    }

    private String encrypt(Object apiKey) {
        if (apiKey == null) {
            return null;
        }
        String plain = String.valueOf(apiKey);
        return plain.isBlank() || plain.startsWith("enc:")
                ? plain : secretCrypto.encrypt(plain);
    }

    private static List<String> strList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }

    private static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String plain = apiKey.startsWith("enc:") ? "******" : apiKey;
        return plain.length() <= 8 ? "******" : plain.substring(0, 4) + "******" + plain.substring(plain.length() - 4);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
