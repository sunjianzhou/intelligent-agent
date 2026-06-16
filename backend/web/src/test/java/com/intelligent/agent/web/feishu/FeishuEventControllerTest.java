package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuEventControllerTest {

    @Mock AgentService agentService;
    @Mock FeishuMessageSender sender;

    private FeishuEventController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        FeishuConfig config = new FeishuConfig();
        config.setVerificationToken("verify-tok");
        config.setEncryptKey("test-key");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(agentService.chat(any(ChatRequest.class))).thenReturn("Agent 回复");

        controller = new FeishuEventController(config, agentService, sender,
                new ObjectMapper(), executor);
    }

    @Test
    void routeEvent_imMessage_extractsUserIdWithPrefix() throws Exception {
        String event = buildImMessageEvent("ou_test123", "oc_chat456", "你好");
        controller.routeEvent(event);
        Thread.sleep(200);

        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentService, timeout(1000)).chat(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo("feishu:ou_test123");
    }

    @Test
    void routeEvent_imMessage_sendsThinkingFirst() throws Exception {
        String event = buildImMessageEvent("ou_abc", "oc_chat789", "测试");
        controller.routeEvent(event);
        Thread.sleep(500);
        verify(sender, timeout(1000)).sendText(eq("oc_chat789"), contains("思考中"));
    }

    @Test
    void routeEvent_unknownEventType_silentlyIgnored() {
        assertThatCode(() -> controller.routeEvent(
                "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"unknown.type\"},\"event\":{}}")
        ).doesNotThrowAnyException();
        verifyNoInteractions(agentService);
    }

    @Test
    void routeEvent_malformedJson_doesNotThrow() {
        assertThatCode(() -> controller.routeEvent("not-json")).doesNotThrowAnyException();
    }

    private String buildImMessageEvent(String openId, String chatId, String text) {
        String contentEscaped = "{\\\"text\\\":\\\"" + text + "\\\"}";
        return "{"
            + "\"schema\":\"2.0\","
            + "\"header\":{\"event_type\":\"im.message.receive_v1\"},"
            + "\"event\":{"
            +   "\"sender\":{\"sender_id\":{\"open_id\":\"" + openId + "\"}},"
            +   "\"message\":{\"chat_id\":\"" + chatId + "\","
            +              "\"msg_type\":\"text\","
            +              "\"content\":\"" + contentEscaped + "\"}"
            + "}"
            + "}";
    }
}
