package com.intelligent.agent.client.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 本地会话持久化（Plan 3 / Task 2）：
 * JSON 会话文件（datas/session_YYYYMMDD_HHMMSS_<hex>.json），形状与
 * Python ChatSession 兼容：{session_id, user_id, created_at, updated_at, messages}。
 */
public class SessionStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path dataDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionStore(Path dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话目录: " + dataDir, e);
        }
    }

    public Map<String, Object> newSession(String userId) {
        String now = LocalDateTime.now().toString();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("session_id", UUID.randomUUID().toString().replace("-", ""));
        session.put("user_id", userId == null ? "cli-user" : userId);
        session.put("created_at", now);
        session.put("updated_at", now);
        session.put("messages", new ArrayList<Map<String, Object>>());
        return session;
    }

    public void append(Map<String, Object> session, String role, String content) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) session.getOrDefault("messages", new ArrayList<>());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "m_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        message.put("role", role);
        message.put("content", content);
        message.put("timestamp", LocalDateTime.now().toString());
        messages.add(message);
        session.put("messages", messages);
        session.put("updated_at", LocalDateTime.now().toString());
    }

    public Path save(Map<String, Object> session) throws IOException {
        String filename = "session_" + LocalDateTime.now().format(STAMP) + "_"
                + String.valueOf(session.get("session_id")).substring(0, 8) + ".json";
        Path path = dataDir.resolve(filename);
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(session), StandardCharsets.UTF_8);
        return path;
    }

    public List<Path> list() throws IOException {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (var stream = Files.list(dataDir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> b.getFileName().toString()
                            .compareTo(a.getFileName().toString()))
                    .toList();
            return files;
        }
    }

    public Map<String, Object> load(Path path) throws IOException {
        return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }
}
