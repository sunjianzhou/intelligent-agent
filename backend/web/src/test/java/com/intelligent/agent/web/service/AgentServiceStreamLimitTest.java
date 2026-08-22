package com.intelligent.agent.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.agent.ActiveChatLimiter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.api.chat.LocalChatService;
import com.intelligent.agent.web.dto.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 流式对话并发上限（WS 路径）：满时拒绝并回"服务繁忙"，流结束后释放槽位。 */
class AgentServiceStreamLimitTest {

    @Test
    void streamChatAsyncRejectsWhenLimiterFull() throws Exception {
        ActiveChatLimiter limiter = new ActiveChatLimiter(1);
        limiter.tryAcquire();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LocalChatService localChatService = mock(LocalChatService.class);
        AgentService agentService = new AgentService(
                new ObjectMapper(), executor, localChatService,
                null, null, null, limiter);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s1");

        agentService.streamChatAsync(new ChatRequest("hi", true, true),
                session, "req-1", 0);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("服务繁忙");
        verify(localChatService, never()).stream(any(ChatRequest.class));

        limiter.release();
        executor.shutdownNow();
    }

    @Test
    void streamChatAsyncReleasesSlotWhenStreamEnds() throws Exception {
        ActiveChatLimiter limiter = new ActiveChatLimiter(1);
        LocalChatService localChatService = mock(LocalChatService.class);
        when(localChatService.stream(any(ChatRequest.class)))
                .thenReturn(Flux.just(ModelEvent.token("hi"), ModelEvent.done(Map.of())));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AgentService agentService = new AgentService(
                new ObjectMapper(), executor, localChatService,
                null, null, null, limiter);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s2");

        agentService.streamChatAsync(new ChatRequest("hi", true, true),
                session, "req-2", 0);

        long deadline = System.currentTimeMillis() + 5000;
        while (limiter.active() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(limiter.active()).isZero();
        executor.shutdownNow();
    }

    @Test
    void streamChatAsyncCancelsAndReleasesSlotWhenSessionDisconnects() throws Exception {
        ActiveChatLimiter limiter = new ActiveChatLimiter(1);
        LocalChatService localChatService = mock(LocalChatService.class);
        when(localChatService.stream(any(ChatRequest.class)))
                .thenReturn(Flux.just(ModelEvent.token("hi"), ModelEvent.done(Map.of()))
                        .delayElements(Duration.ofMillis(50)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AgentService agentService = new AgentService(
                new ObjectMapper(), executor, localChatService,
                null, null, null, limiter);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false); // 客户端已断开
        when(session.getId()).thenReturn("s3");

        agentService.streamChatAsync(new ChatRequest("hi", true, true),
                session, "req-3", 0);

        long deadline = System.currentTimeMillis() + 5000;
        while (limiter.active() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(limiter.active()).isZero();
        executor.shutdownNow();
    }
}
