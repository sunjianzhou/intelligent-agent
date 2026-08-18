package com.intelligent.agent.web.api.chat;

import com.intelligent.agent.web.ai.agent.AgentOrchestrator;
import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.ConfigRuntimeService;
import com.intelligent.agent.web.service.ModelService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 聊天请求构建：把已保存的 runtime 配置注入 AgentRequestContext.options。 */
class LocalChatServiceTest {

    @Test
    void injectsSavedRuntimeLlmOptionsIntoContext() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ConfigRuntimeService config = mock(ConfigRuntimeService.class);
        when(config.llmRequestOptions()).thenReturn(Map.of(
                "temperature", 0.3,
                "max_tokens", 1024L,
                "num_ctx", 8192L,
                "chat_timeout", 120L));
        LocalChatService service = new LocalChatService(
                orchestrator, mock(ModelService.class), config);

        service.complete(new ChatRequest("hi", true, true));

        ArgumentCaptor<AgentRequestContext> captor =
                ArgumentCaptor.forClass(AgentRequestContext.class);
        verify(orchestrator).complete(captor.capture());
        assertThat(captor.getValue().options())
                .containsEntry("temperature", 0.3)
                .containsEntry("max_tokens", 1024L)
                .containsEntry("num_ctx", 8192L)
                .containsEntry("chat_timeout", 120L);
    }

    @Test
    void keepsOptionsEmptyWithoutRuntimeConfigService() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        LocalChatService service = new LocalChatService(orchestrator, mock(ModelService.class));

        service.complete(new ChatRequest("hi", true, true));

        ArgumentCaptor<AgentRequestContext> captor =
                ArgumentCaptor.forClass(AgentRequestContext.class);
        verify(orchestrator).complete(captor.capture());
        assertThat(captor.getValue().options()).isEmpty();
    }
}
