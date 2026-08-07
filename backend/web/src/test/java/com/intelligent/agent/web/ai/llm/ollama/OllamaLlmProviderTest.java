package com.intelligent.agent.web.ai.llm.ollama;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.OllamaOptions;
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
}
