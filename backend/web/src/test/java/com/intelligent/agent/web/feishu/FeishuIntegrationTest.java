package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeishuIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AgentService agentService;
    @MockBean FeishuMessageSender feishuMessageSender;
    @MockBean FeishuRecallBridge feishuRecallBridge;

    private MockWebServer mockFeishuApi;

    @BeforeEach
    void setUp() throws IOException {
        mockFeishuApi = new MockWebServer();
        mockFeishuApi.start();
        Map<String, Object> chatFullResult = new HashMap<>();
        chatFullResult.put("response", "集成测试回复");
        chatFullResult.put("assistant_message_id", "aid-int-1");
        when(agentService.chatFull(any(ChatRequest.class))).thenReturn(chatFullResult);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockFeishuApi.shutdown();
    }

    /**
     * Scenario 1: im.message.receive_v1 事件经 routeEvent 路由后，
     * 正确提取 userId/message 并调用 agentService.chat + feishuMessageSender.sendText。
     */
    @Test
    void scenario1_imMessageEvent_routedCorrectly() throws Exception {
        FeishuConfig config = new FeishuConfig();
        config.setVerificationToken("tok");
        config.setEncryptKey("key");

        // 直接使用 @MockBean 注入的 feishuMessageSender 引用，确保 verify 目标一致
        FeishuEventController ctrl = new FeishuEventController(
                config,
                agentService,
                feishuMessageSender,
                objectMapper,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "feishu-test-event");
                    t.setDaemon(true);
                    return t;
                }),
                feishuRecallBridge);

        String event = buildEvent("ou_user01", "oc_chat01", "你好 Agent");
        ctrl.routeEvent(event);
        Thread.sleep(300);

        verify(agentService, timeout(1000)).chatFull(argThat(req ->
                "feishu:ou_user01".equals(req.getUserId())
                && "你好 Agent".equals(req.getMessage())));
        verify(feishuMessageSender, timeout(1000)).sendText(eq("oc_chat01"), contains("思考"));
    }

    /**
     * Scenario 2a: 卡片回调 encryptKey 为空时跳过签名验证，返回 200 ok。
     */
    @Test
    void scenario2_cardCallback_validSignature_returns200() throws Exception {
        String ts    = "1718500000";
        String nonce = "nonce123";

        mockMvc.perform(post("/feishu/callback/interactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Request-Timestamp", ts)
                        .header("X-Lark-Request-Nonce", nonce)
                        .content("{\"action\":{\"value\":{\"key\":\"confirm\"}}}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ok")));
    }

    /**
     * Scenario 2b: encryptKey 为空时，即使传了错误签名头，Controller 也跳过验证返回 200。
     */
    @Test
    void scenario2_cardCallback_badSignature_returns200WhenKeyEmpty() throws Exception {
        mockMvc.perform(post("/feishu/callback/interactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Request-Timestamp", "ts")
                        .header("X-Lark-Request-Nonce", "n")
                        .header("X-Lark-Signature", "bad-sig")
                        .content("{\"action\":{\"value\":{\"key\":\"cancel\"}}}"))
                // encryptKey 默认为空，Controller 跳过签名校验 → 200
                .andExpect(status().isOk());
    }

    /**
     * Scenario 3: FeishuMessageSender 真实实例指向 MockWebServer，
     * 验证 sendText 发出的请求体包含 chatId 和消息内容。
     */
    @Test
    void scenario3_messageSender_sendsCorrectPayload() throws Exception {
        // 1. mock token 响应
        mockFeishuApi.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"tok\",\"expire\":7200}")
                .addHeader("Content-Type", "application/json"));
        // 2. mock 发消息响应
        //    必须带 message_id，否则 TODO-93 的 verifyMessageId 钩子会触发无限重试
        //    （且无读超时）导致测试永久挂起。
        mockFeishuApi.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"message_id\":\"om_int_1\"}}")
                .addHeader("Content-Type", "application/json"));

        FeishuConfig config = new FeishuConfig();
        config.setAppId("test-id");
        config.setAppSecret("test-secret");

        // 使用 package-private 构造器，将 feishuBase 指向 MockWebServer
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        FeishuMessageSender sender = new FeishuMessageSender(
                config,
                new RestTemplate(factory),
                objectMapper,
                mockFeishuApi.url("/").toString());

        sender.sendText("oc_chat_test", "集成测试消息");

        // 第一个请求：获取 token
        mockFeishuApi.takeRequest(2, TimeUnit.SECONDS);
        // 第二个请求：发送消息
        RecordedRequest msgReq = mockFeishuApi.takeRequest(2, TimeUnit.SECONDS);
        assertThat(msgReq).isNotNull();
        String body = msgReq.getBody().readUtf8();
        assertThat(body).contains("oc_chat_test");
        assertThat(body).contains("集成测试消息");
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private String buildEvent(String openId, String chatId, String text) {
        return "{\"schema\":\"2.0\","
            + "\"header\":{\"event_type\":\"im.message.receive_v1\"},"
            + "\"event\":{\"sender\":{\"sender_id\":{\"open_id\":\"" + openId + "\"}},"
            + "\"message\":{\"chat_id\":\"" + chatId + "\","
            + "\"msg_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"" + text + "\\\"}\"}}}";
    }
}
