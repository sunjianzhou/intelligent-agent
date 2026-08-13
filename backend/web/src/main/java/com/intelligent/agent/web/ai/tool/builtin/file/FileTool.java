package com.intelligent.agent.web.ai.tool.builtin.file;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件操作工具（TODO-110 Task 1）：read/write/list/create/delete/copy/move/info/exists。
 * 安全：路径必须落在配置的安全目录内（绝对化 + normalize 前缀校验），
 * 越界一律拒绝（对齐 Python FileTool + TODO-76 路径安全要求）。
 */
public class FileTool implements AgentTool {

    private final List<Path> safeDirectories;

    public FileTool(List<Path> safeDirectories) {
        this.safeDirectories = safeDirectories.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .toList();
    }

    public FileTool() {
        this(List.of(Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath()));
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "file_tool", "文件和目录操作。action: read(读取全文), write(写入), list(列目录),"
                        + " create, delete, copy, move, info, exists。参数: action, path, content(写时必填)",
                false, null, null,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of("type", "string",
                                        "enum", List.of("read", "write", "list", "create",
                                                "delete", "copy", "move", "info", "exists")),
                                "path", Map.of("type", "string", "description", "文件或目录路径"),
                                "content", Map.of("type", "string", "description", "写入内容（write 时必填）"),
                                "destination", Map.of("type", "string", "description", "目标路径（copy/move 时必填）"),
                                "mode", Map.of("type", "string", "enum", List.of("text", "binary"),
                                        "description", "读写模式，默认 text")),
                        "required", List.of("action", "path")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String action = String.valueOf(arguments.getOrDefault("action", ""));
        String path = String.valueOf(arguments.getOrDefault("path", ""));
        if (path.isEmpty()) {
            return Map.of("error", "path 不能为空");
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        if (!isSafe(target)) {
            return Map.of("error", "路径不在安全目录内: " + path);
        }
        try {
            return switch (action) {
                case "read" -> read(target, arguments);
                case "write" -> write(target, arguments);
                case "list" -> list(target);
                case "create" -> create(target);
                case "delete" -> delete(target);
                case "copy" -> copy(target, String.valueOf(arguments.getOrDefault("destination", "")));
                case "move" -> move(target, String.valueOf(arguments.getOrDefault("destination", "")));
                case "info" -> info(target);
                case "exists" -> Map.of("path", path, "exists", Files.exists(target));
                default -> Map.of("error", "不支持的操作: " + action);
            };
        } catch (Exception e) {
            return Map.of("error", action + " 失败: " + e.getMessage());
        }
    }

    private boolean isSafe(Path target) {
        for (Path safe : safeDirectories) {
            if (target.startsWith(safe)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> read(Path path, Map<String, Object> arguments) throws IOException {
        String mode = String.valueOf(arguments.getOrDefault("mode", "text"));
        Object content = switch (mode) {
            case "json" -> new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Files.readString(path, StandardCharsets.UTF_8), Object.class);
            default -> Files.readString(path, StandardCharsets.UTF_8);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path.toString());
        result.put("content", content);
        return result;
    }

    private Map<String, Object> write(Path path, Map<String, Object> arguments) throws IOException {
        String content = String.valueOf(arguments.getOrDefault("content", ""));
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return Map.of("path", path.toString(), "written", content.length());
    }

    private Map<String, Object> list(Path dir) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path entry : stream.sorted().toList()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getFileName().toString());
                    item.put("is_dir", Files.isDirectory(entry));
                    files.add(item);
                }
            }
        }
        return Map.of("path", dir.toString(), "files", files);
    }

    private Map<String, Object> create(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.createFile(path);
        return Map.of("path", path.toString(), "created", true);
    }

    private Map<String, Object> delete(Path path) throws IOException {
        boolean deleted = Files.deleteIfExists(path);
        return Map.of("path", path.toString(), "deleted", deleted);
    }

    private Map<String, Object> copy(Path source, String destination) throws IOException {
        Path dest = Path.of(destination).toAbsolutePath().normalize();
        if (!isSafe(dest)) {
            return Map.of("error", "目标路径不在安全目录内: " + destination);
        }
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return Map.of("from", source.toString(), "to", dest.toString());
    }

    private Map<String, Object> move(Path source, String destination) throws IOException {
        Path dest = Path.of(destination).toAbsolutePath().normalize();
        if (!isSafe(dest)) {
            return Map.of("error", "目标路径不在安全目录内: " + destination);
        }
        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return Map.of("from", source.toString(), "to", dest.toString());
    }

    private Map<String, Object> info(Path path) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path.toString());
        result.put("exists", Files.exists(path));
        if (Files.exists(path)) {
            result.put("is_dir", Files.isDirectory(path));
            result.put("size_bytes", Files.size(path));
            result.put("last_modified", Files.getLastModifiedTime(path).toString());
        }
        return result;
    }
}
