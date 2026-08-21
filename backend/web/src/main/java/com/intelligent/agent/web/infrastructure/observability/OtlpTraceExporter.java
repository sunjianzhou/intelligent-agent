package com.intelligent.agent.web.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OTel OTLP/HTTP（JSON）追踪导出（G4 预留位落地）。
 * <p>
 * 把本地落盘的 {@link AgentRunTrace} 转换为标准 OTLP/HTTP JSON 载荷（ExportTraceServiceRequest）
 * 推送到 Collector 的 {@code /v1/traces}（Jaeger / Tempo / Grafana / 任意 OTLP 端点），
 * 同时为每个 span 标注 OpenInference 语义属性（openinference.span.kind=LLM/TOOL/RETRIEVER/CHAIN/AGENT），
 * 可被 Phoenix / Arize 等 LLM 可观测平台直接消费。使用 JDK HttpClient + Jackson 手工组包，
 * 不引入 protobuf/OTel SDK 依赖；默认关闭，失败仅告警不影响主流程。
 */
@Slf4j
public class OtlpTraceExporter {

    private static final String SERVICE_NAME = "intelligent-agent";
    private static final int MAX_ATTR_CHARS = 2000;

    private final boolean enabled;
    private final String tracesEndpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor;

    public OtlpTraceExporter(boolean enabled, String endpoint, Duration timeout) {
        this.enabled = enabled;
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.tracesEndpoint = base + "/v1/traces";
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "otlp-trace-export");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 异步导出一条完整 trace；不阻塞调用方，失败仅告警。 */
    public void export(AgentRunTrace trace) {
        if (!enabled || trace == null) {
            return;
        }
        executor.execute(() -> exportSync(trace));
    }

    /** 同步导出（测试直接调用；生产走 {@link #export(AgentRunTrace)} 异步包装）。 */
    public void exportSync(AgentRunTrace trace) {
        if (!enabled || trace == null) {
            return;
        }
        try {
            byte[] body = mapper.writeValueAsBytes(toExportRequest(trace));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tracesEndpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OTLP 导出失败: HTTP {} (requestId={})",
                        response.statusCode(), trace.requestId());
            }
        } catch (Exception e) {
            log.warn("OTLP 导出异常 requestId={}: {}", trace.requestId(), e.getMessage());
        }
    }

    // ── OTLP/HTTP JSON 组包 ──────────────────────────────────────────

    static Map<String, Object> toExportRequest(AgentRunTrace trace) {
        Map<String, Object> resourceSpan = new LinkedHashMap<>();
        resourceSpan.put("resource", Map.of("attributes", List.of(
                attr("service.name", stringValue(SERVICE_NAME)),
                attr("telemetry.sdk.name", stringValue(SERVICE_NAME)))));
        Map<String, Object> scopeSpan = new LinkedHashMap<>();
        scopeSpan.put("scope", Map.of("name", SERVICE_NAME, "version", "1.0"));
        scopeSpan.put("spans", buildSpans(trace));
        resourceSpan.put("scopeSpans", List.of(scopeSpan));
        return Map.of("resourceSpans", List.of(resourceSpan));
    }

    private static List<Map<String, Object>> buildSpans(AgentRunTrace trace) {
        String traceId = hexDigest(trace.requestId() + ":" + trace.startedAt(), 16);
        String rootSpanId = spanId(trace.requestId() + ":root");
        List<Map<String, Object>> spans = new ArrayList<>();

        Map<String, Object> root = baseSpan(traceId, rootSpanId, null,
                "agent.run", 1, trace.startedAt(), trace.durationMs());
        List<Map<String, Object>> rootAttrs = new ArrayList<>();
        rootAttrs.add(attr("openinference.span.kind", stringValue("AGENT")));
        rootAttrs.add(attr("request_id", stringValue(trace.requestId())));
        rootAttrs.add(attr("user_id", stringValue(trace.userId())));
        rootAttrs.add(attr("session_id", stringValue(trace.sessionId())));
        rootAttrs.add(attr("channel", stringValue(trace.channel())));
        rootAttrs.add(attr("model", stringValue(trace.model())));
        root.put("attributes", rootAttrs);
        root.put("status", status(trace.status()));
        spans.add(root);

        for (TraceSpan span : trace.spans()) {
            Map<String, Object> s = baseSpan(traceId,
                    spanId(trace.requestId() + ":" + span.name() + ":" + span.startedAt()),
                    rootSpanId, span.name(), 1,
                    Instant.ofEpochMilli(span.startedAt()), span.durationMs());
            s.put("attributes", spanAttrs(span));
            s.put("status", status(span.status()));
            spans.add(s);
        }
        return spans;
    }

    private static List<Map<String, Object>> spanAttrs(TraceSpan span) {
        List<Map<String, Object>> attrs = new ArrayList<>();
        String oiKind = switch (span.name()) {
            case "llm_call" -> "LLM";
            case "tool_call" -> "TOOL";
            case "rag" -> "RETRIEVER";
            default -> "CHAIN";
        };
        attrs.add(attr("openinference.span.kind", stringValue(oiKind)));
        attrs.add(attr("openinference.span.status", stringValue(span.status())));
        Map<String, Object> details = span.details();
        switch (span.name()) {
            case "llm_call" -> {
                attrs.add(attr("openinference.llm.model_name", stringValue(str(details.get("model")))));
                if (details.get("stream") instanceof Boolean stream) {
                    attrs.add(attr("openinference.llm.invocation_parameters",
                            stringValue("stream=" + stream)));
                }
            }
            case "tool_call" -> {
                attrs.add(attr("openinference.tool.name", stringValue(str(details.get("tool")))));
                attrs.add(attr("openinference.tool.call_id",
                        stringValue("tool_call@" + span.startedAt())));
                attrs.add(attr("openinference.tool.status", stringValue(str(details.get("status")))));
                attrs.add(attr("openinference.input.value", stringValue(str(details.get("args")))));
            }
            case "rag" -> {
                attrs.add(attr("openinference.retriever.documents",
                        intValue(num(details.get("recall")))));
                attrs.add(attr("rag.history_count", intValue(num(details.get("history")))));
            }
            default -> { /* chain：泛型 details 原样透传 */ }
        }
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (attrs.stream().noneMatch(a -> entry.getKey().equals(a.get("key")))) {
                attrs.add(attr("ai.trace." + entry.getKey(), anyValue(entry.getValue())));
            }
        }
        return attrs;
    }

    private static Map<String, Object> baseSpan(String traceId, String spanId, String parentId,
                                                String name, int kind, Instant start, long durationMs) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", traceId);
        span.put("spanId", spanId);
        if (parentId != null) {
            span.put("parentSpanId", parentId);
        }
        span.put("name", name);
        span.put("kind", kind);
        long startNanos = start.toEpochMilli() * 1_000_000L;
        span.put("startTimeUnixNano", Long.toString(startNanos));
        span.put("endTimeUnixNano", Long.toString(startNanos
                + Math.max(0, durationMs) * 1_000_000L));
        return span;
    }

    private static Map<String, Object> status(String status) {
        boolean ok = status == null || status.isBlank() || "ok".equals(status);
        return ok ? Map.of("code", 1, "message", "OK")
                : Map.of("code", 2, "message", status);
    }

    private static Map<String, Object> attr(String key, Map<String, Object> value) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("key", key);
        attr.put("value", value);
        return attr;
    }

    private static Map<String, Object> stringValue(String s) {
        return Map.of("stringValue", truncate(s == null ? "" : s));
    }

    private static Map<String, Object> intValue(long v) {
        return Map.of("intValue", v);
    }

    private static Map<String, Object> anyValue(Object v) {
        if (v == null) {
            return stringValue("");
        }
        if (v instanceof String s) {
            return stringValue(s);
        }
        if (v instanceof Boolean b) {
            return Map.of("boolValue", b);
        }
        if (v instanceof Integer || v instanceof Long) {
            return intValue(((Number) v).longValue());
        }
        if (v instanceof Number n) {
            return Map.of("doubleValue", n.doubleValue());
        }
        if (v instanceof List<?> list) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Object item : list) {
                values.add(anyValue(item));
            }
            return Map.of("arrayValue", Map.of("values", values));
        }
        if (v instanceof Map<?, ?> m) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                values.add(attr(String.valueOf(entry.getKey()), anyValue(entry.getValue())));
            }
            return Map.of("kvlistValue", Map.of("values", values));
        }
        return stringValue(String.valueOf(v));
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ATTR_CHARS ? s : s.substring(0, MAX_ATTR_CHARS);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** 确定性 span id：requestId 维度的 8 字节 SHA-256 前缀。 */
    private static String spanId(String seed) {
        return hexDigest(seed, 8);
    }

    private static String hexDigest(String input, int bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[bytes];
            System.arraycopy(hash, 0, out, 0, bytes);
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            return "0".repeat(bytes * 2);
        }
    }
}
