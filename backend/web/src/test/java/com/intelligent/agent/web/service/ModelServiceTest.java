package com.intelligent.agent.web.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ModelService.resolveModel：per-user 模型切换必须对推理链路生效
 * （运行时切换 → 持久化偏好 → 云端模型 → 默认模型）。
 */
class ModelServiceTest {

    private MockWebServer ollamaServer;
    private Path dataDir;
    private ModelService modelService;

    @BeforeEach
    void setUp() throws IOException {
        ollamaServer = new MockWebServer();
        ollamaServer.start();
        dataDir = Files.createTempDirectory("model-service");
        ollamaServer.enqueue(new MockResponse()
                .setBody("{\"models\":[{\"name\":\"qwen2.5:7b\"},{\"name\":\"deepseek-chat\"}]}")
                .setHeader("Content-Type", "application/json"));

        modelService = new ModelService();
        ReflectionTestUtils.setField(modelService, "ollamaBaseUrl", ollamaServer.url("/").toString());
        ReflectionTestUtils.setField(modelService, "defaultModel", "qwen2.5:7b");
        ReflectionTestUtils.setField(modelService, "dataDir", dataDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        ollamaServer.shutdown();
    }

    @Test
    void resolvesDefaultModelWithoutPreference() {
        assertThat(modelService.resolveModel("u1")).isEqualTo("qwen2.5:7b");
    }

    @Test
    void resolvesRuntimeSwitchedModel() {
        Map<String, Object> switched = modelService.switchModel("u1", "deepseek-chat");

        assertThat(switched.get("success")).isEqualTo(true);
        assertThat(modelService.resolveModel("u1")).isEqualTo("deepseek-chat");
    }

    @Test
    void resolvesPersistedPreferenceAcrossInstances() throws IOException {
        modelService.switchModel("u1", "deepseek-chat");

        ModelService reloaded = new ModelService();
        ReflectionTestUtils.setField(reloaded, "dataDir", dataDir.toString());
        ReflectionTestUtils.setField(reloaded, "defaultModel", "qwen2.5:7b");

        assertThat(reloaded.resolveModel("u1")).isEqualTo("deepseek-chat");
    }

    @Test
    void resolvesCloudModelWhenConfiguredAndNoPreference() {
        ReflectionTestUtils.setField(modelService, "cloudProvider", "custom");
        ReflectionTestUtils.setField(modelService, "cloudApiKey", "sk-cloud");
        ReflectionTestUtils.setField(modelService, "cloudModel", "gemma4-31B");

        assertThat(modelService.resolveModel("u1")).isEqualTo("gemma4-31B");
    }
}
