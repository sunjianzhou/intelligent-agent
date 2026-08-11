package com.intelligent.agent.web.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.controller.AnalyticsProxyController;
import com.intelligent.agent.web.controller.KnowledgeProxyController;
import com.intelligent.agent.web.controller.SkillProxyController;
import com.intelligent.agent.web.controller.TeachingController;
import com.intelligent.agent.web.domain.analytics.AnalyticsService;
import com.intelligent.agent.web.domain.knowledge.KnowledgeService;
import com.intelligent.agent.web.domain.skill.SkillService;
import com.intelligent.agent.web.domain.teaching.TeachingService;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * knowledge / skills / analytics / teaching 契约测试（Plan 2 / Task 4）。
 */
class KnowledgeAndSkillContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc;
    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() throws Exception {
        Path dataDir = Files.createTempDirectory("knowledge-contract");
        MemoryRepository memoryRepository = new VectorMemoryRepository();
        knowledgeService = new KnowledgeService(dataDir, memoryRepository);
        SkillService skillService = new SkillService(dataDir);
        AnalyticsService analyticsService = new AnalyticsService(dataDir);
        TeachingService teachingService = new TeachingService(dataDir);

        mockMvc = MockMvcBuilders.standaloneSetup(
                new KnowledgeProxyController(knowledgeService),
                new SkillProxyController(skillService),
                new AnalyticsProxyController(analyticsService),
                new TeachingController(teachingService))
                .build();
    }

    // ── Knowledge ─────────────────────────────────────────────

    @Test
    void knowledgeUploadListAndDelete() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.md", "text/markdown",
                "第一段内容，介绍项目背景。\n\n第二段内容，包含详细说明。".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/knowledge/upload")
                        .file(file)
                        .param("description", "测试文档"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.file_id").isString())
                .andExpect(jsonPath("$.filename").value("notes.md"))
                .andExpect(jsonPath("$.chunk_count").value(1));

        mockMvc.perform(get("/api/knowledge/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.files[0].filename").value("notes.md"));

        mockMvc.perform(delete("/api/knowledge/files/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void knowledgeUploadRejectsOversizedFile() throws Exception {
        MockMultipartFile big = new MockMultipartFile(
                "file", "big.txt", "text/plain", new byte[11 * 1024 * 1024]);

        mockMvc.perform(multipart("/api/knowledge/upload").file(big))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void knowledgeUploadRejectsUnsupportedExtension() throws Exception {
        MockMultipartFile exe = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", "MZ".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/knowledge/upload").file(exe))
                .andExpect(status().isBadRequest());
    }

    // ── Skills ────────────────────────────────────────────────

    @Test
    void skillCreateListToggleAndDelete() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills").isArray())
                .andExpect(jsonPath("$.count").value(0));

        mockMvc.perform(post("/api/skills")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"数据库助手\",\"id\":\"skill_1\",\"trigger_keywords\":[\"sql\"],"
                                + "\"description\":\"查询数据库\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.skill.name").value("数据库助手"))
                .andExpect(jsonPath("$.skill.id").isString());

        mockMvc.perform(put("/api/skills/skill_1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"数据库助手v2\"}"))
                .andExpect(jsonPath("$.skill.name").value("数据库助手v2"));

        mockMvc.perform(patch("/api/skills/skill_1/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(delete("/api/skills/skill_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void skillTemplatesListAndApply() throws Exception {
        mockMvc.perform(get("/api/skills/templates/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates").isArray())
                .andExpect(jsonPath("$.count").value(3));

        mockMvc.perform(post("/api/skills/templates/tpl_database/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.skill.name").isString());
    }

    // ── Analytics ─────────────────────────────────────────────

    @Test
    void analyticsFeedbackAndStats() throws Exception {
        mockMvc.perform(post("/api/analytics/feedback")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"message\":\"你好\",\"response\":\"你好！\","
                                + "\"rating\":\"like\",\"response_time\":1.5,\"tools_used\":[\"echo\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.id").isString());

        mockMvc.perform(get("/api/analytics/stats/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.stats.total").value(1))
                .andExpect(jsonPath("$.stats.likes").value(1));

        mockMvc.perform(get("/api/analytics/records/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/api/analytics/skill-stats/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.total").isNumber());

        mockMvc.perform(get("/api/analytics/tool-calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());

        mockMvc.perform(get("/api/analytics/tool-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.total").isNumber());
    }

    // ── Teaching ──────────────────────────────────────────────

    @Test
    void teachingDailyPlanAndSubmit() throws Exception {
        mockMvc.perform(get("/api/teaching/daily-plan").param("topic", "k8s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("k8s"))
                .andExpect(jsonPath("$.date").isString())
                .andExpect(jsonPath("$.is_weekend").isBoolean())
                .andExpect(jsonPath("$.questions").isArray());

        mockMvc.perform(post("/api/teaching/submit")
                        .contentType(APPLICATION_JSON)
                        .content("{\"user_id\":\"admin\",\"topic\":\"k8s\",\"answers\":["
                                + "{\"question_id\":\"k8s-001\",\"user_answer\":\"A\"},"
                                + "{\"question_id\":\"k8s-002\",\"user_answer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.results[0].correct").value(true))
                .andExpect(jsonPath("$.results[1].correct").value(false));

        mockMvc.perform(get("/api/teaching/wrong-book").param("topic", "k8s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("k8s"))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/api/teaching/command-log").param("topic", "k8s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("k8s"))
                .andExpect(jsonPath("$.content").isString());
    }
}
