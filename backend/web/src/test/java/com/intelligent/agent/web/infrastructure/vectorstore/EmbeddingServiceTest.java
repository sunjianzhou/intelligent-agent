package com.intelligent.agent.web.infrastructure.vectorstore;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TODO-110 Task 5：真实 embedding 服务测试（MockWebServer 模拟 Ollama /api/embed）。
 * 覆盖单条/批量解析、缓存、失败/禁用时 n-gram 兜底、维度守卫。
 */
class EmbeddingServiceTest {

    private MockWebServer server;
    private EmbeddingService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new EmbeddingService(server.url("/").toString(), "nomic-embed-text",
                Duration.ofSeconds(5), true);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void embedsSingleTextAndCachesResult() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"embeddings\":[[0.1,0.2,0.3]]}")
                .setHeader("Content-Type", "application/json"));

        double[] first = service.embed("hello world");
        double[] second = service.embed("hello world");

        assertThat(first).containsExactly(0.1, 0.2, 0.3);
        assertThat(second).isSameAs(first);
        assertThat(server.getRequestCount()).isEqualTo(1);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/embed");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"nomic-embed-text\"")
                .contains("\"input\":\"hello world\"");
    }

    @Test
    void embedsBatchInSingleRequest() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"embeddings\":[[0.1],[0.2]]}")
                .setHeader("Content-Type", "application/json"));

        List<double[]> vectors = service.embedAll(List.of("a", "b"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1);
        assertThat(vectors.get(1)).containsExactly(0.2);
        assertThat(server.getRequestCount()).isEqualTo(1);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getBody().readUtf8()).contains("\"input\":[\"a\",\"b\"]");
    }

    @Test
    void fallsBackToNGramWhenRemoteFails() {
        server.enqueue(new MockResponse().setResponseCode(500));

        double[] vector = service.embed("some text");

        assertThat(vector).hasSize(TextEmbedding.DIMENSION);
    }

    @Test
    void usesNGramWhenDisabled() {
        EmbeddingService localOnly = new EmbeddingService(
                server.url("/").toString(), "nomic-embed-text", Duration.ofSeconds(5), false);

        assertThat(localOnly.embed("some text")).hasSize(TextEmbedding.DIMENSION);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void cosineReturnsZeroOnDimensionMismatch() {
        assertThat(service.cosine(new double[]{1, 0}, new double[]{1, 0, 0})).isZero();
        assertThat(service.cosine(new double[]{1, 0}, new double[]{1, 0})).isEqualTo(1.0);
    }
}
