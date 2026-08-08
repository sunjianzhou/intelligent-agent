package com.intelligent.agent.web.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.controller.ConversationsProxyController;
import com.intelligent.agent.web.controller.RoleController;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.domain.role.RoleService;
import com.intelligent.agent.web.feishu.FeishuRecallBridge;
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
 * 本地运行时（java/shadow）下走 Java 领域服务，响应形状与 Python 一致。
 */
class DomainApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc;
    private RoleService roleService;
    private ConversationService conversationService;

    @BeforeEach
    void setUp() throws Exception {
        Path dataDir = Files.createTempDirectory("domain-contract");
        roleService = new RoleService(dataDir);
        conversationService = new ConversationService(dataDir);
        RoleController roleController =
                new RoleController(null, MAPPER, roleService, "java");
        ConversationsProxyController conversationController =
                new ConversationsProxyController(null, MAPPER, conversationService,
                        "java", null);
        mockMvc = MockMvcBuilders.standaloneSetup(roleController, conversationController).build();
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
