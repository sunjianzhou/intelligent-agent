package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class FeishuMessageSenderTest {

    private MockWebServer server;
    private FeishuMessageSender sender;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        FeishuConfig config = new FeishuConfig();
        config.setAppId("test-app-id");
        config.setAppSecret("test-app-secret");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);

        sender = new FeishuMessageSender(config, new RestTemplate(factory),
                new ObjectMapper(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void getTenantAccessToken_callsApi_andCaches() throws Exception {
        server.enqueue(tokenResponse("tok-001", 7200));

        String tok1 = sender.getTenantAccessToken();
        String tok2 = sender.getTenantAccessToken();  // 命中缓存

        assertThat(tok1).isEqualTo("tok-001");
        assertThat(tok2).isEqualTo("tok-001");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void getTenantAccessToken_refreshes_whenExpiredSoon() throws Exception {
        server.enqueue(tokenResponse("tok-old", 200));   // 200s < 300s 阈值
        server.enqueue(tokenResponse("tok-new", 7200));

        String first  = sender.getTenantAccessToken();
        String second = sender.getTenantAccessToken();

        assertThat(first).isEqualTo("tok-old");
        assertThat(second).isEqualTo("tok-new");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void sendText_postsToFeishuApi() throws Exception {
        server.enqueue(tokenResponse("tok-send", 7200));
        server.enqueue(new MockResponse().setBody("{\"code\":0}").setResponseCode(200));

        sender.sendText("oc_chat123", "Hello 飞书");

        server.takeRequest();  // token 请求
        RecordedRequest msgReq = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(msgReq).isNotNull();
        assertThat(msgReq.getPath()).contains("/im/v1/messages");
        String body = msgReq.getBody().readUtf8();
        assertThat(body).contains("oc_chat123");
        assertThat(body).contains("Hello 飞书");
    }

    @Test
    void sendText_retries_onServerError_thenSendsFallback() throws Exception {
        server.enqueue(tokenResponse("tok-retry", 7200));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        // fallback 消息
        server.enqueue(new MockResponse().setBody("{\"code\":0}").setResponseCode(200));

        sender.sendText("chat-err", "触发重试");

        assertThat(server.getRequestCount()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void sendText_returnsMessageId() throws Exception {
        server.enqueue(tokenResponse("tok-id", 7200));
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"message_id\":\"om_abc123\"}}")
                .setResponseCode(200));

        String msgId = sender.sendText("oc_chat123", "Hello");

        assertThat(msgId).isEqualTo("om_abc123");
    }

    @Test
    void sendText_returnsNull_whenFallbackAlsoFails() throws Exception {
        server.enqueue(tokenResponse("tok-fail", 7200));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));  // fallback 也失败

        String msgId = sender.sendText("chat-err", "触发重试");

        assertThat(msgId).isNull();
    }

    @Test
    void recall_postsDeleteToCorrectUrl() throws Exception {
        server.enqueue(tokenResponse("tok-recall", 7200));
        server.enqueue(new MockResponse().setBody("{\"code\":0}").setResponseCode(200));

        sender.recall("om_abc123");

        server.takeRequest();  // token 请求
        RecordedRequest recallReq = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq).isNotNull();
        assertThat(recallReq.getMethod()).isEqualTo("DELETE");
        assertThat(recallReq.getPath()).contains("/im/v1/messages/om_abc123");
    }

    @Test
    void recall_throwsOnApiError() throws Exception {
        server.enqueue(tokenResponse("tok-recall-err", 7200));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"code\":1,\"msg\":\"too late\"}"));

        assertThatThrownBy(() -> sender.recall("om_expired"))
                .isInstanceOf(RuntimeException.class);
    }

    private MockResponse tokenResponse(String token, int expire) {
        return new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"" + token
                        + "\",\"expire\":" + expire + "}")
                .addHeader("Content-Type", "application/json")
                .setResponseCode(200);
    }
}
