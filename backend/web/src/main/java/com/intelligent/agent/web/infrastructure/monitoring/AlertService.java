package com.intelligent.agent.web.infrastructure.monitoring;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 系统告警（R-13）：断路器打开 / 推理队列满等事件通过通知队列送达
 * （WebSocket 广播 + REST {@code /api/notifications/poll}），按类型限频避免告警风暴。
 */
public class AlertService {

    private final boolean enabled;
    private final Consumer<Map<String, Object>> sink;
    private final long minIntervalMillis;
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();

    public AlertService(boolean enabled, Consumer<Map<String, Object>> sink) {
        this(enabled, sink, 5 * 60_000L);
    }

    public AlertService(boolean enabled, Consumer<Map<String, Object>> sink,
                        long minIntervalMillis) {
        this.enabled = enabled;
        this.sink = sink;
        this.minIntervalMillis = Math.max(1000, minIntervalMillis);
    }

    /**
     * 触发告警；被限频或未启用时返回 false（不投递）。
     *
     * @param type    告警类型（同时作为限频 key）
     * @param message 人类可读消息
     * @param data    附加结构化数据（可空）
     */
    public boolean alert(String type, String message, Map<String, Object> data) {
        if (!enabled || type == null || type.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastSentAt.get(type);
        if (last != null && now - last < minIntervalMillis) {
            return false;
        }
        lastSentAt.put(type, now);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "alert");
        entry.put("alert_type", type);
        entry.put("message", message == null ? type : message);
        entry.put("timestamp", Instant.now().toString());
        if (data != null && !data.isEmpty()) {
            entry.put("data", data);
        }
        if (sink != null) {
            sink.accept(entry);
        }
        return true;
    }
}
