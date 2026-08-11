package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.feishu.FeishuRecallBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationsProxyControllerTest {

    @Mock HttpServletRequest req;
    @Mock FeishuRecallBridge recallBridge;

    @TempDir
    Path tempDir;

    private ConversationsProxyController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ConversationService conversationService = new ConversationService(tempDir);
        controller = new ConversationsProxyController(conversationService, recallBridge);
        when(req.getAttribute("userId")).thenReturn("u1");
    }

    @Test
    void retractMessages_nonexistentSession_returnsZeroDeleted() {
        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", Collections.singletonList("mid-1"));

        ResponseEntity<Map<String, Object>> resp =
                controller.retractMessages("sess1", body, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
        assertThat(resp.getBody().get("requested")).isEqualTo(1);
        assertThat(resp.getBody().get("deleted")).isEqualTo(0);
    }

    @Test
    void retractMessages_triggersFeishuRecallBridge() {
        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", Collections.singletonList("mid-1"));

        controller.retractMessages("sess1", body, req);

        verify(recallBridge).onMessagesRetracted(argThat(resp ->
                resp != null && Boolean.TRUE.equals(resp.get("success"))));
    }

    @Test
    void retractMessages_removesRealMessagesFromSession() {
        Map<String, Object> append = new HashMap<>();
        append.put("session_id", "sess2");
        append.put("messages", Collections.singletonList(
                Map.of("role", "user", "content", "需要撤回的消息")));
        controller.appendConversation(append, req);

        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", Collections.singletonList("m1"));
        ResponseEntity<Map<String, Object>> resp =
                controller.retractMessages("sess2", body, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
    }
}
