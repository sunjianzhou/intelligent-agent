package com.intelligent.agent.web.infrastructure.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 领域服务的 JSON 文件存储（infrastructure.filesystem）。
 * <p>
 * 所有路径片段经 {@link #safe(String)} 净化，且解析后必须落在 baseDir 内，
 * 防止 user_id / role_id / project_id / session_id 路径穿越（对应 Python 侧
 * TODO-76 一类历史欠账，Java 侧从第一天起收紧）。
 */
@Slf4j
public class JsonFileStore {

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public JsonFileStore(Path baseDir) {
        this(baseDir, new ObjectMapper());
    }

    public JsonFileStore(Path baseDir, ObjectMapper objectMapper) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + this.baseDir, e);
        }
    }

    public Path baseDir() {
        return baseDir;
    }

    public Map<String, Object> read(String... parts) {
        Path path = resolve(parts);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IOException e) {
            log.warn("读取 JSON 文件失败 {}: {}", path, e.getMessage());
            return null;
        }
    }

    public void write(String[] parts, Map<String, Object> data) {
        Path path = resolve(parts);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data == null ? Map.of() : data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 JSON 文件失败 " + path, e);
        }
    }

    public boolean delete(String... parts) {
        Path path = resolve(parts);
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除 JSON 文件失败 {}: {}", path, e.getMessage());
            return false;
        }
    }

    /** 列出目录下所有 JSON 文件内容（跳过解析失败项）。 */
    public List<Map<String, Object>> listJson(String... dirParts) {
        Path dir = resolve(dirParts);
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        Map<String, Object> data = readAbsolute(p);
                        if (data != null) {
                            result.add(data);
                        }
                    });
        } catch (IOException e) {
            log.warn("列出 JSON 文件失败 {}: {}", dir, e.getMessage());
        }
        return result;
    }

    private Map<String, Object> readAbsolute(Path path) {
        try {
            return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IOException e) {
            log.warn("读取 JSON 文件失败 {}: {}", path, e.getMessage());
            return null;
        }
    }

    private Path resolve(String... parts) {
        Path path = baseDir;
        for (String part : parts) {
            path = path.resolve(safe(part));
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法路径: " + String.join("/", parts));
        }
        return normalized;
    }

    /** 净化标识符：仅保留安全字符并消除 ".." 序列。 */
    public static String safe(String part) {
        String cleaned = part == null ? "_" : part.trim();
        cleaned = cleaned.replaceAll("[^A-Za-z0-9_.@:\\-]", "_");
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "_");
        }
        return cleaned.isBlank() ? "_" : cleaned;
    }
}
