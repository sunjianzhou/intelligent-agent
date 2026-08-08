package com.intelligent.agent.web.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shadow 对比记录器（Plan 3 / Task 5）：
 * 记录最近 N 次对比，输出 JSON 前强制脱敏：
 * <ul>
 *   <li>删除/置空 Authorization、token、secret、password、api_key、jwt 等键；</li>
 *   <li>任何包含 "Bearer " 或私密 prompt 标记的字符串值 → [redacted]。</li>
 * </ul>
 */
@Slf4j
public class ShadowComparisonRecorder {

    public static final int DEFAULT_MAX_HISTORY = 200;

    private final List<ShadowComparison> history = new CopyOnWriteArrayList<>();
    private final int maxHistory;

    public ShadowComparisonRecorder() {
        this(DEFAULT_MAX_HISTORY);
    }

    public ShadowComparisonRecorder(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    public ShadowComparison record(ShadowComparison comparison) {
        history.add(0, comparison);
        while (history.size() > maxHistory) {
            history.remove(history.size() - 1);
        }
        log.debug("shadow 对比已记录: toolTraceHash={}, latencyMs={}",
                comparison.toolTraceHash(), comparison.latencyMs());
        return comparison;
    }

    public List<ShadowComparison> history() {
        return List.copyOf(history);
    }

    public void clear() {
        history.clear();
    }

    static String toJson(ShadowComparison comparison) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("python_wire", redactValue(comparison.pythonWire()));
        out.put("java_wire", redactValue(comparison.javaWire()));
        out.put("tool_trace_hash", comparison.toolTraceHash());
        out.put("retrieval_ids", comparison.retrievalIds() == null ? List.of() : comparison.retrievalIds());
        out.put("latency_ms", comparison.latencyMs());
        try {
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    static String redactValue(String json) {
        if (json == null) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Object node = mapper.readValue(json, Object.class);
            return mapper.writeValueAsString(redactNode(node));
        } catch (Exception e) {
            String s = json.replaceAll("Bearer\\s+\\S+", "[redacted]");
            return s.replace("private prompt", "[redacted]");
        }
    }

    @SuppressWarnings("unchecked")
    private static Object redactNode(Object node) {
        if (node instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (sensitiveKey(key)) {
                    out.put(key, "[redacted]");
                } else {
                    out.put(key, redactNode(value));
                }
            }
            return out;
        }
        if (node instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) node) {
                out.add(redactNode(item));
            }
            return out;
        }
        if (node instanceof String text) {
            return sensitiveValue(text) ? "[redacted]" : text;
        }
        return node;
    }

    private static boolean sensitiveKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("authorization") || k.contains("token") || k.contains("secret")
                || k.contains("password") || k.contains("api_key") || k.contains("apikey")
                || k.contains("credential") || k.contains("jwt");
    }

    private static boolean sensitiveValue(String text) {
        return text.contains("Bearer ") || text.contains("private prompt");
    }
}
