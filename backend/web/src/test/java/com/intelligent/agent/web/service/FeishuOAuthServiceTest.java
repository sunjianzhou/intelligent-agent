package com.intelligent.agent.web.service;

import com.intelligent.agent.web.feishu.FeishuConfig;
import com.intelligent.agent.web.feishu.FeishuMessageSender;
import com.intelligent.agent.web.integration.feishu.FeishuChannelClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 飞书 OAuth 本地化测试（TODO-110 Task 2）：authorize / callback / status。
 */
class FeishuOAuthServiceTest {

    @TempDir
    Path tempDir;

    private MockWebServer feishuServer;
    private FeishuOAuthService service;
    private FeishuChannelClient channelClient;

    @BeforeEach
    void setUp() throws Exception {
        feishuServer = new MockWebServer();
        feishuServer.start();

        FeishuConfig config = new FeishuConfig();
        config.setAppId("cli_test_app");
        config.setAppSecret("test-secret");
        channelClient = new FeishuChannelClient(
                mock(FeishuMessageSender.class), tempDir, true);
        service = new FeishuOAuthService(
                config, channelClient, feishuServer.url("/").toString(), "https://example.com/cb");
    }

    @AfterEach
    void tearDown() throws Exception {
        feishuServer.shutdown();
    }

    @Test
    void authorizeReturnsAuthUrlWithAppIdAndState() {
        Map<String, Object> result = service.authorize("ou_test");

        assertThat(result.get("auth_url")).asString()
                .contains("app_id=cli_test_app")
                .contains("state=");
        assertThat(result.get("state")).asString().endsWith(":ou_test");
    }

    @Test
    void callbackExchangesCodeAndPersistsToken() {
        feishuServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"app_access_token\":\"app-token-1\"}"));
        feishuServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"access_token\":\"user-token-1\","
                        + "\"refresh_token\":\"refresh-1\"}}"));

        String html = service.callback("code-123", "state-abc:ou_test");

        assertThat(html).contains("飞书授权成功");
        Map<String, Object> token = channelClient.getUserToken("ou_test");
        assertThat(token.get("access_token")).isEqualTo("user-token-1");
        assertThat(service.status("ou_test").get("authorized")).isEqualTo(true);
    }

    @Test
    void statusFalseWhenNotAuthorized() {
        assertThat(service.status("ou_none").get("authorized")).isEqualTo(false);
    }

    @Test
    void callbackRejectsMissingState() {
        String html = service.callback("code-123", null);
        assertThat(html).contains("授权失败");
    }
}
