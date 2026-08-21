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

    @Test
    void sdxlModelUsesSdxlTemplate() throws Exception {
        ReflectionTestUtils.setField(service, "model", "sdxl_base.safetensors");
        enqueueSuccessfulGeneration("p2");

        service.generate("cat", "", 512, 512, 20, 7.0, 42);

        String body = comfyServer.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"CLIPSetLastLayer\"")
                .contains("\"stop_at_clip_layer\":-2")
                .contains("\"sdxl_base.safetensors\"")
                .doesNotContain("UNETLoader");
    }

    @Test
    void fluxModelUsesFluxTemplate() throws Exception {
        ReflectionTestUtils.setField(service, "model", "flux1-dev.safetensors");
        enqueueSuccessfulGeneration("p3");

        service.generate("cat", "", 1024, 1024, 20, 7.0, 42);

        String body = comfyServer.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"UNETLoader\"")
                .contains("\"EmptySD3LatentImage\"")
                .contains("\"t5xxl_fp8_e4m3fn.safetensors\"")
                .contains("\"cfg\":1.0")
                .doesNotContain("CheckpointLoaderSimple");
    }

    @Test
    void loraInjectionChainsLoraLoader() throws Exception {
        enqueueSuccessfulGeneration("p4");

        service.generate("cat", "", 512, 512, 20, 7.0, 42, null, "dpmpp_2m",
                List.of(new ComfyUiClient.Lora("detail.safetensors", 0.8, 0.9)));

        String body = comfyServer.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"LoraLoader\"")
                .contains("\"detail.safetensors\"")
                .contains("\"strength_model\":0.8")
                .contains("\"strength_clip\":0.9")
                // KSampler 模型引用指向 LoRA 节点输出，而非 checkpoint
                .contains("\"model\":[\"100\",0]");
    }

    @Test
    void customWorkflowSubstitutesPlaceholders() throws Exception {
        Map<String, Object> graph = Map.of(
                "1", Map.of("class_type", "CheckpointLoaderSimple",
                        "inputs", Map.of("ckpt_name", "{{model}}")),
                "2", Map.of("class_type", "CLIPTextEncode",
                        "inputs", Map.of("text", "{{prompt}}", "clip", List.of("1", 1))),
                "3", Map.of("class_type", "CLIPTextEncode",
                        "inputs", Map.of("text", "{{negative_prompt}}", "clip", List.of("1", 1))),
                "4", Map.of("class_type", "EmptyLatentImage",
                        "inputs", Map.of("width", "{{width}}", "height", "{{height}}", "batch_size", 1)),
                "5", Map.of("class_type", "KSampler",
                        "inputs", Map.of("seed", "{{seed}}", "steps", "{{steps}}", "cfg", "{{cfg}}",
                                "sampler_name", "euler", "scheduler", "normal", "denoise", 1.0,
                                "model", List.of("1", 0), "positive", List.of("2", 0),
                                "negative", List.of("3", 0), "latent_image", List.of("4", 0))),
                "6", Map.of("class_type", "VAEDecode",
                        "inputs", Map.of("samples", List.of("5", 0), "vae", List.of("1", 2))),
                "7", Map.of("class_type", "SaveImage",
                        "inputs", Map.of("filename_prefix", "agent_gen", "images", List.of("6", 0))));
        assertThat(service.saveCustomWorkflow(graph).get("success")).isEqualTo(true);
        assertThat(service.getCustomWorkflow().get("using_custom")).isEqualTo(true);

        enqueueSuccessfulGeneration("p5");
        service.generate("测试图", "低质量", 768, 1024, 25, 7.5, 99);

        String body = comfyServer.takeRequest().getBody().readUtf8();
        assertThat(body).contains("测试图").contains("低质量")
                .contains("\"model.safetensors\"")
                .contains("\"width\":768").contains("\"height\":1024")
                .contains("\"steps\":25").contains("\"cfg\":7.5").contains("\"seed\":99")
                .doesNotContain("{{");
        // 排空本次生成的 history/view 请求，避免污染下次断言
        comfyServer.takeRequest();
        comfyServer.takeRequest();

        assertThat(service.resetCustomWorkflow().get("success")).isEqualTo(true);
        assertThat(service.getCustomWorkflow().get("using_custom")).isEqualTo(false);

        enqueueSuccessfulGeneration("p6");
        service.generate("cat", "", 512, 512, 20, 7.0, 42);
        assertThat(comfyServer.takeRequest().getBody().readUtf8())
                .contains("\"CheckpointLoaderSimple\"");
    }

    @Test
    void switchModelAppliesToNextGeneration() throws Exception {
        comfyServer.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":"
                        + "[[\"model.safetensors\",\"new.safetensors\"]]}}}}"));
        Map<String, Object> switched = service.switchModel("new.safetensors");
        assertThat(switched.get("success")).isEqualTo(true);
        // 排空 switchModel 触发的 /object_info 查询
        comfyServer.takeRequest();

        enqueueSuccessfulGeneration("p7");
        service.generate("cat", "", 512, 512, 20, 7.0, 42);

        assertThat(comfyServer.takeRequest().getBody().readUtf8())
                .contains("\"new.safetensors\"");
    }

    @Test
    void listLorasParsesObjectInfo() {
        comfyServer.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"LoraLoader\":{\"input\":{\"required\":{\"lora_name\":"
                        + "[[\"detail.safetensors\",\"style.safetensors\"]]}}},"
                        + "\"LoraLoaderModelOnly\":{\"input\":{\"required\":{\"lora_name\":"
                        + "[[\"flux-lora.safetensors\"]]}}}}"));

        List<String> loras = (List<String>) service.listLoras().get("loras");

        assertThat(loras).containsExactly("detail.safetensors", "style.safetensors",
                "flux-lora.safetensors");
    }

    @Test
    void parseLorasSupportsAllShapes() {
        assertThat(ComfyUiClient.parseLoras(List.of("a.safetensors:0.8:0.9", "b.safetensors")))
                .containsExactly(
                        new ComfyUiClient.Lora("a.safetensors", 0.8, 0.9),
                        new ComfyUiClient.Lora("b.safetensors", 1.0, 1.0));
        assertThat(ComfyUiClient.parseLoras(List.of(
                Map.of("name", "c.safetensors", "strength_model", 0.5))))
                .containsExactly(new ComfyUiClient.Lora("c.safetensors", 0.5, 1.0));
        assertThat(ComfyUiClient.parseLoras("d.safetensors:0.6, e.safetensors"))
                .containsExactly(
                        new ComfyUiClient.Lora("d.safetensors", 0.6, 0.6),
                        new ComfyUiClient.Lora("e.safetensors", 1.0, 1.0));
    }

    private void enqueueSuccessfulGeneration(String promptId) {
        comfyServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"prompt_id\":\"" + promptId + "\"}"));
        comfyServer.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"" + promptId + "\":{\"status\":{\"status_str\":\"success\",\"completed\":true},"
                        + "\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\"}]}}}}"));
        comfyServer.enqueue(new MockResponse().setResponseCode(200)
                .setBody("png-binary-data"));
    }
}
