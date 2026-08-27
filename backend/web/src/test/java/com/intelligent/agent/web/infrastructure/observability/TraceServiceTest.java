package com.intelligent.agent.web.infrastructure.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 运行追踪服务契约（G4）：begin/addSpan/complete 落盘、list/get 按用户隔离、
 * 删除、容量上限淘汰。
 */
class TraceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void completedTraceIsPersistedAndReadable() throws Exception {
        TraceService service = new TraceService(tempDir);
        service.begin("req-1", "alice", "s1", "web", "qwen2.5:7b");
        service.addSpan("req-1", TraceSpan.ok("llm_call", 1, 20, Map.of("model", "qwen2.5:7b")));
        service.addSpan("req-1", TraceSpan.ok("rag", 2, 5, Map.of("recall", 3)));

        service.complete("req-1", "ok");

        assertThat(Files.exists(tempDir.resolve("traces/req-1.json"))).isTrue();
        Map<String, Object> trace = service.get("alice", "req-1");
        assertThat(trace).isNotNull();
        assertThat(trace.get("request_id")).isEqualTo("req-1");
        assertThat(trace.get("user_id")).isEqualTo("alice");
        assertThat(trace.get("status")).isEqualTo("ok");
        assertThat(trace.get("spans")).asList().hasSize(2);
    }

    @Test
    void completeNotifiesUsageSinkWithFinishedTrace() {
        AtomicReference<AgentRunTrace> received = new AtomicReference<>();
        TraceService service = new TraceService(tempDir, 100, null, received::set);
        service.begin("req-u", "u1", "s1", "web", "deepseek-chat");
        service.addSpan("req-u", TraceSpan.ok("llm_call", 1, 20, Map.of(
                "model", "deepseek-chat", "input_tokens", 120, "output_tokens", 30)));

        service.complete("req-u", "ok");

        assertThat(received.get()).isNotNull();
        assertThat(received.get().requestId()).isEqualTo("req-u");
        assertThat(received.get().userId()).isEqualTo("u1");
        assertThat(received.get().spans()).hasSize(1);
    }

    @Test
    void listIsScopedByUser() {
        TraceService service = new TraceService(tempDir);
        service.begin("req-a", "alice", null, "web", null);
        service.complete("req-a", "ok");
        service.begin("req-b", "bob", null, "web", null);
        service.complete("req-b", "ok");

        assertThat(service.list("alice", 50)).hasSize(1);
        assertThat(service.list("alice", 50).get(0).get("request_id")).isEqualTo("req-a");
        assertThat(service.list("bob", 50)).hasSize(1);
    }

    @Test
    void getRejectsOtherUsersTrace() {
        TraceService service = new TraceService(tempDir);
        service.begin("req-x", "alice", null, "web", null);
        service.complete("req-x", "ok");

        assertThat(service.get("bob", "req-x")).isNull();
        assertThat(service.delete("bob", "req-x")).isFalse();
        assertThat(service.get("alice", "req-x")).isNotNull();
        assertThat(service.delete("alice", "req-x")).isTrue();
        assertThat(service.get("alice", "req-x")).isNull();
    }

    @Test
    void capacityPrunesOldestTraces() {
        TraceService service = new TraceService(tempDir, 2);
        service.begin("r1", "alice", null, "web", null);
        service.complete("r1", "ok");
        service.begin("r2", "alice", null, "web", null);
        service.complete("r2", "ok");
        service.begin("r3", "alice", null, "web", null);
        service.complete("r3", "ok");

        assertThat(service.list("alice", 50)).hasSize(2);
        assertThat(service.list("alice", 50).get(0).get("request_id")).isEqualTo("r3");
    }

    @Test
    void completeInvokesExporterWhenConfigured() {
        RecordingExporter exporter = new RecordingExporter();
        TraceService service = new TraceService(tempDir, 10, exporter);
        service.begin("req-exp", "alice", "s1", "web", "qwen2.5:7b");
        service.addSpan("req-exp",
                TraceSpan.ok("llm_call", 1, 10, Map.of("model", "qwen2.5:7b")));

        service.complete("req-exp", "ok");

        assertThat(exporter.exported).hasSize(1);
        assertThat(exporter.exported.get(0).requestId()).isEqualTo("req-exp");
        assertThat(exporter.exported.get(0).spans()).hasSize(1);
    }

    /** 记录导出内容的测试替身（OtlpTraceExporter 可继承，覆写异步入口）。 */
    private static final class RecordingExporter extends OtlpTraceExporter {
        final List<AgentRunTrace> exported = new ArrayList<>();

        RecordingExporter() {
            super(false, "http://localhost:4318", Duration.ofSeconds(1));
        }

        @Override
        public void export(AgentRunTrace trace) {
            if (trace != null) {
                exported.add(trace);
            }
        }
    }

    @Test
    void generateRequestIdWhenBlank() {
        TraceService service = new TraceService(tempDir);
        String id = service.begin(null, "alice", null, "web", null);
        assertThat(id).startsWith("trace-");
        service.complete(id, "ok");
        assertThat(service.list("alice", 10)).hasSize(1);
    }

    @Test
    void spansOnlyRecordedForActiveTrace() {
        TraceService service = new TraceService(tempDir);
        service.addSpan("no-such-trace", TraceSpan.ok("llm_call", 1, 1, Map.of()));
        assertThat(service.activeCount()).isZero();
    }
}
