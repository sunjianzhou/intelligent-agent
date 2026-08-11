package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuEventControllerTest {

    @Mock AgentService agentService;
    @Mock FeishuMessageSender sender;
    @Mock FeishuRecallBridge recallBridge;

    private FeishuEventController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        FeishuConfig config = new FeishuConfig();
        config.setVerificationToken("verify-tok");
        config.setEncryptKey("test-key");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Map<String, Object> chatFullResult = new HashMap<>();
        chatFullResult.put("response", "Agent 回复");
        chatFullResult.put("assistant_message_id", "aid-1");
        when(agentService.chatFull(any(ChatRequest.class))).thenReturn(chatFullResult);
        when(sender.sendInteractive(any(), any())).thenReturn("om_feishu1");

        controller = new FeishuEventController(config, agentService, sender,
                new ObjectMapper(), executor, recallBridge);
    }

    @Test
    void routeEvent_imMessage_extractsUserIdWithPrefix() throws Exception {
        String event = buildImMessageEvent("ou_test123", "oc_chat456", "你好");
        controller.routeEvent(event);
        Thread.sleep(200);

        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentService, timeout(1000)).chatFull(cap.capture());
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
    void routeEvent_imMessage_registersFeishuRecallMapping() throws Exception {
        String event = buildImMessageEvent("ou_abc", "oc_chat789", "测试");
        controller.routeEvent(event);
        Thread.sleep(500);
        verify(recallBridge, timeout(1000)).register("aid-1", "om_feishu1");
    }

    @Test
    void routeEvent_groupChatNotMentioned_skipsThinkingButStillAsksLlm() throws Exception {
        String event = buildImMessageEvent("ou_grp", "oc_group1", "随便聊聊", "group");
        controller.routeEvent(event);
        Thread.sleep(500);

        ArgumentCaptor<ChatRequest> cap = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentService, timeout(1000)).chatFull(cap.capture());
        assertThat(cap.getValue().getSceneChatType()).isEqualTo("group");
        assertThat(cap.getValue().getSceneMentioned()).isFalse();  // 无 mentions 字段 → 未被 @
        // 群聊未被 @：跳过「思考中」占位提示，避免对不相关消息刷屏
        verify(sender, never()).sendText(eq("oc_group1"), contains("思考中"));
        // 仍会把结果回传（[GROUP SCENE] 规则在本地 PromptService 决定是否真正回复；这里 mock 返回非 NO_REPLY 内容）
        verify(sender, timeout(1000)).sendInteractive(eq("oc_group1"), any());
    }

    @Test
    void routeEvent_groupChatNoReplySentinel_discardedSilently() throws Exception {
        Map<String, Object> noReplyResult = new HashMap<>();
        noReplyResult.put("response", "NO_REPLY");
        noReplyResult.put("assistant_message_id", "aid-2");
        when(agentService.chatFull(any(ChatRequest.class))).thenReturn(noReplyResult);

        String event = buildImMessageEvent("ou_grp2", "oc_group2", "随便聊聊", "group");
        controller.routeEvent(event);
        Thread.sleep(500);

        verify(agentService, timeout(1000)).chatFull(any());
        verify(sender, never()).sendInteractive(eq("oc_group2"), any());
        verify(recallBridge, never()).register(any(), any());
    }

    @Test
    void routeEvent_p2pChat_stillSendsThinkingFirst() throws Exception {
        String event = buildImMessageEvent("ou_p2p", "oc_p2p1", "你好", "p2p");
        controller.routeEvent(event);
        Thread.sleep(500);
        verify(sender, timeout(1000)).sendText(eq("oc_p2p1"), contains("思考中"));
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
        return buildImMessageEvent(openId, chatId, text, "p2p");
    }

    private String buildImMessageEvent(String openId, String chatId, String text, String chatType) {
        String contentEscaped = "{\\\"text\\\":\\\"" + text + "\\\"}";
        return "{"
            + "\"schema\":\"2.0\","
            + "\"header\":{\"event_type\":\"im.message.receive_v1\"},"
            + "\"event\":{"
            +   "\"sender\":{\"sender_id\":{\"open_id\":\"" + openId + "\"}},"
            +   "\"message\":{\"chat_id\":\"" + chatId + "\","
            +              "\"chat_type\":\"" + chatType + "\","
            +              "\"msg_type\":\"text\","
            +              "\"content\":\"" + contentEscaped + "\"}"
            + "}"
            + "}";
    }
}
