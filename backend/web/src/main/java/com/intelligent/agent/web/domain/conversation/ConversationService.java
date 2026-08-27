package com.intelligent.agent.web.domain.conversation;

import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对话历史领域服务（Plan 2 / Task 3）：
 * 每轮对话持久化到 data/conversations/{user_id}/{session_id}.json，
 * 响应形状与 Python conversations_router 一致。
 */
@Slf4j
public class ConversationService {

    private static final int MAX_SESSIONS_LISTED = 100;
    private static final int MAX_RETRACT_BATCH = 50;
    /** 按用户分片的写锁：同用户串行化读-改-写，不同用户可并发，去掉全局串行瓶颈。 */
    private static final int LOCK_STRIPES = 64;
    private final Object[] sessionLocks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            sessionLocks[i] = new Object();
        }
    }

    private final JsonFileStore store;
    private final int maxMessages;

    public ConversationService(Path dataDir) {
        this(dataDir, 200);
    }

    public ConversationService(Path dataDir, int maxMessages) {
        this.store = new JsonFileStore(dataDir);
        this.maxMessages = maxMessages;
    }

    public Map<String, Object> listConversations(String userId) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Map<String, Object> data : store.listJson("conversations", userId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("session_id", data.get("session_id"));
            entry.put("created_at", data.getOrDefault("created_at", ""));
            entry.put("updated_at", data.getOrDefault("updated_at", ""));
            entry.put("message_count", list(data.get("messages")).size());
            // R-12：服务端标题（重命名后优先，未命名回退首条用户消息预览）
            Object title = data.get("title");
            entry.put("title", title == null || String.valueOf(title).isBlank()
                    ? preview(data) : String.valueOf(title));
            entry.put("preview", preview(data));
            if (data.get("parent_session_id") != null) {
                entry.put("parent_session_id", data.get("parent_session_id"));
            }
            if (data.get("project_id") != null) {
                entry.put("project_id", data.get("project_id"));
            }
            sessions.add(entry);
        }
        sessions.sort((a, b) -> String.valueOf(b.get("updated_at"))
                .compareTo(String.valueOf(a.get("updated_at"))));
        if (sessions.size() > MAX_SESSIONS_LISTED) {
            sessions = new ArrayList<>(sessions.subList(0, MAX_SESSIONS_LISTED));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("sessions", sessions);
        result.put("count", sessions.size());
        return result;
    }

    public Map<String, Object> getConversation(String userId, String sessionId) {
        Map<String, Object> session = load(userId, sessionId);
        if (session == null) {
            throw new NotFoundException("会话不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("session", session);
        return result;
    }

    /** R-12：服务端会话重命名（持久化 title 字段，列表/详情可见）。 */
    public Map<String, Object> renameConversation(String userId, String sessionId, String title) {
        synchronized (lockFor(userId)) {
            Map<String, Object> session = load(userId, sessionId);
            if (session == null) {
                throw new NotFoundException("会话不存在");
            }
            String clean = title == null ? "" : title.trim();
            if (clean.isBlank()) {
                throw new InvalidRequestException("标题不能为空");
            }
            if (clean.length() > 100) {
                clean = clean.substring(0, 100);
            }
            session.put("title", clean);
            session.put("updated_at", Instant.now().toString());
            store.writeCompact(new String[]{"conversations", userId, sessionId + ".json"}, session);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("session_id", sessionId);
            result.put("title", clean);
            return result;
        }
    }

    /** R-12：导出会话 JSON（含全部消息与元数据，跨设备可恢复）。 */
    public Map<String, Object> exportConversation(String userId, String sessionId) {
        Map<String, Object> session = load(userId, sessionId);
        if (session == null) {
            throw new NotFoundException("会话不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("session_id", sessionId);
        result.put("exported_at", Instant.now().toString());
        result.put("filename", sessionId + ".json");
        result.put("session", session);
        return result;
    }

    public Map<String, Object> deleteConversation(String userId, String sessionId) {
        if (!store.delete("conversations", userId, sessionId + ".json")) {
            throw new NotFoundException("会话不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("session_id", sessionId);
        return result;
    }

    public Map<String, Object> clearAllConversations(String userId) {
        int count = deleteAllFiles(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("deleted", count);
        return result;
    }

    public Map<String, Object> branchConversation(String userId, Map<String, Object> body) {
        String parentSessionId = body == null ? null : str(body.get("parent_session_id"));
        List<Map<String, Object>> messages = messagesOf(body == null ? null : body.get("messages"));
        String newSessionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("session_id", newSessionId);
        session.put("user_id", userId);
        session.put("created_at", now);
        session.put("updated_at", now);
        if (parentSessionId != null && !parentSessionId.isBlank()) {
            session.put("parent_session_id", parentSessionId);
        }
        session.put("messages", trimMessages(messages));
        store.writeCompact(new String[]{"conversations", userId, newSessionId + ".json"}, session);
        log.info("分支会话已创建: user={}, new={}, parent={}, msgs={}",
                userId, newSessionId, parentSessionId, messages.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("session_id", newSessionId);
        return result;
    }

    private Object lockFor(String userId) {
        return sessionLocks[(userId == null ? "" : userId).hashCode() & (LOCK_STRIPES - 1)];
    }

    /** 读-改-写同一会话文件：按用户分片锁防并发丢更新，不同用户不再互相阻塞。 */
    public Map<String, Object> append(String userId, String sessionId,
                                      List<Map<String, Object>> messages) {
        synchronized (lockFor(userId)) {
        String effectiveSessionId = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString() : sessionId;
        if (messages == null || messages.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("session_id", effectiveSessionId);
            return result;
        }

        Map<String, Object> session = load(userId, effectiveSessionId);
        if (session == null) {
            String now = Instant.now().toString();
            session = new LinkedHashMap<>();
            session.put("session_id", effectiveSessionId);
            session.put("user_id", userId);
            session.put("created_at", now);
            session.put("updated_at", now);
            session.put("messages", new ArrayList<Object>());
        }
        // 防御性拷贝：不修改调用方传入的 message（REST 反序列化/测试可能为不可变 map）
        List<Map<String, Object>> enriched = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            Map<String, Object> copy = new LinkedHashMap<>(message);
            if (copy.get("id") == null || String.valueOf(copy.get("id")).isBlank()) {
                copy.put("id", UUID.randomUUID().toString());
            }
            if (copy.get("timestamp") == null) {
                copy.put("timestamp", Instant.now().toString());
            }
            enriched.add(copy);
        }
        List<Object> existing = list(session.get("messages"));
        existing.addAll(enriched);
        session.put("messages", trimMessages(existing));
        session.put("updated_at", Instant.now().toString());
        store.writeCompact(new String[]{"conversations", userId, effectiveSessionId + ".json"}, session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("session_id", effectiveSessionId);
        return result;
        }
    }

    /** 读-改-写同一会话文件：按用户分片锁防并发丢更新，不同用户不再互相阻塞。 */
    public Map<String, Object> retract(String userId, String sessionId,
                                       List<String> requestedIds) {
        synchronized (lockFor(userId)) {
        List<String> dedup = new ArrayList<>(new LinkedHashSet<>(
                requestedIds == null ? List.of() : requestedIds));
        if (dedup.size() > MAX_RETRACT_BATCH) {
            throw new InvalidRequestException("单次最多撤回 " + MAX_RETRACT_BATCH + " 条，请分批操作");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("requested", dedup.size());
        result.put("deleted", 0);
        result.put("deleted_ids", List.of());
        result.put("memory_purged", 0);
        if (dedup.isEmpty()) {
            return result;
        }

        Map<String, Object> session = load(userId, sessionId);
        if (session == null) {
            return result;
        }
        Set<String> targets = new LinkedHashSet<>(dedup);
        List<Object> kept = new ArrayList<>();
        List<String> removedIds = new ArrayList<>();
        List<String> removedContents = new ArrayList<>();
        for (Object messageObj : list(session.get("messages"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messageObj;
            String id = String.valueOf(message.get("id"));
            if (targets.contains(id)) {
                removedIds.add(id);
                Object content = message.get("content");
                if (content != null && !String.valueOf(content).isBlank()) {
                    removedContents.add(String.valueOf(content));
                }
            } else {
                kept.add(message);
            }
        }
        session.put("messages", kept);
        session.put("updated_at", Instant.now().toString());
        store.writeCompact(new String[]{"conversations", userId, sessionId + ".json"}, session);

        result.put("deleted", removedIds.size());
        result.put("deleted_ids", removedIds);
        // 级联清理记忆用：被撤回消息的内容列表
        result.put("removed_contents", removedContents);
        return result;
        }
    }

    // ── 内部辅助 ──────────────────────────────────────────────

    private Map<String, Object> load(String userId, String sessionId) {
        return store.read("conversations", userId, sessionId + ".json");
    }

    private int deleteAllFiles(String userId) {
        java.io.File dir = store.baseDir().resolve("conversations").resolve(JsonFileStore.safe(userId)).toFile();
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (java.io.File file : files) {
            if (file.getName().endsWith(".json") && file.delete()) {
                count++;
            }
        }
        return count;
    }

    private static String preview(Map<String, Object> session) {
        for (Object messageObj : list(session.get("messages"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messageObj;
            if ("user".equals(message.get("role"))) {
                String content = String.valueOf(message.getOrDefault("content", ""));
                return content.length() > 80 ? content.substring(0, 80) : content;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messagesOf(Object value) {
        if (!(value instanceof List)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                messages.add(new LinkedHashMap<>((Map<String, Object>) item));
            }
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    private <T> List<T> trimMessages(List<T> messages) {
        if (messages.size() <= maxMessages) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }
}
