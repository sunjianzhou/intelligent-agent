package com.intelligent.agent.web.infrastructure.observability;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTLP/HTTP 追踪导出契约（G4）：默认关闭不发请求；开启后按 /v1/traces 推送
 * JSON 载荷并带 OpenInference 语义属性；Collector 报错不抛异常。
 */
class OtlpTraceExporterTest {

    private MockWebServer otlpServer;

    @BeforeEach
    void setUp() throws Exception {
        otlpServer = new MockWebServer();
        otlpServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        otlpServer.shutdown();
    }

    private static AgentRunTrace sampleTrace() {
        return new AgentRunTrace("req-otlp", "alice", "s1", "web", "qwen2.5:7b",
                Instant.parse("2026-08-21T10:00:00Z"), 1234, "ok", List.of(
                TraceSpan.ok("llm_call", 1000, 500,
                        Map.of("model", "qwen2.5:7b", "stream", true)),
                TraceSpan.ok("tool_call", 1600, 200,
                        Map.of("tool", "calculator", "args", "{\"expr\":\"1+1\"}",
                                "status", "success")),
                TraceSpan.ok("rag", 2000, 50, Map.of("recall", 2, "history", 4))));
    }

    @Test
    void disabledExporterSendsNothing() throws Exception {
        OtlpTraceExporter exporter = new OtlpTraceExporter(
                false, otlpServer.url("/").toString(), Duration.ofSeconds(1));

        exporter.exportSync(sampleTrace());

        assertThat(otlpServer.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void exportPostsOtlpJsonWithOpenInferenceAttributes() throws Exception {
        otlpServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        OtlpTraceExporter exporter = new OtlpTraceExporter(
                true, otlpServer.url("/").toString(), Duration.ofSeconds(2));

        exporter.exportSync(sampleTrace());

        RecordedRequest request = otlpServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/traces");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"traceId\"")
                .contains("\"openinference.span.kind\"")
                .contains("\"TOOL\"")
                .contains("\"LLM\"")
                .contains("\"RETRIEVER\"")
                .contains("\"agent.run\"")
                .contains("\"ai.trace.recall\"");
    }

    @Test
    void exportToleratesServerError() throws Exception {
        otlpServer.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        OtlpTraceExporter exporter = new OtlpTraceExporter(
                true, otlpServer.url("/").toString(), Duration.ofSeconds(2));

        exporter.exportSync(sampleTrace());

        assertThat(otlpServer.takeRequest().getPath()).isEqualTo("/v1/traces");
        // 到达此处即视为未抛异常
    }
}
