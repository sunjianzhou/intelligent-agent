package com.intelligent.agent.web.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.agent.ActiveChatLimiter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.controller.ChatController;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/chat/stream SSE 契约（Plan 3 / Task 2，CLI 依赖）：
 * java 模式下事件以 {type, data} JSON 逐条下发。
 */
class ChatStreamContractTest {

    @Test
    void localStreamEmitsSseTokenAndDone() throws Exception {
        LocalChatService local = mock(LocalChatService.class);
        when(local.stream(any(ChatRequest.class)))
                .thenReturn(Flux.just(ModelEvent.token("你好"), ModelEvent.done(Map.of())));

        ChatController controller = new ChatController(
                mock(AgentService.class), local, new ObjectMapper(),
                testExecutor(), new ActiveChatLimiter(64));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"token\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"done\"")));
    }

    @Test
    void streamReturnsBusyEventWhenLimiterFull() throws Exception {
        ActiveChatLimiter limiter = new ActiveChatLimiter(1);
        limiter.tryAcquire();
        LocalChatService local = mock(LocalChatService.class);
        ChatController controller = new ChatController(
                mock(AgentService.class), local, new ObjectMapper(),
                testExecutor(), limiter);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        byte[] body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text).contains("\"type\":\"error\"");
        assertThat(text).contains("服务繁忙");

        verify(local, never()).stream(any(ChatRequest.class));
        limiter.release();
    }

    private static ExecutorService testExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-stream-worker");
            t.setDaemon(true);
            return t;
        });
    }
}
