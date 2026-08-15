package com.intelligent.agent.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.api.chat.LocalChatService;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.dto.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 聊天会话持久化契约（2026-08-15 补充）：REST /api/chat 落库会话并返回
 * user_message_id / assistant_message_id（撤回级联依赖）。
 */
class AgentServicePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void chatFullPersistsTurnAndReturnsMessageIds() throws Exception {
        LocalChatService localChatService = mock(LocalChatService.class);
        when(localChatService.complete(any(ChatRequest.class)))
                .thenReturn(Mono.just("你好，我是智能助手"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ConversationService conversationService = new ConversationService(tempDir);
        AgentService agentService = new AgentService(
                new ObjectMapper(), executor, localChatService,
                null, null, conversationService);

        ChatRequest request = new ChatRequest("你好", true, true);
        request.setUserId("alice");
        request.setSessionId("persist-session");

        Map<String, Object> result = agentService.chatFull(request);

        assertThat(result.get("response")).isEqualTo("你好，我是智能助手");
        assertThat(result.get("user_message_id")).isNotNull();
        assertThat(result.get("assistant_message_id")).isNotNull();

        Path file = tempDir.resolve("conversations/alice/persist-session.json");
        assertThat(Files.exists(file)).isTrue();
        Map<String, Object> session = new ObjectMapper().readValue(
                Files.readString(file), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) session.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role")).isEqualTo("user");
        assertThat(messages.get(1).get("role")).isEqualTo("assistant");
        assertThat(messages.get(0).get("id")).isEqualTo(result.get("user_message_id"));
        assertThat(messages.get(1).get("id")).isEqualTo(result.get("assistant_message_id"));
        executor.shutdownNow();
    }
}
