package com.intelligent.agent.web.integration;

import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.im.ChannelMessage;
import com.intelligent.agent.web.im.ChannelType;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import com.intelligent.agent.web.integration.telegram.TelegramChannelClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通道广播去重 + 具名集成客户端测试（Plan 2 / Task 5）。
 */
class ChannelDeduplicationTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ── 广播去重 ─────────────────────────────────────────────

    @Test
    void broadcastDeliversExactlyOncePerChannel() {
        ChannelClient feishu = mock(ChannelClient.class);
        when(feishu.channelType()).thenReturn("feishu_im");
        when(feishu.isEnabled()).thenReturn(true);
        when(feishu.send(any(ChannelMessage.class)))
                .thenReturn(new DeliveryResult(true, "feishu_im:mid-1", null, "feishu_im"));

        ChannelRouter router = new ChannelRouter(List.of(feishu), Duration.ofMinutes(5));
        ChannelMessage message = new ChannelMessage(
                ChannelType.FEISHU, "u1", "hello", "feishu:orig-1", "dedup-1",
                "text", "chat-1", "p2p", false, "pending", Map.of());

        assertThat(router.broadcast(message).deliveries()).allMatch(DeliveryResult::accepted);
        assertThat(router.broadcast(message).deliveries()).allMatch(DeliveryResult::accepted);
        verify(feishu, times(1)).send(message);
    }

    @Test
    void broadcastSkipsDisabledChannels() {
        ChannelClient disabled = mock(ChannelClient.class);
        when(disabled.channelType()).thenReturn("telegram");
        when(disabled.isEnabled()).thenReturn(false);

        ChannelRouter router = new ChannelRouter(List.of(disabled), Duration.ofMinutes(5));
        ChannelMessage message = new ChannelMessage(
                ChannelType.TELEGRAM, "u1", "hello", "tg:orig", "dedup-tg",
                "text", "chat-1", "p2p", false, "pending", Map.of());

        assertThat(router.broadcast(message).deliveries()).isEmpty();
        verify(disabled, times(0)).send(any());
    }

    // ── Telegram 客户端（MockWebServer，重试） ────────────────

    @Test
    void telegramClientSendsWithRetryAndParsesMessageId() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"ok\":false}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"ok\":true,\"result\":{\"message_id\":42}}"));

        TelegramChannelClient client = new TelegramChannelClient(
                "test-token", server.url("/").toString(), true,
                new com.intelligent.agent.web.im.RetryConfig(2, 0.01, 0.05, 2.0));

        ChannelMessage message = new ChannelMessage(
                ChannelType.TELEGRAM, "u1", "hi", "tg:orig", "dedup-tg",
                "text", "chat-1", "p2p", false, "pending", Map.of());

        DeliveryResult result = client.send(message);

        assertThat(result.accepted()).isTrue();
        assertThat(result.messageId()).isEqualTo("telegram:42");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // ── ComfyUI 客户端（MockWebServer，进度追踪） ─────────────

    @Test
    void comfyUiClientSubmitsAndTracksProgress() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"system\":{\"comfyui_version\":\"v0.2.0\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"prompt_id\":\"p1\"}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"p1\":{\"status\":{\"status_str\":\"success\",\"completed\":true},"
                        + "\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\"}]}}}}"));
        // progress() 内部会再次轮询 history
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"p1\":{\"status\":{\"status_str\":\"success\",\"completed\":true}}}"));

        ComfyUiClient client = new ComfyUiClient(
                server.url("/").toString(), true, new com.fasterxml.jackson.databind.ObjectMapper());

        Map<String, Object> stats = client.systemStats();
        assertThat(stats.get("system")).isNotNull();

        String promptId = client.submitPrompt(Map.of("prompt", "test"));
        assertThat(promptId).isEqualTo("p1");

        Map<String, Object> history = client.history("p1");
        assertThat(client.progress("p1")).isEqualTo(1.0);
        assertThat(history.containsKey("p1")).isTrue();
    }

    // ── 调度器到期计算 ────────────────────────────────────────

    @Test
    void schedulerRunsDueDelayTask() {
        TaskService taskService = new TaskService();
        Map<String, Object> created = taskService.createTask(Map.of(
                "name", "提醒", "action", "log", "schedule_type", "delay",
                "delay_seconds", -1, "args", Map.of("message", "该吃药了")));
        String taskId = (String) ((Map<?, ?>) created.get("task")).get("id");

        TaskSchedulerService scheduler = new TaskSchedulerService(
                taskService, java.nio.file.Path.of("target", "scheduler-test"));

        scheduler.tick();

        Map<String, Object> task = taskService.allTasks().stream()
                .filter(t -> taskId.equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(task.get("status")).isEqualTo("completed");
        assertThat(((Number) task.get("run_count")).intValue()).isEqualTo(1);
        assertThat(task.get("last_run")).isNotNull();
    }

    @Test
    void schedulerRunsLlmGenerateAction() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.complete(any(ChatTurn.class))).thenReturn(Mono.just("生成的提醒文本"));
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        when(router.forUser(any(), any())).thenReturn(provider);

        TaskService taskService = new TaskService();
        Map<String, Object> created = taskService.createTask(Map.of(
                "name", "日报", "action", "llm_generate", "schedule_type", "delay",
                "delay_seconds", -1, "args", Map.of("message", "生成今日日报")));
        String taskId = (String) ((Map<?, ?>) created.get("task")).get("id");

        TaskSchedulerService scheduler = new TaskSchedulerService(
                taskService, java.nio.file.Path.of("target", "scheduler-test"), null, router);
        scheduler.tick();

        Map<String, Object> task = taskService.allTasks().stream()
                .filter(t -> taskId.equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(task.get("status")).isEqualTo("completed");
        assertThat(task.get("last_result")).isEqualTo("生成的提醒文本");
    }
}
