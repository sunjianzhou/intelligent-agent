package com.intelligent.agent.web.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 工具轮结果断点缓存（R-16）：按 {@code requestId + 工具调用签名} 暂存已执行结果。
 * <p>
 * 客户端中断流式后以相同 {@code request_id} 重发时，编排器复用缓存结果，
 * 跳过已执行的副作用工具（不再重复执行、不再重复审批）。进程内 TTL + 容量上限；
 * 请求成功完结后由编排器清理。
 */
public class ToolCheckpointStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final long ttlMillis;
    private final int maxEntries;
    private final LongSupplier nowMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ToolCheckpointStore() {
        this(true, 10 * 60_000L, 2000, System::currentTimeMillis);
    }

    public ToolCheckpointStore(boolean enabled, long ttlMillis, int maxEntries,
                               LongSupplier nowMillis) {
        this.enabled = enabled;
        this.ttlMillis = Math.max(1000, ttlMillis);
        this.maxEntries = Math.max(1, maxEntries);
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
    }

    /** 命中缓存返回结果；未命中/过期/禁用返回 empty。 */
    public Optional<ToolResult> get(String requestId, String signature) {
        if (!enabled || requestId == null || requestId.isBlank()
                || signature == null || signature.isBlank()) {
            return Optional.empty();
        }
        String key = key(requestId, signature);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (nowMillis.getAsLong() - entry.createdAt >= ttlMillis) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.result);
    }

    /** 暂存已执行工具的结果。 */
    public void put(String requestId, String signature, ToolResult result) {
        if (!enabled || requestId == null || requestId.isBlank()
                || signature == null || signature.isBlank() || result == null) {
            return;
        }
        evictExpired();
        String key = key(requestId, signature);
        if (!entries.containsKey(key) && entries.size() >= maxEntries) {
            evictOldest();
        }
        entries.put(key, new Entry(result, nowMillis.getAsLong()));
    }

    /** 请求成功完结后清理该 requestId 的全部断点。 */
    public void remove(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        String prefix = requestId + "|";
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 工具调用签名：工具名 + 参数规范 JSON（键排序，与参数顺序无关）。 */
    public static String signature(ToolCall call) {
        if (call == null) {
            return "";
        }
        return call.name() + ":" + canonicalJson(call.arguments());
    }

    private static String key(String requestId, String signature) {
        return requestId + "|" + signature;
    }

    private void evictExpired() {
        long now = nowMillis.getAsLong();
        entries.entrySet().removeIf(e -> now - e.getValue().createdAt >= ttlMillis);
    }

    private void evictOldest() {
        long oldest = Long.MAX_VALUE;
        String oldestKey = null;
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().createdAt < oldest) {
                oldest = e.getValue().createdAt;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            entries.remove(oldestKey);
        }
    }

    /** 参数规范 JSON：递归按键排序，保证同一工具调用（参数顺序不同）签名一致。 */
    private static String canonicalJson(Object value) {
        try {
            JsonNode node = value == null ? MAPPER.nullNode() : MAPPER.valueToTree(value);
            return MAPPER.writeValueAsString(sort(node));
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = new ObjectNode(JsonNodeFactory.instance);
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.set(e.getKey(), sort(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = new ArrayNode(JsonNodeFactory.instance);
            node.forEach(n -> array.add(sort(n)));
            return array;
        }
        return node;
    }

    private record Entry(ToolResult result, long createdAt) {
    }
}
