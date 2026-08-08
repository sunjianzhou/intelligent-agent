package com.intelligent.agent.web.ai.tool.builtin.feishu;

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
 * 飞书日历/任务工具测试（MockWebServer）：list/create 与 OAuth token 解析。
 */
class FeishuToolTest {

    @TempDir
    Path tempDir;

    private MockWebServer feishuServer;
    private FeishuChannelClient channelClient;

    @BeforeEach
    void setUp() throws Exception {
        feishuServer = new MockWebServer();
        feishuServer.start();
        channelClient = new FeishuChannelClient(mock(FeishuMessageSender.class), tempDir, true);
        channelClient.saveUserToken("ou_test", "user-token-1", "refresh-1", 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        feishuServer.shutdown();
    }

    @Test
    void calendarListCallsFeishuApi() throws Exception {
        feishuServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"items\":[{\"event_id\":\"ev1\"}]}}"));

        FeishuCalendarTool tool = new FeishuCalendarTool(
                channelClient, feishuServer.url("/").toString());
        Map<?, ?> result = (Map<?, ?>) tool.execute(Map.of(
                "action", "list", "calendar_id", "cal_1",
                "open_id", "ou_test", "start_time", "100", "end_time", "200"));

        assertThat(result.get("code")).isEqualTo(0);
        assertThat(feishuServer.getRequestCount()).isEqualTo(1);
        assertThat(feishuServer.takeRequest().getHeader("Authorization"))
                .isEqualTo("Bearer user-token-1");
    }

    @Test
    void calendarCreatePostsEvent() {
        feishuServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"event_id\":\"ev_new\"}}"));

        FeishuCalendarTool tool = new FeishuCalendarTool(
                channelClient, feishuServer.url("/").toString());
        Map<?, ?> result = (Map<?, ?>) tool.execute(Map.of(
                "action", "create", "calendar_id", "cal_1", "open_id", "ou_test",
                "summary", "会议", "start_time", "100", "end_time", "200"));

        assertThat(result.get("code")).isEqualTo(0);
    }

    @Test
    void taskListRequiresOAuthToken() {
        FeishuTaskTool tool = new FeishuTaskTool(channelClient, feishuServer.url("/").toString());

        Map<?, ?> result = (Map<?, ?>) tool.execute(Map.of("action", "list"));

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("message"))).contains("OAuth 授权");
    }

    @Test
    void taskCreatePostsTask() {
        feishuServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"task\":{\"guid\":\"t1\"}}}"));

        FeishuTaskTool tool = new FeishuTaskTool(channelClient, feishuServer.url("/").toString());
        Map<?, ?> result = (Map<?, ?>) tool.execute(Map.of(
                "action", "create", "open_id", "ou_test", "summary", "写周报"));

        assertThat(result.get("code")).isEqualTo(0);
    }
}
