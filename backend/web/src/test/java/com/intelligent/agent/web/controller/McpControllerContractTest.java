package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import com.intelligent.agent.web.integration.mcp.McpConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/mcp/servers 端点契约（G2）：CRUD + connect/disconnect。 */
class McpControllerContractTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        McpConnectionManager manager = new McpConnectionManager(
                tempDir, new SecretCrypto("test-secret-0123456789abcdef"),
                new ToolExecutor(List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(new McpController(manager)).build();
    }

    @Test
    void serverCrudRoundTrip() throws Exception {
        // 创建
        String body = mockMvc.perform(post("/api/mcp/servers")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"mock\",\"base_url\":\"http://127.0.0.1:1\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("server").path("id").asText();
        assertThat(id).isNotBlank();

        // 列表
        mockMvc.perform(get("/api/mcp/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.servers[0].name").value("mock"));

        // 更新
        mockMvc.perform(put("/api/mcp/servers/" + id)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"mock2\",\"base_url\":\"http://127.0.0.1:2\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.server.name").value("mock2"));

        // 未连接时 disconnect 返回 success=false（幂等语义）
        mockMvc.perform(post("/api/mcp/servers/" + id + "/disconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        // 删除
        mockMvc.perform(delete("/api/mcp/servers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/api/mcp/servers"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void getMissingServerReturns404() throws Exception {
        mockMvc.perform(get("/api/mcp/servers/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRequiresNameAndBaseUrl() throws Exception {
        mockMvc.perform(post("/api/mcp/servers")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"\",\"base_url\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

}
