package com.intelligent.agent.web.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Agent 运行追踪服务（G4）：
 * <ul>
 *   <li>进行中的 trace 在内存聚合 spans；完成后原子落盘 {@code data/traces/}；</li>
 *   <li>容量上限（默认 500 条）按文件修改时间淘汰最旧；</li>
 *   <li>list/get 均按 userId 隔离；可选 OTLP/HTTP 导出（{@link OtlpTraceExporter}）。</li>
 * </ul>
 */
@Slf4j
public class TraceService {

    public static final int DEFAULT_MAX_TRACES = 500;

    private final Path tracesDir;
    private final int maxTraces;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final OtlpTraceExporter exporter;

    /** 进行中的 trace：requestId → 聚合器（spans 追加）。 */
    private final Map<String, MutableTrace> active = new ConcurrentHashMap<>();
    /** 已落盘 trace 索引（lastModified, name 升序），避免每次完成都全目录扫描。 */
    private final TreeMap<Path, Long> traceFiles = new TreeMap<>(
            Comparator.comparingLong((Path p) -> p.toFile().lastModified())
                    .thenComparing(p -> p.getFileName().toString()));

    public TraceService(Path dataDir) {
        this(dataDir, DEFAULT_MAX_TRACES, null);
    }

    public TraceService(Path dataDir, int maxTraces) {
        this(dataDir, maxTraces, null);
    }

    public TraceService(Path dataDir, int maxTraces, OtlpTraceExporter exporter) {
        this.tracesDir = dataDir.resolve("traces");
        this.maxTraces = maxTraces > 0 ? maxTraces : DEFAULT_MAX_TRACES;
        this.exporter = exporter;
        try {
            Files.createDirectories(tracesDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 traces 目录: " + tracesDir, e);
        }
        loadIndex();
    }

    /** 开始一次追踪；requestId 为空时自动生成。 */
    public String begin(String requestId, String userId, String sessionId,
                        String channel, String model) {
        String id = requestId == null || requestId.isBlank()
                ? "trace-" + Long.toHexString(System.nanoTime()) : requestId;
        MutableTrace trace = new MutableTrace(userId, sessionId, channel, model);
        active.put(id, trace);
        return id;
    }

    public void addSpan(String requestId, TraceSpan span) {
        if (requestId == null) {
            return;
        }
        MutableTrace trace = active.get(requestId);
        if (trace != null && span != null) {
            trace.spans.add(span);
        }
    }

    /** 结束追踪：组装 + 落盘 + 从活动表移除 + 容量淘汰。 */
    public void complete(String requestId, String status) {
        if (requestId == null) {
            return;
        }
        MutableTrace trace = active.remove(requestId);
        if (trace == null) {
            return;
        }
        long durationMs = System.currentTimeMillis() - trace.startedAt.toEpochMilli();
        AgentRunTrace finished = new AgentRunTrace(
                requestId, trace.userId, trace.sessionId, trace.channel, trace.model,
                trace.startedAt, durationMs,
                status == null || status.isBlank() ? "ok" : status,
                List.copyOf(trace.spans));
        persist(finished);
        pruneIfNeeded();
        if (exporter != null) {
            exporter.export(finished);
        }
    }

    /** 最近 N 条（按完成时间倒序，userId 隔离）。 */
    public List<Map<String, Object>> list(String userId, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        int take = Math.max(1, Math.min(limit, 500));
        List<Path> files = new ArrayList<>(take);
        var it = traceFiles.descendingMap().entrySet().iterator();
        while (it.hasNext() && files.size() < take) {
            files.add(it.next().getKey());
        }
        for (Path file : files) {
            AgentRunTrace trace = readFile(file);
            if (trace != null && (userId == null || userId.isBlank()
                    || userId.equals(trace.userId()))) {
                result.add(summary(trace));
            }
        }
        return result;
    }

    /** 单条完整 trace（userId 隔离）。 */
    public Map<String, Object> get(String userId, String requestId) {
        AgentRunTrace trace = readFile(fileOf(requestId));
        if (trace == null) {
            return null;
        }
        if (userId != null && !userId.isBlank() && !userId.equals(trace.userId())) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("request_id", trace.requestId());
        map.put("user_id", trace.userId());
        map.put("session_id", trace.sessionId());
        map.put("channel", trace.channel());
        map.put("model", trace.model());
        map.put("started_at", trace.startedAt().toString());
        map.put("duration_ms", trace.durationMs());
        map.put("status", trace.status());
        map.put("spans", trace.spans());
        return map;
    }

    public boolean delete(String userId, String requestId) {
        Map<String, Object> existing = get(userId, requestId);
        if (existing == null) {
            return false;
        }
        try {
            Path file = fileOf(requestId);
            boolean deleted = Files.deleteIfExists(file);
            traceFiles.remove(file);
            return deleted;
        } catch (IOException e) {
            log.warn("trace 删除失败 {}: {}", requestId, e.getMessage());
            return false;
        }
    }

    public int activeCount() {
        return active.size();
    }

    // ── 内部 ────────────────────────────────────────────────

    private static final class MutableTrace {
        final String userId;
        final String sessionId;
        final String channel;
        final String model;
        final Instant startedAt = Instant.now();
        final List<TraceSpan> spans = new ArrayList<>();

        MutableTrace(String userId, String sessionId, String channel, String model) {
            this.userId = userId == null ? "" : userId;
            this.sessionId = sessionId;
            this.channel = channel;
            this.model = model;
        }
    }

    private void persist(AgentRunTrace trace) {
        try {
            Files.createDirectories(tracesDir);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("request_id", trace.requestId());
            data.put("user_id", trace.userId());
            data.put("session_id", trace.sessionId());
            data.put("channel", trace.channel());
            data.put("model", trace.model());
            data.put("started_at", trace.startedAt().toString());
            data.put("duration_ms", trace.durationMs());
            data.put("status", trace.status());
            data.put("spans", trace.spans());
            Path file = fileOf(trace.requestId());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            traceFiles.put(file, file.toFile().lastModified());
        } catch (Exception e) {
            log.warn("trace 落盘失败 {}: {}", trace.requestId(), e.getMessage(), e);
        }
    }

    private AgentRunTrace readFile(Path file) {
        try {
            Map<String, Object> data = mapper.readValue(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            List<TraceSpan> spans = new ArrayList<>();
            if (data.get("spans") instanceof List) {
                for (Object span : (List<?>) data.get("spans")) {
                    spans.add(mapper.convertValue(span, TraceSpan.class));
                }
            }
            return new AgentRunTrace(
                    str(data.get("request_id")),
                    str(data.get("user_id")),
                    str(data.get("session_id")),
                    str(data.get("channel")),
                    str(data.get("model")),
                    instant(data.get("started_at")),
                    num(data.get("duration_ms")),
                    str(data.get("status")),
                    spans);
        } catch (Exception e) {
            log.warn("trace 读取失败 {}: {}", file, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> summary(AgentRunTrace trace) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("request_id", trace.requestId());
        map.put("user_id", trace.userId());
        map.put("channel", trace.channel());
        map.put("model", trace.model());
        map.put("started_at", trace.startedAt().toString());
        map.put("duration_ms", trace.durationMs());
        map.put("status", trace.status());
        map.put("span_count", trace.spans().size());
        return map;
    }

    private void pruneIfNeeded() {
        int excess = traceFiles.size() - maxTraces;
        var it = traceFiles.entrySet().iterator();
        int removed = 0;
        while (it.hasNext() && removed < excess) {
            Path file = it.next().getKey();
            it.remove();
            try {
                if (Files.deleteIfExists(file)) {
                    removed++;
                }
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** 启动时把既有 trace 文件装入内存索引，之后 list/prune 不再扫描目录。 */
    private void loadIndex() {
        try (Stream<Path> files = Files.list(tracesDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> traceFiles.put(p, p.toFile().lastModified()));
        } catch (IOException e) {
            log.warn("traces 索引加载失败（以空索引启动）: {}", e.getMessage());
        }
    }

    private Path fileOf(String requestId) {
        String safe = requestId == null ? "trace"
                : requestId.replaceAll("[^A-Za-z0-9_.:@\\-]", "_");
        return tracesDir.resolve(safe + ".json");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Instant instant(Object value) {
        return value == null ? Instant.now() : Instant.parse(String.valueOf(value));
    }
}
