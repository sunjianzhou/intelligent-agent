package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.infrastructure.observability.TraceService;
import com.intelligent.agent.web.infrastructure.observability.TraceSpan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/traces 端点契约（G4）：list / get / delete，userId 隔离。 */
class TraceControllerContractTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private TraceService traceService;

    @BeforeEach
    void setUp() {
        traceService = new TraceService(tempDir);
        mockMvc = MockMvcBuilders.standaloneSetup(new TraceController(traceService)).build();
    }

    @Test
    void listReturnsOnlyOwnTraces() throws Exception {
        traceService.begin("req-alice", "alice", null, "web", "qwen2.5:7b");
        traceService.addSpan("req-alice", TraceSpan.ok("llm_call", 1, 10,
                Map.of("model", "qwen2.5:7b")));
        traceService.complete("req-alice", "ok");
        traceService.begin("req-bob", "bob", null, "web", null);
        traceService.complete("req-bob", "ok");

        mockMvc.perform(get("/api/traces").requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.traces[0].request_id").value("req-alice"))
                .andExpect(jsonPath("$.traces[0].span_count").value(1));
    }

    @Test
    void getReturnsFullTraceWithSpans() throws Exception {
        traceService.begin("req-1", "alice", "s1", "web", "qwen2.5:7b");
        traceService.addSpan("req-1", TraceSpan.ok("rag", 1, 5, Map.of("recall", 2)));
        traceService.addSpan("req-1", TraceSpan.ok("llm_call", 2, 30,
                Map.of("model", "qwen2.5:7b", "stream", true)));
        traceService.complete("req-1", "ok");

        mockMvc.perform(get("/api/traces/req-1").requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("req-1"))
                .andExpect(jsonPath("$.spans.length()").value(2))
                .andExpect(jsonPath("$.spans[1].name").value("llm_call"));
    }

    @Test
    void getForOtherUserReturns404() throws Exception {
        traceService.begin("req-1", "alice", null, "web", null);
        traceService.complete("req-1", "ok");

        mockMvc.perform(get("/api/traces/req-1").requestAttr("userId", "bob"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOwnTraceSucceedsAndForeignFails() throws Exception {
        traceService.begin("req-1", "alice", null, "web", null);
        traceService.complete("req-1", "ok");

        mockMvc.perform(delete("/api/traces/req-1").requestAttr("userId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(delete("/api/traces/req-1").requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
