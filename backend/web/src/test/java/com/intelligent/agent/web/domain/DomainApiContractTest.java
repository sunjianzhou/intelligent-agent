package com.intelligent.agent.web.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.controller.ConversationsProxyController;
import com.intelligent.agent.web.controller.ProjectProxyController;
import com.intelligent.agent.web.controller.RoleController;
import com.intelligent.agent.web.controller.TaskProxyController;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.domain.project.ProjectService;
import com.intelligent.agent.web.domain.role.RoleService;
import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 领域 API 契约测试（Plan 2 / Task 3）：
 * 每个垂直切片在替换代理前先写失败测试，实现后转绿。
 * Java-only 领域服务契约测试。
 */
class DomainApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc;
    private RoleService roleService;
    private ConversationService conversationService;
    private ProjectService projectService;
    private TaskService taskService;

    @BeforeEach
    void setUp() throws Exception {
        Path dataDir = Files.createTempDirectory("domain-contract");
        roleService = new RoleService(dataDir);
        conversationService = new ConversationService(dataDir);
        projectService = new ProjectService(dataDir, new VectorMemoryRepository());
        taskService = new TaskService();
        RoleController roleController = new RoleController(roleService);
        ConversationsProxyController conversationController =
                new ConversationsProxyController(conversationService, null);
        ProjectProxyController projectController = new ProjectProxyController(projectService);
        TaskProxyController taskController = new TaskProxyController(taskService);
        mockMvc = MockMvcBuilders.standaloneSetup(
                roleController, conversationController, projectController, taskController).build();
    }

    // ── Role 切片 ─────────────────────────────────────────────

    @Test
    void roleListReturnsArrayShape() throws Exception {
        mockMvc.perform(get("/api/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void roleCreateAndGetRoundTrip() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(roleBody("assistant_01", "小助手"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role_id").value("assistant_01"));

        mockMvc.perform(get("/api/roles/assistant_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role.role_id").value("assistant_01"))
                .andExpect(jsonPath("$.role.role_card.name").value("小助手"));

        mockMvc.perform(get("/api/roles/assistant_01/card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.card.name").value("小助手"));
    }

    @Test
    void roleCreateRejectsMissingCardName() throws Exception {
        Map<String, Object> body = roleBody("bad", "");
        mockMvc.perform(post("/api/roles")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void roleGetMissingReturns404() throws Exception {
        mockMvc.perform(get("/api/roles/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rolePatchDeepMergesFields() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(roleBody("assistant_01", "小助手"))))
                .andExpect(status().isOk());

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("core_identity", Map.of("language_style", "活泼亲切"));
        mockMvc.perform(patch("/api/roles/assistant_01")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role.core_identity.language_style").value("活泼亲切"))
                .andExpect(jsonPath("$.role.core_identity.personality[0]").value("温柔"));
    }

    @Test
    void roleActivateFlowPerUser() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(roleBody("assistant_01", "小助手"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/roles/activate")
                        .contentType(APPLICATION_JSON)
                        .content("{\"role_id\":\"assistant_01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role_id").value("assistant_01"));

        mockMvc.perform(get("/api/roles/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role_id").value("assistant_01"));

        mockMvc.perform(delete("/api/roles/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivated").value("assistant_01"));
    }

    @Test
    void roleDeleteRemovesRole() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(roleBody("assistant_01", "小助手"))))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/roles/assistant_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/roles/assistant_01"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rolePutUpdatesExisting() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(roleBody("assistant_01", "小助手"))))
                .andExpect(status().isOk());

        Map<String, Object> updated = roleBody("assistant_01", "新名字");
        mockMvc.perform(put("/api/roles/assistant_01")
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role.role_card.name").value("新名字"));
    }

    // ── Conversation 切片 ─────────────────────────────────────

    @Test
    void conversationAppendListAndGetRoundTrip() throws Exception {
        mockMvc.perform(post("/api/conversations/append")
                        .contentType(APPLICATION_JSON)
                        .content("{\"session_id\":\"s1\",\"messages\":["
                                + "{\"role\":\"user\",\"content\":\"你好\",\"id\":\"m1\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value("s1"));

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.sessions[0].session_id").value("s1"))
                .andExpect(jsonPath("$.sessions[0].preview").value("你好"));

        mockMvc.perform(get("/api/conversations/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.session_id").value("s1"))
                .andExpect(jsonPath("$.session.messages[0].id").value("m1"))
                .andExpect(jsonPath("$.session.messages[0].content").value("你好"));
    }

    @Test
    void conversationRetractRemovesMessages() throws Exception {
        mockMvc.perform(post("/api/conversations/append")
                        .contentType(APPLICATION_JSON)
                        .content("{\"session_id\":\"s1\",\"messages\":["
                                + "{\"role\":\"user\",\"content\":\"甲\",\"id\":\"m1\"},"
                                + "{\"role\":\"assistant\",\"content\":\"乙\",\"id\":\"m2\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/conversations/s1/retract")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message_ids\":[\"m1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.deleted_ids[0]").value("m1"));

        mockMvc.perform(get("/api/conversations/s1"))
                .andExpect(jsonPath("$.session.messages.length()").value(1))
                .andExpect(jsonPath("$.session.messages[0].id").value("m2"));
    }

    @Test
    void conversationBranchCreatesNewSession() throws Exception {
        mockMvc.perform(post("/api/conversations/branch")
                        .contentType(APPLICATION_JSON)
                        .content("{\"parent_session_id\":\"s1\",\"messages\":["
                                + "{\"role\":\"user\",\"content\":\"分支\",\"id\":\"m1\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.session_id").isString());
    }

    @Test
    void conversationDeleteAndClear() throws Exception {
        mockMvc.perform(post("/api/conversations/append")
                        .contentType(APPLICATION_JSON)
                        .content("{\"session_id\":\"s1\",\"messages\":["
                                + "{\"role\":\"user\",\"content\":\"你好\",\"id\":\"m1\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/conversations/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value("s1"));

        mockMvc.perform(get("/api/conversations/s1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/conversations/append")
                        .contentType(APPLICATION_JSON)
                        .content("{\"session_id\":\"s2\",\"messages\":["
                                + "{\"role\":\"user\",\"content\":\"你好2\",\"id\":\"m2\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.deleted").value(1));
    }

    // ── Project 切片 ──────────────────────────────────────────

    @Test
    void projectListCreateGetRoundTrip() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.projects").isArray())
                .andExpect(jsonPath("$.count").value(0));

        mockMvc.perform(post("/api/projects")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"Java 迁移\",\"id\":\"proj_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.project.title").value("Java 迁移"))
                .andExpect(jsonPath("$.project.id").isString());

        mockMvc.perform(get("/api/projects/proj_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.title").value("Java 迁移"));
    }

    @Test
    void projectCreateRejectsBlankTitle() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void projectUpdateAndDelete() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"旧标题\",\"id\":\"proj_1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/projects/proj_1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.title").value("新标题"))
                .andExpect(jsonPath("$.project.id").value("proj_1"));

        mockMvc.perform(delete("/api/projects/proj_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.project_id").value("proj_1"));

        mockMvc.perform(get("/api/projects/proj_1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void projectSpecRoundTrip() throws Exception {
        mockMvc.perform(put("/api/project/spec")
                        .contentType(APPLICATION_JSON)
                        .content("{\"project_id\":\"p1\",\"content\":\"规格内容\",\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project_id").value("p1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.synced").value(true));

        mockMvc.perform(get("/api/project/spec").param("project_id", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("规格内容"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void projectContextExtractAndQuery() throws Exception {
        mockMvc.perform(post("/api/project/context/extract")
                        .contentType(APPLICATION_JSON)
                        .content("{\"project_id\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extracted").isNumber())
                .andExpect(jsonPath("$.version").isNumber());

        mockMvc.perform(get("/api/project/context").param("project_id", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project_id").value("p1"))
                .andExpect(jsonPath("$.nuggets").isArray());
    }

    @Test
    void projectTaskDecomposeAndList() throws Exception {
        mockMvc.perform(post("/api/project/tasks/decompose")
                        .contentType(APPLICATION_JSON)
                        .content("{\"project_id\":\"p1\",\"task_description\":\"完成迁移\\n编写测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project_id").value("p1"))
                .andExpect(jsonPath("$.task_tree.root_tasks").isArray());

        mockMvc.perform(get("/api/project/tasks").param("project_id", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project_id").value("p1"))
                .andExpect(jsonPath("$.task_tree").isArray())
                .andExpect(jsonPath("$.note").isString());
    }

    // ── Task 切片 ─────────────────────────────────────────────

    @Test
    void taskListIsEmptyInitially() throws Exception {
        mockMvc.perform(get("/api/tasks/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void taskCreatePatchAndList() throws Exception {
        mockMvc.perform(post("/api/tasks/create")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"提醒\",\"action\":\"log\",\"id\":\"task_1\","
                                + "\"schedule_type\":\"delay\","
                                + "\"delay_seconds\":60,\"args\":{\"message\":\"该吃药了\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.task.name").value("提醒"))
                .andExpect(jsonPath("$.task.schedule_type").value("delay"))
                .andExpect(jsonPath("$.task.status").value("pending"))
                .andExpect(jsonPath("$.task.args.message").value("该吃药了"))
                .andExpect(jsonPath("$.task.id").isString());

        mockMvc.perform(patch("/api/tasks/task_1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"提醒v2\",\"delay_seconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.name").value("提醒v2"))
                .andExpect(jsonPath("$.task.delay_seconds").value(120));

        mockMvc.perform(get("/api/tasks/list").param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.tasks[0].name").value("提醒v2"));
    }

    @Test
    void taskCancelDeleteExecuteAndStats() throws Exception {
        String taskId = "task_a";
        mockMvc.perform(post("/api/tasks/create")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"任务A\",\"action\":\"log\",\"id\":\"task_a\"}"))
                .andExpect(jsonPath("$.task.id").value(taskId));

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/tasks/" + taskId + "/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/tasks/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_tasks").value(1))
                .andExpect(jsonPath("$.tasks_by_status.cancelled").value(1))
                .andExpect(jsonPath("$.scheduler_running").isBoolean());

        mockMvc.perform(delete("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.task_id").value(taskId));

        mockMvc.perform(get("/api/tasks/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").isArray());
    }

    private static Map<String, Object> roleBody(String roleId, String cardName) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", cardName);
        card.put("signature", "测试角色");
        card.put("tags", java.util.List.of("AI"));

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("personality", java.util.List.of("温柔", "理性"));
        identity.put("principles", java.util.List.of("诚实"));
        identity.put("redlines", java.util.List.of());
        identity.put("language_style", "温柔");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role_id", roleId);
        body.put("core_identity", identity);
        body.put("role_card", card);
        return body;
    }
}
