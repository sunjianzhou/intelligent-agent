package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.config.AuthProperties;
import com.intelligent.agent.web.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证端点测试：普通登录 + CLI scoped token。
 */
class AuthControllerTest {

    private MockMvc mockMvc;

    private AuthProperties authProperties() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("test-secret-test-secret-test-secret-1234567890");
        AuthProperties.User user = new AuthProperties.User();
        user.setUsername("admin");
        user.setPassword("pw123");
        properties.setUsers(List.of(user));
        return properties;
    }

    @Test
    void loginReturnsToken() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(authProperties(), new JwtUtil(authProperties()))).build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"pw123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.token").isString());
    }

    @Test
    void cliTokenReturnsScopedToken() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(authProperties(), new JwtUtil(authProperties()))).build();

        mockMvc.perform(post("/api/auth/cli-token")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"pw123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.scope").value("cli"))
                .andExpect(jsonPath("$.token").isString());
    }

    @Test
    void cliTokenRejectsBadCredentials() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(authProperties(), new JwtUtil(authProperties()))).build();

        mockMvc.perform(post("/api/auth/cli-token")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }
}
