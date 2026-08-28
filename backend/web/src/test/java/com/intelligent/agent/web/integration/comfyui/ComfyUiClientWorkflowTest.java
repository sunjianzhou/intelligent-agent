package com.intelligent.agent.web.integration.comfyui;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R-08 后续：ControlNet / 局部重绘工作流模板 + /ws 预览帧解析。 */
class ComfyUiClientWorkflowTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> graph(Map<String, Object> workflow) {
        return (Map<String, Object>) workflow.get("prompt");
    }

    @Test
    void controlNetWorkflowInsertsControlNetChain() {
        Map<String, Object> workflow = ComfyUiClient.sdControlNetWorkflow(
                "cat", "bad", "model.safetensors", 512, 512, 20, 7.0, 1,
                "euler", List.of(), false, null, 1.0,
                "control_v11p_sd15_canny.safetensors", 1.2, "ref.png", null);
        Map<String, Object> g = graph(workflow);

        assertThat(g).containsKeys("cn_loader", "cn_img", "cn_apply");
        Map<String, Object> loader = (Map<String, Object>) g.get("cn_loader");
        assertThat(loader.get("class_type")).isEqualTo("ControlNetLoader");
        assertThat(((Map<String, Object>) loader.get("inputs")).get("control_net_name"))
                .isEqualTo("control_v11p_sd15_canny.safetensors");
        Map<String, Object> applyInputs = (Map<String, Object>)
                ((Map<String, Object>) g.get("cn_apply")).get("inputs");
        assertThat(applyInputs.get("strength")).isEqualTo(1.2);
        assertThat(applyInputs.get("control_net")).isEqualTo(List.of("cn_loader", 0));
        assertThat(applyInputs.get("image")).isEqualTo(List.of("cn_img", 0));

        Map<String, Object> kInputs = (Map<String, Object>)
                ((Map<String, Object>) g.get("3")).get("inputs");
        assertThat(kInputs.get("positive")).isEqualTo(List.of("cn_apply", 0));
        assertThat(kInputs.get("latent_image")).isEqualTo(List.of("5", 0));
    }

    @Test
    void inpaintWorkflowUsesMaskedLatent() {
        Map<String, Object> workflow = ComfyUiClient.sdControlNetWorkflow(
                "fix", "bad", "model.safetensors", 512, 512, 20, 7.0, 1,
                "euler", List.of(), false, "init.png", 0.7,
                null, 1.0, null, "mask.png");
        Map<String, Object> g = graph(workflow);

        assertThat(g).containsKeys("load_init", "load_mask", "vae_encode", "noise_mask");
        Map<String, Object> maskInputs = (Map<String, Object>)
                ((Map<String, Object>) g.get("load_mask")).get("inputs");
        assertThat(maskInputs.get("image")).isEqualTo("mask.png");
        assertThat(((Map<String, Object>) g.get("noise_mask")).get("class_type"))
                .isEqualTo("SetLatentNoiseMask");

        Map<String, Object> kInputs = (Map<String, Object>)
                ((Map<String, Object>) g.get("3")).get("inputs");
        assertThat(kInputs.get("latent_image")).isEqualTo(List.of("noise_mask", 0));
        assertThat(kInputs.get("denoise")).isEqualTo(0.7);
    }

    @Test
    void plainSdWorkflowHasNoControlNetOrMaskNodes() {
        Map<String, Object> workflow = ComfyUiClient.sdControlNetWorkflow(
                "cat", "bad", "model.safetensors", 512, 512, 20, 7.0, 1,
                "euler", List.of(), false, null, 1.0,
                null, 1.0, null, null);
        Map<String, Object> g = graph(workflow);

        assertThat(g).doesNotContainKeys("cn_loader", "cn_apply", "load_mask", "noise_mask");
        Map<String, Object> kInputs = (Map<String, Object>)
                ((Map<String, Object>) g.get("3")).get("inputs");
        assertThat(kInputs.get("positive")).isEqualTo(List.of("6", 0));
        assertThat(kInputs.get("latent_image")).isEqualTo(List.of("5", 0));
    }

    @Test
    void sdxlWorkflowKeepsSdxlSpecificsWithControlNet() {
        Map<String, Object> workflow = ComfyUiClient.sdControlNetWorkflow(
                "cat", "bad", "sdxl.safetensors", 1024, 1024, 25, 7.0, 1,
                "dpmpp_2m", List.of(), true, null, 1.0,
                "control.safetensors", 0.8, "ref.png", null);
        Map<String, Object> g = graph(workflow);

        assertThat(g).containsKey("10"); // CLIPSetLastLayer
        assertThat(((Map<String, Object>) g.get("10")).get("class_type"))
                .isEqualTo("CLIPSetLastLayer");
        assertThat(g).containsKey("cn_apply");
    }

    @Test
    void parsePreviewFrameExtractsHeaderAndImage() {
        String header = "{\"type\":\"preview\",\"data\":{\"prompt_id\":\"p1\"}}";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] image = new byte[]{1, 2, 3, 4};
        ByteBuffer buf = ByteBuffer.allocate(4 + headerBytes.length + image.length);
        buf.putInt(headerBytes.length);
        buf.put(headerBytes);
        buf.put(image);

        ComfyUiClient.PreviewFrame frame = ComfyUiClient.parsePreviewFrame(buf.array());

        assertThat(frame).isNotNull();
        assertThat(frame.type()).isEqualTo("preview");
        assertThat(frame.promptId()).isEqualTo("p1");
        assertThat(frame.image()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void parsePreviewFrameRejectsInvalidPayload() {
        assertThat(ComfyUiClient.parsePreviewFrame(null)).isNull();
        assertThat(ComfyUiClient.parsePreviewFrame(new byte[]{1, 2, 3})).isNull();
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(1000); // 头长度越界
        buf.putInt(0);
        assertThat(ComfyUiClient.parsePreviewFrame(buf.array())).isNull();
    }
}
