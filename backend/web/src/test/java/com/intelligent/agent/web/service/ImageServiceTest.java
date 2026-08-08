package com.intelligent.agent.web.service;

import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片生成本地化测试（TODO-110 Task 2）：provider-status / models / generate / 列表 / 删除。
 */
class ImageServiceTest {

    @TempDir
    Path tempDir;

    private MockWebServer comfyServer;
    private ImageService service;

    @BeforeEach
    void setUp() throws Exception {
        comfyServer = new MockWebServer();
        comfyServer.start();
        ComfyUiClient client = new ComfyUiClient(
                comfyServer.url("/").toString(), true, new com.fasterxml.jackson.databind.ObjectMapper());
        service = new ImageService(client);
        ReflectionTestUtils.setField(service, "dataDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "model", "model.safetensors");
        ReflectionTestUtils.setField(service, "provider", "comfyui");
        ReflectionTestUtils.setField(service, "baseUrl", comfyServer.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        comfyServer.shutdown();
    }

    @Test
    void providerStatusReflectsAvailability() {
        comfyServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"system\":{\"comfyui_version\":\"v0.2.0\"}}"));

        Map<String, Object> status = service.providerStatus();

        assertThat(status.get("available")).isEqualTo(true);
        assertThat(status.get("provider")).isEqualTo("comfyui");
    }

    @Test
    void listModelsParsesObjectInfo() {
        comfyServer.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":"
                        + "[[\"model1.safetensors\",\"model2.safetensors\"]]}}}}"));

        List<String> models = (List<String>) service.listModels().get("models");

        assertThat(models).contains("model1.safetensors", "model2.safetensors");
    }

    @Test
    void generateSavesImageAndListsIt() throws Exception {
        comfyServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"prompt_id\":\"p1\"}"));
        comfyServer.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"p1\":{\"status\":{\"status_str\":\"success\",\"completed\":true},"
                        + "\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\"}]}}}}"));
        comfyServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("png-binary-data"));

        Map<String, Object> result = service.generate("一只猫", "", 512, 512, 20, 7.0, 42);

        assertThat(result.get("success")).isEqualTo(true);
        String filename = (String) result.get("filename");
        assertThat(filename).endsWith("_a.png");
        assertThat(tempDir.resolve("images").resolve(filename)).exists();

        Map<String, Object> list = service.listImages();
        assertThat((Integer) list.get("count")).isEqualTo(1);

        Map<String, Object> deleted = service.deleteImage(filename);
        assertThat(deleted.get("success")).isEqualTo(true);
        assertThat(service.listImages().get("count")).isEqualTo(0);
    }
}
