package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 记忆端点契约（2026-08-15 补充）：summaries / export / distill / batch-import
 * 四个端点此前随 Python 代理移除而缺失，前端调用 405；补齐后的契约回归。
 */
class MemoryProxyControllerContractTest {

    private VectorMemoryRepository repository;
    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = new VectorMemoryRepository();
        SemanticResponseCache cache = new SemanticResponseCache();
        MemoryDistillationService distiller = new MemoryDistillationService(5, 10);
        ConversationMemoryService memoryService =
                new ConversationMemoryService(repository, cache, distiller);
        MemoryProxyController controller =
                new MemoryProxyController(repository, memoryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void summariesListsSummaryRecordsOnly() throws Exception {
        repository.upsert(new MemoryRecord("s1", "alice", "会话摘要: 讨论架构",
                null, null, "summary", Map.of("source", "session_summary"), 0.6));
        repository.upsert(new MemoryRecord("f1", "alice", "事实: 用户偏好",
                null, null, "fact", Map.of(), 0.7));

        mockMvc.perform(get("/api/memory/summaries?limit=10").requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaries.length()").value(1))
                .andExpect(jsonPath("$.summaries[0].content").value("会话摘要: 讨论架构"));
    }

    @Test
    void exportJsonReturnsAttachment() throws Exception {
        repository.upsert(new MemoryRecord("f1", "alice", "事实: A",
                null, null, "fact", Map.of(), 0.7));

        String body = mockMvc.perform(get("/api/memory/export?format=json")
                        .requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body);
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isEqualTo(1);
    }

    @Test
    void exportMarkdownReturnsMdAttachment() throws Exception {
        repository.upsert(new MemoryRecord("f1", "alice", "事实: A",
                null, null, "fact", Map.of(), 0.7));

        mockMvc.perform(get("/api/memory/export?format=markdown")
                        .requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("事实: A")));
    }

    @Test
    void distillTriggersAndReportsCount() throws Exception {
        mockMvc.perform(post("/api/memory/distill").requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void batchImportCreatesRecords() throws Exception {
        Map<String, Object> body = Map.of("items", List.of(
                Map.of("content", "事实一", "category", "fact", "importance", 0.9),
                Map.of("content", "事实二", "category", "fact", "importance", 0.5),
                Map.of("content", "  ", "category", "fact")));

        mockMvc.perform(post("/api/memory/batch-import")
                        .requestAttr("userId", "alice")
                        .contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.imported_count").value(2));

        assertThat(repository.count(MemorySearchQuery.builder("alice", "", 100).build()))
                .isEqualTo(2);
    }
}
