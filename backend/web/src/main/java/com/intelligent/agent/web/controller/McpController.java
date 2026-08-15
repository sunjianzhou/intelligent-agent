package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.integration.mcp.McpConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器管理端点（G2）：配置 CRUD + connect/disconnect。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpConnectionManager connectionManager;

    public McpController(McpConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @GetMapping("/servers")
    public ResponseEntity<Map<String, Object>> listServers() {
        List<Map<String, Object>> servers = connectionManager.list();
        Map<String, Object> result = new HashMap<>();
        result.put("servers", servers);
        result.put("count", servers.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/servers/{serverId}")
    public ResponseEntity<Map<String, Object>> getServer(@PathVariable String serverId) {
        Map<String, Object> server = connectionManager.get(serverId);
        if (server == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false, "message", "服务器不存在"));
        }
        return ResponseEntity.ok(server);
    }

    @PostMapping("/servers")
    public ResponseEntity<Map<String, Object>> createServer(
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(connectionManager.create(body));
    }

    @PutMapping("/servers/{serverId}")
    public ResponseEntity<Map<String, Object>> updateServer(
            @PathVariable String serverId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(connectionManager.update(serverId, body));
    }

    @DeleteMapping("/servers/{serverId}")
    public ResponseEntity<Map<String, Object>> deleteServer(@PathVariable String serverId) {
        return ResponseEntity.ok(connectionManager.delete(serverId));
    }

    @PostMapping("/servers/{serverId}/connect")
    public ResponseEntity<Map<String, Object>> connectServer(@PathVariable String serverId) {
        return ResponseEntity.ok(connectionManager.connect(serverId));
    }

    @PostMapping("/servers/{serverId}/disconnect")
    public ResponseEntity<Map<String, Object>> disconnectServer(@PathVariable String serverId) {
        return ResponseEntity.ok(connectionManager.disconnect(serverId));
    }
}
