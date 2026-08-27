package com.intelligent.agent.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.api.chat.LocalChatService;
import com.intelligent.agent.web.domain.analytics.AnalyticsService;
import com.intelligent.agent.web.dto.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-10：月用量限额在 AgentService 入口拦截（chatFull 返回 quota_exceeded，不触达本地服务）。
 */
class AgentServiceQuotaTest {

    @Test
    void chatFullRejectsWhenMonthlyQuotaExceeded() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        when(analytics.usageQuotaExceeded("u1")).thenReturn(true);
        LocalChatService local = mock(LocalChatService.class);
        AgentService service = new AgentService(
                new ObjectMapper(), Executors.newSingleThreadExecutor(), local,
                null, null, null, null, analytics);

        ChatRequest request = new ChatRequest("你好", true, true);
        request.setUserId("u1");

        Map<String, Object> result = service.chatFull(request);

        assertThat(result.get("quota_exceeded")).isEqualTo(true);
        assertThat(result.get("success")).isEqualTo(false);
        verify(analytics).usageQuotaExceeded("u1");
    }
}
