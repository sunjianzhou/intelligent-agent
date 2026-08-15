package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知 per-user 分发契约（2026-08-15 补齐）：
 * 用户级通知只推给该用户自己的会话；系统级通知广播；
 * 目标用户不在线时重新入队等待上线。
 */
@ExtendWith(MockitoExtension.class)
class WebSocketControllerTest {

    @Mock
    private TaskSchedulerService scheduler;

    @Mock
    private AgentService agentService;

    private WebSocketController controller;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        controller = new WebSocketController();
        ReflectionTestUtils.setField(controller, "sessions", sessions);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "agentService", agentService);
        ReflectionTestUtils.setField(controller, "streamExecutor",
                Executors.newSingleThreadExecutor());
        ReflectionTestUtils.setField(controller, "taskSchedulerService", scheduler);
    }

    @Test
    void userNotificationOnlySentToMatchingUserSession() throws Exception {
        WebSocketSession alice = mockSession("alice");
        WebSocketSession bob = mockSession("bob");
        when(scheduler.pollNotifications()).thenReturn(List.of(
                Map.of("message", "你的任务", "timestamp", "t1",
                        "task_id", "x", "user_id", "alice")));

        controller.pushPendingNotifications();

        verify(alice).sendMessage(any(TextMessage.class));
        verify(bob, never()).sendMessage(any(TextMessage.class));
        verify(scheduler, never()).requeue(any());
    }

    @Test
    void systemNotificationBroadcastsToAllSessions() throws Exception {
        WebSocketSession alice = mockSession("alice");
        WebSocketSession bob = mockSession("bob");
        when(scheduler.pollNotifications()).thenReturn(List.of(
                Map.of("message", "系统公告", "timestamp", "t1", "task_id", "x")));

        controller.pushPendingNotifications();

        verify(alice).sendMessage(any(TextMessage.class));
        verify(bob).sendMessage(any(TextMessage.class));
        verify(scheduler, never()).requeue(any());
    }

    @Test
    void offlineUserNotificationIsRequeued() throws Exception {
        mockSession("bob");
        when(scheduler.pollNotifications()).thenReturn(List.of(
                Map.of("message", "给 alice", "timestamp", "t1",
                        "task_id", "x", "user_id", "alice")));

        controller.pushPendingNotifications();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(scheduler).requeue(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).get("user_id")).isEqualTo("alice");
    }

    @Test
    void userWithMultipleSessionsReceivesNotificationOnAll() throws Exception {
        WebSocketSession aliceWeb = mockSession("alice");
        WebSocketSession aliceMobile = mockSession("alice");
        when(scheduler.pollNotifications()).thenReturn(List.of(
                Map.of("message", "多端推送", "timestamp", "t1",
                        "task_id", "x", "user_id", "alice")));

        controller.pushPendingNotifications();

        verify(aliceWeb).sendMessage(any(TextMessage.class));
        verify(aliceMobile).sendMessage(any(TextMessage.class));
        verify(scheduler, never()).requeue(any());
    }

    private WebSocketSession mockSession(String userId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("sess-" + userId + "-" + sessions.size());
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        when(session.getAttributes()).thenReturn(attrs);
        sessions.put(session.getId(), session);
        return session;
    }
}
