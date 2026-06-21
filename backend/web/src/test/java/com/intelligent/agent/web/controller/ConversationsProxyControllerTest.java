package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.feishu.FeishuRecallBridge;
import com.intelligent.agent.web.service.PythonProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationsProxyControllerTest {

    @Mock PythonProxyService proxy;
    @Mock HttpServletRequest req;
    @Mock FeishuRecallBridge recallBridge;

    private ConversationsProxyController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ConversationsProxyController();
        controller.proxy = proxy;
        controller.objectMapper = new ObjectMapper();
        controller.feishuRecallBridge = recallBridge;
        when(proxy.extractUserIdFromRequest(req)).thenReturn("u1");
    }

    @Test
    void retractMessages_forwardsToCorrectPythonPath() throws Exception {
        doReturn(ResponseEntity.ok("{\"success\":true,\"requested\":1,\"deleted\":1,\"deleted_ids\":[\"mid-1\"],\"memory_purged\":1}"))
                .when(proxy).post(anyString(), any(), anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", Collections.singletonList("mid-1"));

        ResponseEntity<Map<String, Object>> resp = controller.retractMessages("sess1", body, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("deleted")).isEqualTo(1);
        verify(proxy).post("/api/conversations/sess1/retract", body, "u1");
    }

    @Test
    void retractMessages_proxyThrows_returnsErrorResponse() throws Exception {
        doThrow(new RuntimeException("network error"))
                .when(proxy).post(anyString(), any(), anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", Collections.singletonList("mid-1"));

        ResponseEntity<Map<String, Object>> resp = controller.retractMessages("sess1", body, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    void retractMessages_triggersFeishuRecallBridge() throws Exception {
        when(proxy.post(eq("/api/conversations/sess1/retract"), any(), eq("u1")))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"requested\":1,\"deleted\":1,\"deleted_ids\":[\"mid-1\"],\"memory_purged\":1}"));

        Map<String, Object> body = new HashMap<>();
        body.put("message_ids", Collections.singletonList("mid-1"));

        controller.retractMessages("sess1", body, req);

        verify(recallBridge).onMessagesRetracted(argThat(resp ->
                resp != null && Boolean.TRUE.equals(resp.get("success"))));
    }
}
