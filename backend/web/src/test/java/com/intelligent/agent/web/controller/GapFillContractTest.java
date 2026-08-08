package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.builtin.CalculatorTool;
import com.intelligent.agent.web.ai.tool.builtin.TimeTool;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import com.intelligent.agent.web.integration.mcp.McpToolRegistry;
import com.intelligent.agent.web.service.AgentService;
import com.intelligent.agent.web.service.ModelService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TODO-110 Task 2 死端点本地化契约测试：
 * tools/list、models/model-switch、memory/*、python/health、notifications/poll。
 */
class GapFillContractTest {

    private MockWebServer ollamaServer;
    private MockMvc mockMvc;
    private Path dataDir;
    private MemoryRepository memoryRepository;
    private ConversationMemoryService conversationMemoryService;

    @BeforeEach
    void setUp() throws Exception {
        ollamaServer = new MockWebServer();
        ollamaServer.start();
        dataDir = Files.createTempDirectory("gapfill");

        memoryRepository = new VectorMemoryRepository();
        conversationMemoryService = new ConversationMemoryService(
                memoryRepository, new SemanticResponseCache(), new MemoryDistillationService());

        ModelService modelService = new ModelService();
        ReflectionTestUtils.setField(modelService, "ollamaBaseUrl", ollamaServer.url("/").toString());
        ReflectionTestUtils.setField(modelService, "defaultModel", "qwen2.5:7b");
        ReflectionTestUtils.setField(modelService, "dataDir", dataDir.toString());

        ToolExecutor toolExecutor = new ToolExecutor(List.of(new CalculatorTool(), new TimeTool()));
        TaskSchedulerService scheduler = new TaskSchedulerService(
                new com.intelligent.agent.web.domain.task.TaskService(), dataDir);

        ToolProxyController toolController =
                new ToolProxyController(toolExecutor, new McpToolRegistry(), "java");
        MemoryProxyController memoryController = new MemoryProxyController(
                null, new ObjectMapper(), memoryRepository, conversationMemoryService, "java");

        HealthController healthController = new HealthController();
        ReflectionTestUtils.setField(healthController, "modelService", modelService);
        ReflectionTestUtils.setField(healthController, "agentService", mock(AgentService.class));
        ReflectionTestUtils.setField(healthController, "taskSchedulerService", scheduler);
        ReflectionTestUtils.setField(healthController, "runtimeMode", "java");

        mockMvc = MockMvcBuilders.standaloneSetup(
                toolController, memoryController, healthController).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        ollamaServer.shutdown();
    }

    @Test
    void toolsListReturnsRegisteredTools() throws Exception {
        mockMvc.perform(get("/api/tools/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.tools[*].name").value(
                        org.hamcrest.Matchers.containsInAnyOrder("calculator", "time_tool")));
    }

    @Test
    void modelsListComesFromOllama() throws Exception {
        ollamaServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"models\":[{\"name\":\"qwen2.5:7b\"},{\"name\":\"dolphin3:8b\"}]}"));

        mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available_models[0]").value("qwen2.5:7b"))
                .andExpect(jsonPath("$.ollama_available").value(true))
                .andExpect(jsonPath("$.current_model").value("qwen2.5:7b"));
    }

    @Test
    void modelSwitchPersistsPerUser() throws Exception {
        ollamaServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"models\":[{\"name\":\"qwen2.5:7b\"},{\"name\":\"dolphin3:8b\"}]}"));

        mockMvc.perform(post("/api/model/switch")
                        .contentType(APPLICATION_JSON)
                        .content("{\"model\":\"dolphin3:8b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.current_model").value("dolphin3:8b"));
    }

    @Test
    void memoryStatsListAndDelete() throws Exception {
        memoryRepository.upsert(new com.intelligent.agent.web.ai.memory.MemoryRecord(
                "m1", "default", "alice 偏好喝茶", null, null, "fact",
                java.util.Map.of("category", "fact"), 0.8));

        mockMvc.perform(get("/api/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.long_term.count").value(1));

        mockMvc.perform(get("/api/memory/list").param("memory_type", "long_term"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memories[0].id").value("m1"))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(delete("/api/memory/m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/memory/list").param("memory_type", "long_term"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void memoryImportancePatchAndSearch() throws Exception {
        memoryRepository.upsert(new com.intelligent.agent.web.ai.memory.MemoryRecord(
                "m1", "default", "alice prefers tea", null, null, "fact",
                java.util.Map.of("category", "fact"), 0.5));

        mockMvc.perform(patch("/api/memory/m1/importance")
                        .contentType(APPLICATION_JSON)
                        .content("{\"importance\":0.9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importance").value(0.9));

        mockMvc.perform(get("/api/memory/search").param("q", "tea"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("m1"));
    }

    @Test
    void pythonHealthReportsJavaOnly() throws Exception {
        mockMvc.perform(get("/api/python/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("java-only"));
    }

    @Test
    void notificationsPollReturnsQueue() throws Exception {
        mockMvc.perform(get("/api/notifications/poll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications").isArray())
                .andExpect(jsonPath("$.count").value(0));
    }
}
