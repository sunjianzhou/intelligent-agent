package com.intelligent.agent.web.ai.tool.builtin.file;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读文件操作工具（TODO-110 Task 1 + R-08 收敛）：read/list/info/exists + preview(diff 预览)。
 * 安全：路径必须落在配置的安全目录内（绝对化 + normalize 前缀校验），
 * 越界一律拒绝（对齐 Python FileTool + TODO-76 路径安全要求）。
 * <p>写操作已拆到 {@link FileEditTool}（需审批）；本工具保持纯只读。</p>
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
                "file_tool", "只读文件和目录操作。action: read(读取全文), list(列目录),"
                        + " info, exists, preview(变更预览 diff)。"
                        + " preview 参数: path + action(write/append/delete) + content(write/append 时提供)，"
                        + " 返回 unified diff 但不做任何修改。"
                        + " 若要实际写入/删除/移动文件，请使用 file_edit_tool（需用户审批）。",
                true, null, null,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of("type", "string",
                                        "enum", List.of("read", "list", "info", "exists", "preview")),
                                "path", Map.of("type", "string", "description", "文件或目录路径"),
                                "content", Map.of("type", "string", "description", "预览用内容（preview 的 write/append 时提供）"),
                                "preview_action", Map.of("type", "string",
                                        "enum", List.of("write", "append", "delete"),
                                        "description", "preview 要预览的操作类型"),
                                "mode", Map.of("type", "string", "enum", List.of("text", "json"),
                                        "description", "读取模式，默认 text")),
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
                case "list" -> list(target);
                case "info" -> info(target);
                case "exists" -> Map.of("path", path, "exists", Files.exists(target));
                case "preview" -> preview(target, arguments);
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

    private Map<String, Object> list(Path dir) throws IOException {
        List<Map<String, Object>> files = new java.util.ArrayList<>();
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

    /**
     * 变更预览（只读）：对 write/append/delete 生成 unified diff，不落盘。
     * 超大文件（> 512KB）拒绝预览，避免把整个文件读进上下文。
     */
    private Map<String, Object> preview(Path path, Map<String, Object> arguments) throws IOException {
        String previewAction = String.valueOf(arguments.getOrDefault("preview_action", "write"));
        if (Files.exists(path) && Files.size(path) > 512 * 1024) {
            return Map.of("error", "文件超过 512KB，跳过预览: " + path);
        }
        String before = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        String after;
        switch (previewAction) {
            case "delete" -> after = "";
            case "append" -> {
                String content = String.valueOf(arguments.getOrDefault("content", ""));
                String separator = before.isEmpty() || before.endsWith("\n") ? "" : "\n";
                after = before + separator + content;
            }
            default -> after = String.valueOf(arguments.getOrDefault("content", ""));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path.toString());
        result.put("preview_action", previewAction);
        result.put("diff", UnifiedDiff.of(path.toString(), before, after));
        result.put("changed", !before.equals(after));
        return result;
    }
}
