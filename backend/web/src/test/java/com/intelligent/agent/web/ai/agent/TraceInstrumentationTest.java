package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.infrastructure.observability.TraceSpan;
import com.intelligent.agent.web.infrastructure.observability.TraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentOrchestrator 追踪埋点契约（G4）：无工具对话记录 rag/llm_call/memory spans；
 * 工具流程额外记录 tool_call span。
 */
class TraceInstrumentationTest {

    @TempDir
    Path tempDir;

    @Test
    void completeRecordsRagLlmAndMemorySpans() {
        TraceService traceService = new TraceService(tempDir);
        AgentOrchestratorTest.FakeProvider fake = new AgentOrchestratorTest.FakeProvider(
                List.of("你好"), Flux.just(ModelEvent.token("你好"), ModelEvent.done(Map.of())));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(fake, null, List.of()),
                new ToolExecutor(List.of()), null, null, null, 5, traceService);

        String answer = orchestrator.complete(
                AgentRequestContext.of("alice", "你好")).block();

        assertThat(answer).isEqualTo("你好");
        List<Map<String, Object>> traces = traceService.list("alice", 10);
        assertThat(traces).hasSize(1);
        Map<String, Object> trace = traceService.get("alice",
                String.valueOf(traces.get(0).get("request_id")));
        List<?> spans = (List<?>) trace.get("spans");
        List<String> names = spans.stream()
                .map(s -> ((TraceSpan) s).name())
                .toList();
        assertThat(names).contains("rag", "llm_call", "memory");
        assertThat(trace.get("status")).isEqualTo("ok");
    }

    @Test
    void toolFlowRecordsToolCallSpan() {
        TraceService traceService = new TraceService(tempDir);
        AgentOrchestratorTest.ToolCallProvider provider = new AgentOrchestratorTest.ToolCallProvider(
                List.of("", "计算完成"),
                Flux.just(ModelEvent.token("计算完成"), ModelEvent.done(Map.of())),
                List.of(ToolCall.of("echo", Map.of("text", "hi"))), 1);
        ToolExecutor tools = new ToolExecutor(List.of(new AgentOrchestratorTest.EchoTool()));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                tools, null, null, null, 5, traceService);

        String answer = orchestrator.complete(
                AgentRequestContext.of("alice", "echo hi")).block();

        assertThat(answer).isEqualTo("计算完成");
        List<Map<String, Object>> all = traceService.list("", 50);
        assertThat(all).as("trace 未落盘，目录: %s, active=%d",
                java.util.Arrays.toString(tempDir.resolve("traces").toFile().list()),
                traceService.activeCount()).isNotEmpty();
        Map<String, Object> trace = traceService.get("alice",
                String.valueOf(all.get(0).get("request_id")));
        List<?> spans = (List<?>) trace.get("spans");
        List<TraceSpan> toolSpans = spans.stream()
                .map(s -> (TraceSpan) s)
                .filter(s -> "tool_call".equals(s.name()))
                .toList();
        assertThat(toolSpans).hasSize(1);
        assertThat(toolSpans.get(0).status()).isEqualTo("ok");
        assertThat(String.valueOf(toolSpans.get(0).details().get("tool"))).isEqualTo("echo");
    }
}
