package com.intelligent.agent.web.integration.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP stdio 传输客户端（G2）：本地进程 + 行分隔 JSON-RPC（stdin/stdout）。
 * 读取线程按 request id 关联响应；服务器通知/日志行忽略；超时/进程退出
 * 使挂起请求失败。每个客户端持有一个进程与 session（池化复用）。
 */
@Slf4j
public class McpStdioClient implements McpTransportClient {

    private final String command;
    private final List<String> args;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong requestId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile PrintWriter stdin;
    private volatile String sessionId;
    private volatile boolean closed;

    public McpStdioClient(String command, List<String> args, Duration timeout) {
        this.command = command;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    @Override
    public synchronized boolean initialize(String protocolVersion) {
        if (!start()) {
            return false;
        }
        Map<String, Object> result = rpc("initialize", Map.of(
                "protocolVersion", protocolVersion == null ? "2024-11-05" : protocolVersion,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "intelligent-agent", "version", "1.0")));
        if (result == null) {
            return false;
        }
        Object meta = result.get("_meta");
        if (meta instanceof Map && ((Map<?, ?>) meta).get("sessionId") != null) {
            sessionId = String.valueOf(((Map<?, ?>) meta).get("sessionId"));
        }
        postNotification("notifications/initialized", Map.of());
        return true;
    }

    @Override
    public List<McpClient.McpToolInfo> listTools() {
        Map<String, Object> result = rpc("tools/list", Map.of());
        List<McpClient.McpToolInfo> tools = new ArrayList<>();
        if (result == null || !(result.get("tools") instanceof List)) {
            return tools;
        }
        for (Object item : (List<?>) result.get("tools")) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) item;
            String name = String.valueOf(tool.getOrDefault("name", ""));
            if (name.isBlank()) {
                continue;
            }
            String description = tool.get("description") == null
                    ? "" : String.valueOf(tool.get("description"));
            Object schema = tool.get("inputSchema");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchema = schema instanceof Map
                    ? (Map<String, Object>) schema : Map.of();
            tools.add(new McpClient.McpToolInfo(name, description, inputSchema));
        }
        return tools;
    }

    @Override
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> result = rpc("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments));
        if (result == null) {
            return Map.of("success", false, "error", "MCP stdio 调用无响应");
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
            Object err = result.get("error");
            out.put("error", err == null ? "MCP 工具返回错误" : String.valueOf(err));
        }
        return out;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public synchronized void close() {
        closed = true;
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Exception ignored) {
            // ignore
        }
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(2, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        failAll("客户端已关闭");
    }

    // ── 进程与 JSON-RPC ──────────────────────────────────────

    private synchronized boolean start() {
        if (process != null && process.isAlive()) {
            return true;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            process = builder.start();
            stdin = new PrintWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8), true);
            Thread reader = new Thread(this::readLoop, "mcp-stdio-reader");
            reader.setDaemon(true);
            reader.start();
            Thread stderr = new Thread(this::drainStderr, "mcp-stdio-stderr");
            stderr.setDaemon(true);
            stderr.start();
            return true;
        } catch (Exception e) {
            log.warn("MCP stdio 进程启动失败 {}: {}", command, e.getMessage());
            failAll("进程启动失败");
            return false;
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode idNode = node.get("id");
                    if (idNode != null && idNode.isNumber()) {
                        CompletableFuture<JsonNode> future =
                                pending.remove(idNode.longValue());
                        if (future != null) {
                            future.complete(node);
                        }
                    }
                } catch (Exception e) {
                    log.warn("MCP stdio 响应解析失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("MCP stdio 读取线程退出: {}", e.getMessage());
        } finally {
            failAll("进程退出");
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("MCP stdio stderr: {}", line);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private Map<String, Object> rpc(String method, Map<String, Object> params) {
        long id = requestId.getAndIncrement();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            if (stdin == null) {
                return null;
            }
            stdin.println(mapper.writeValueAsString(body));
            JsonNode node = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            JsonNode result = node.get("result");
            if (result != null && !result.isNull()) {
                return mapper.convertValue(result, new TypeReference<Map<String, Object>>() {
                });
            }
            JsonNode error = node.get("error");
            log.warn("MCP stdio RPC 错误 {}: {}", method,
                    error == null ? "unknown" : error.toString());
            return null;
        } catch (Exception e) {
            log.warn("MCP stdio RPC {} 失败: {}", method, e.getMessage());
            return null;
        } finally {
            pending.remove(id);
        }
    }

    private void postNotification(String method, Map<String, Object> params) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        try {
            if (stdin != null) {
                stdin.println(mapper.writeValueAsString(body));
            }
        } catch (Exception e) {
            log.debug("MCP stdio 通知发送失败: {}", e.getMessage());
        }
    }

    private void failAll(String reason) {
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(new IOException(reason));
        }
        pending.clear();
    }
}
