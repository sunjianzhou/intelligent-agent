package com.intelligent.agent.web.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.controller.ChatController;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开 chat 契约回归：
 * <ul>
 *   <li>POST /api/chat 响应形状（success + data.response 字符串）；</li>
 *   <li>流式 SSE 事件序列契约（contracts/chat-stream-events.jsonl），
 *       事件类型限定在 token / tool_call_start / tool_call / tool_calls_done /
 *       done / error，序列化为 {type, data}。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ChatContractTest {

    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "token", "tool_call_start", "tool_call", "tool_calls_done", "done", "error");

    @Mock
    private AgentService agentService;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(agentService)).build();
    }

    @Test
    void chatReturnsOkWithResponseString() throws Exception {
        when(agentService.chat(any(ChatRequest.class))).thenReturn("你好");

        mockMvc.perform(post("/api/chat")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.response").isString());
    }

    @Test
    void chatRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void streamEventFixtureUsesExistingSseShape() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/contracts/chat-stream-events.jsonl")) {
            assertThat(in).as("contracts/chat-stream-events.jsonl fixture must exist").isNotNull();
            String fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            List<ModelEvent> events = fixture.lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        try {
                            return mapper.readValue(line, ModelEvent.class);
                        } catch (Exception e) {
                            throw new RuntimeException("invalid fixture line: " + line, e);
                        }
                    })
                    .toList();

            assertThat(events).isNotEmpty();
            for (ModelEvent event : events) {
                assertThat(ALLOWED_EVENT_TYPES).contains(event.type());
                assertThat(mapper.writeValueAsString(event))
                        .contains("\"type\":\"" + event.type() + "\"")
                        .contains("\"data\":");
            }
        }
    }
}
