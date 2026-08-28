package com.intelligent.agent.web.ai.tool.builtin.file;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 受控文件编辑工具（R-08）：write/append/create/delete/copy/move。
 * <p>
 * 安全模型：
 * <ul>
 *   <li>路径必须落在配置的安全目录内（绝对化 + normalize 前缀校验 + 符号链接真实路径校验），
 *       越界/逃逸一律拒绝；</li>
 *   <li>所有写操作标记 {@code approvalRequired=true}，经 HITL 审批门（web/WS 卡片 / 飞书卡片）
 *       确认后才真正执行；</li>
 *   <li>只读 diff 预览放在 {@link FileTool#preview}，模型应在修改前先预览。</li>
 * </ul>
 */
public class FileEditTool implements AgentTool {

    private final List<Path> safeDirectories;

    public FileEditTool(List<Path> safeDirectories) {
        this.safeDirectories = safeDirectories == null ? List.of()
                : safeDirectories.stream()
                .filter(java.util.Objects::nonNull)
                .map(p -> p.toAbsolutePath().normalize())
                .toList();
    }

    public FileEditTool() {
        this(List.of(Path.of("").toAbsolutePath()));
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "file_edit_tool",
                "受控文件编辑（R-08）：action: write(写入/覆盖), append(追加), create(创建空文件),"
                        + " delete(删除文件), copy, move。参数: action, path, content(write/append 必填),"
                        + " destination(copy/move 必填)。"
                        + " 所有修改都需要用户审批；执行任何修改前，请先调用 file_tool 的 preview action"
                        + " 查看变更预览。路径必须位于安全目录内。",
                false, null, Duration.ofSeconds(30),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of("type", "string",
                                        "enum", List.of("write", "append", "create",
                                                "delete", "copy", "move")),
                                "path", Map.of("type", "string", "description", "文件路径（必须位于安全目录）"),
                                "content", Map.of("type", "string", "description", "写入/追加内容"),
                                "destination", Map.of("type", "string", "description", "目标路径（copy/move 必填）")),
                        "required", List.of("action", "path")),
                true);
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String action = String.valueOf(arguments.getOrDefault("action", ""));
        String path = String.valueOf(arguments.getOrDefault("path", ""));
        if (path.isEmpty()) {
            return Map.of("error", "path 不能为空");
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        if (!isSafeTarget(target)) {
            return Map.of("error", "路径不在安全目录内: " + path);
        }
        try {
            return switch (action) {
                case "write" -> write(target, String.valueOf(arguments.getOrDefault("content", "")));
                case "append" -> append(target, String.valueOf(arguments.getOrDefault("content", "")));
                case "create" -> create(target);
                case "delete" -> delete(target);
                case "copy" -> copy(target, String.valueOf(arguments.getOrDefault("destination", "")));
                case "move" -> move(target, String.valueOf(arguments.getOrDefault("destination", "")));
                default -> Map.of("error", "不支持的操作: " + action);
            };
        } catch (Exception e) {
            return Map.of("error", action + " 失败: " + e.getMessage());
        }
    }

    private Map<String, Object> write(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String existing = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        if (existing.equals(content)) {
            return Map.of("path", path.toString(), "written", 0, "changed", false);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return Map.of("path", path.toString(), "written", content.length(), "changed", true);
    }

    private Map<String, Object> append(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        if (!Files.exists(path)) {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return Map.of("path", path.toString(), "appended", content.length(), "created", true);
        }
        String existing = Files.readString(path, StandardCharsets.UTF_8);
        String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
        Files.writeString(path, existing + separator + content, StandardCharsets.UTF_8);
        return Map.of("path", path.toString(), "appended", content.length(), "created", false);
    }

    private Map<String, Object> create(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        if (Files.exists(path)) {
            return Map.of("path", path.toString(), "created", false, "error", "文件已存在");
        }
        Files.createFile(path);
        return Map.of("path", path.toString(), "created", true);
    }

    private Map<String, Object> delete(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return Map.of("error", "delete 仅支持文件，目录请逐文件处理: " + path);
        }
        boolean deleted = Files.deleteIfExists(path);
        return Map.of("path", path.toString(), "deleted", deleted);
    }

    private Map<String, Object> copy(Path source, String destination) throws IOException {
        Path dest = Path.of(destination).toAbsolutePath().normalize();
        if (!isSafeTarget(dest)) {
            return Map.of("error", "目标路径不在安全目录内: " + destination);
        }
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return Map.of("from", source.toString(), "to", dest.toString());
    }

    private Map<String, Object> move(Path source, String destination) throws IOException {
        Path dest = Path.of(destination).toAbsolutePath().normalize();
        if (!isSafeTarget(dest)) {
            return Map.of("error", "目标路径不在安全目录内: " + destination);
        }
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return Map.of("from", source.toString(), "to", dest.toString());
    }

    /**
     * 双重校验：先按 normalize 后绝对路径做前缀检查，再对已存在路径解析真实路径
     * （防符号链接逃逸）；新建文件时校验其父目录的真实路径。
     */
    private boolean isSafeTarget(Path raw) {
        Path abs = raw.toAbsolutePath().normalize();
        if (!isSafe(abs)) {
            return false;
        }
        try {
            if (Files.exists(abs)) {
                return isSafe(abs.toRealPath());
            }
            Path parent = abs.getParent();
            if (parent != null && Files.exists(parent)) {
                return isSafe(parent.toRealPath().resolve(abs.getFileName()));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isSafe(Path target) {
        if (safeDirectories.isEmpty()) {
            return false;
        }
        for (Path safe : safeDirectories) {
            if (target.startsWith(safe)) {
                return true;
            }
        }
        return false;
    }
}
