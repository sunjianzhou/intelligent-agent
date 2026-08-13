package com.intelligent.agent.web.ai.llm.cloud;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI 兼容 provider 协议测试：SSE 逐 token + [DONE]、非流式、
 * Bearer 认证头，以及凭据脱敏。
 */
class OpenAiCompatibleLlmProviderTest {

    private MockWebServer server;
    private OpenAiCompatibleLlmProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new OpenAiCompatibleLlmProvider(server.url("/").toString(),
                "sk-test-123", "deepseek-chat", Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void configureAndClearToggleProviderAvailability() {
        OpenAiCompatibleLlmProvider runtime = new OpenAiCompatibleLlmProvider(
                "", "", "", Duration.ofSeconds(10));

        assertThat(runtime.isConfigured()).isFalse();

        runtime.configure("http://localhost:9000/v1", "sk-runtime", "deepseek-chat");

        assertThat(runtime.isConfigured()).isTrue();

        runtime.clearConfig();

        assertThat(runtime.isConfigured()).isFalse();
    }

    @Test
    void streamsSseTokensAndDone() {
        server.enqueue(new MockResponse()
                .setBody("""
                        data: {"choices":[{"delta":{"content":"你"}}]}

                        data: {"choices":[{"delta":{"content":"好"}}]}

                        data: [DONE]
                        """)
                .setHeader("Content-Type", "text/event-stream"));

        StepVerifier.create(provider.stream(
                        ChatTurn.of("deepseek-chat", List.of(ChatMessage.user("hi")))))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("你"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("好"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void completeReturnsFirstChoiceContent() {
        server.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}")
                .setHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.complete(
                        ChatTurn.of("deepseek-chat", List.of(ChatMessage.user("hi")))))
                .expectNext("你好")
                .verifyComplete();
    }

    @Test
    void completeWithToolsParsesNativeToolCalls() {
        server.enqueue(new MockResponse()
                .setBody("""
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"call_1","type":"function","function":{"name":"calculator","arguments":"{\\"expression\\":\\"2*3\\"}"}},
                          {"id":"call_2","type":"function","function":{"name":"web_search","arguments":{"query":"java"}}}
                        ]}}]}
                        """)
                .setHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.completeWithTools(
                        ChatTurn.of("deepseek-chat", List.of(ChatMessage.user("算一下"))),
                        List.of(new ToolDefinition("calculator", "计算", true, null, null))))
                .assertNext(resp -> {
                    assertThat(resp.hasNativeToolCalls()).isTrue();
                    assertThat(resp.toolCalls()).containsExactly(
                            ToolCall.of("calculator", Map.of("expression", "2*3")),
                            ToolCall.of("web_search", Map.of("query", "java")));
                })
                .verifyComplete();
    }

    @Test
    void sendsToolsPayloadToChatCompletions() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
                .setHeader("Content-Type", "application/json"));
        ToolDefinition def = new ToolDefinition("calculator", "计算工具", true, null, null,
                Map.of("type", "object",
                        "properties", Map.of("expression", Map.of("type", "string")),
                        "required", List.of("expression")));
        provider.completeWithTools(
                        ChatTurn.of("deepseek-chat", List.of(ChatMessage.user("hi"))),
                        List.of(def))
                .block();

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"tools\":[")
                .contains("\"type\":\"function\"")
                .contains("\"name\":\"calculator\"")
                .contains("\"parameters\":{")
                .contains("\"type\":\"object\"");
    }

    @Test
    void serializesNativeToolCallsInHistory() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
                .setHeader("Content-Type", "application/json"));
        ChatMessage assistant = ChatMessage.assistant("", List.of(Map.of(
                "id", "call_0",
                "function", Map.of("name", "calculator", "arguments", Map.of("expression", "1+2")))));
        ChatTurn turn = new ChatTurn("u1", "deepseek-chat",
                List.of(assistant, ChatMessage.tool("3", "call_0")), Map.of());
        provider.complete(turn).block();

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"tool_calls\":[")
                .contains("\"id\":\"call_0\"")
                .contains("\"type\":\"function\"")
                .contains("\"arguments\":\"{\\\"expression\\\":\\\"1+2\\\"}\"")
                .contains("\"role\":\"tool\"")
                .contains("\"tool_call_id\":\"call_0\"");
    }

    @Test
    void sendsBearerTokenToChatCompletions() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
                .setHeader("Content-Type", "application/json"));
        provider.complete(ChatTurn.of("deepseek-chat", List.of(ChatMessage.user("hi")))).block();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-test-123");
    }

    @Test
    void redactsApiKeyFromErrors() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("Invalid API key sk-test-123"));

        StepVerifier.create(provider.complete(
                        ChatTurn.of("deepseek-chat", List.of(ChatMessage.user("hi")))))
                .expectErrorSatisfies(t -> {
                    assertThat(t).isInstanceOf(LlmProviderException.class);
                    assertThat(t.getMessage()).doesNotContain("sk-test-123");
                })
                .verify();
    }
}
