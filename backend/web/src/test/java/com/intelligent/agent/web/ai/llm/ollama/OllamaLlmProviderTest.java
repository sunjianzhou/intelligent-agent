package com.intelligent.agent.web.ai.llm.ollama;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.OllamaOptions;
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
 * Ollama provider 协议测试：NDJSON 流式逐 token + done，
 * 非流式完整回复，以及 /api/chat 请求体形状。
 */
class OllamaLlmProviderTest {

    private MockWebServer server;
    private OllamaLlmProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new OllamaLlmProvider(server.url("/").toString(), "qwen2.5:7b",
                OllamaOptions.defaults(), Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void streamsTokensAndDoneEvent() {
        server.enqueue(new MockResponse()
                .setBody("""
                        {"message":{"content":"你"},"done":false}
                        {"message":{"content":"好"},"done":false}
                        {"message":{"content":""},"done":true}
                        """)
                .setHeader("Content-Type", "application/x-ndjson"));

        StepVerifier.create(provider.stream(
                        ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("hi")))))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("你"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("好"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void completeReturnsFullText() {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"你好世界\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.complete(
                        ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("hi")))))
                .expectNext("你好世界")
                .verifyComplete();
    }

    @Test
    void completeWithToolsParsesNativeToolCalls() {
        server.enqueue(new MockResponse()
                .setBody("""
                        {"message":{"role":"assistant","content":"","tool_calls":[
                          {"function":{"name":"calculator","arguments":{"expression":"1+2"}}},
                          {"function":{"name":"time_tool","arguments":"{\\"action\\":\\"timestamp\\"}"}}
                        ]},"done":true}
                        """)
                .setHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.completeWithTools(
                        ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("算一下"))),
                        List.of(new ToolDefinition("calculator", "计算", true, null, null))))
                .assertNext(resp -> {
                    assertThat(resp.content()).isEmpty();
                    assertThat(resp.hasNativeToolCalls()).isTrue();
                    assertThat(resp.toolCalls()).containsExactly(
                            ToolCall.of("calculator", Map.of("expression", "1+2")),
                            ToolCall.of("time_tool", Map.of("action", "timestamp")));
                })
                .verifyComplete();
    }

    @Test
    void completeWithToolsFallsBackToPlainContent() {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"直接回答\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.completeWithTools(
                        ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("hi"))),
                        List.of(new ToolDefinition("calculator", "计算", true, null, null))))
                .assertNext(resp -> {
                    assertThat(resp.content()).isEqualTo("直接回答");
                    assertThat(resp.hasNativeToolCalls()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void sendsToolsPayloadToApiChat() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));
        ToolDefinition def = new ToolDefinition("calculator", "计算工具", true, null, null,
                Map.of("type", "object",
                        "properties", Map.of("expression", Map.of("type", "string")),
                        "required", List.of("expression")));
        provider.completeWithTools(
                        ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("hi"))),
                        List.of(def))
                .block();

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"tools\":[")
                .contains("\"type\":\"function\"")
                .contains("\"name\":\"calculator\"")
                .contains("\"parameters\":{")
                .contains("\"type\":\"object\"")
                .contains("\"required\":[\"expression\"]");
    }

    @Test
    void serializesNativeToolCallsInHistory() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));
        ChatMessage assistant = ChatMessage.assistant("", List.of(Map.of(
                "id", "call_0",
                "function", Map.of("name", "calculator", "arguments", Map.of("expression", "1+2")))));
        ChatTurn turn = new ChatTurn("u1", "qwen2.5:7b",
                List.of(assistant, ChatMessage.tool("3", "call_0")), Map.of());
        provider.complete(turn).block();

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"role\":\"assistant\"")
                .contains("\"tool_calls\":[{\"function\":{")
                .contains("\"name\":\"calculator\"")
                .doesNotContain("\"id\":\"call_0\"")
                .contains("\"role\":\"tool\"");
    }

    @Test
    void postsChatPayloadToApiChat() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));
        provider.complete(ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("hi")))).block();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/chat");
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        String body = request.getBody().readUtf8();
        assertThat(body)
                .contains("\"model\":\"qwen2.5:7b\"")
                .contains("\"stream\":false")
                .contains("\"role\":\"user\"");
    }

    @Test
    void attachesImagesToUserMessage() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));
        ChatTurn turn = new ChatTurn("u1", "qwen2.5:7b",
                List.of(ChatMessage.system("sys"), ChatMessage.user("看图")),
                Map.of(), List.of("aGVsbG8="));
        provider.complete(turn).block();

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"images\":[\"aGVsbG8=\"]");
    }

    @Test
    void sendsNumericKeepAliveAsNumberNotString() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));
        provider.complete(ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("hi")))).block();

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"keep_alive\":-1");
        assertThat(body).doesNotContain("\"keep_alive\":\"-1\"");
    }

    @Test
    void sendsDurationKeepAliveAsString() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}")
                .setHeader("Content-Type", "application/json"));
        OllamaLlmProvider hourProvider = new OllamaLlmProvider(
                server.url("/").toString(), "qwen2.5:7b",
                new OllamaOptions(0.7, 2048, 0.9, 40, 1.1, 4096, -1, "1h"),
                Duration.ofSeconds(10));
        hourProvider.complete(ChatTurn.of("qwen2.5:7b", List.of(ChatMessage.user("hi")))).block();

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"keep_alive\":\"1h\"");
    }
}
