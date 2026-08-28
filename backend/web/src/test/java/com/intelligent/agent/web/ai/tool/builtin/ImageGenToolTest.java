package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import com.intelligent.agent.web.service.ImageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** R-08 后续：Chat 内 ImageGenTool 参数补全（sampler/LoRA/img2img/ControlNet/蒙版）。 */
class ImageGenToolTest {

    @Test
    void passesExtendedParametersToImageService() {
        ImageService service = mock(ImageService.class);
        when(service.generate(anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                anyDouble(), anyInt(), any(), any(), any(), any(), anyDouble(),
                any(), anyDouble(), any(), any()))
                .thenReturn(Map.of("success", true,
                        "image_url", "/api/images/a.png", "prompt_id", "p1"));
        ImageGenTool tool = new ImageGenTool(service);

        String text = String.valueOf(tool.execute(Map.of(
                "prompt", "cat",
                "model", "custom.safetensors",
                "sampler", "dpmpp_2m",
                "loras", List.of("detail.safetensors:0.8"),
                "init_image_base64", "aW5pdA==",
                "denoising_strength", 0.6,
                "controlnet", "control.safetensors",
                "controlnet_strength", 1.2,
                "control_image_base64", "Y250cmw=",
                "mask_image_base64", "bWFzaw==")));

        assertThat(text).contains("图片已生成");
        verify(service).generate(eq("cat"), eq(""), eq(1024), eq(1024), eq(20), eq(7.0),
                anyInt(), eq("custom.safetensors"), eq("dpmpp_2m"),
                eq(List.of(new ComfyUiClient.Lora("detail.safetensors", 0.8, 0.8))),
                eq("aW5pdA=="), eq(0.6),
                eq("control.safetensors"), eq(1.2), eq("Y250cmw="), eq("bWFzaw=="));
    }

    @Test
    void rejectsBlankPromptWithoutCallingService() {
        ImageService service = mock(ImageService.class);
        ImageGenTool tool = new ImageGenTool(service);

        assertThat(String.valueOf(tool.execute(Map.of("prompt", "  ")))).contains("不能为空");
        verifyNoInteractions(service);
    }

    @Test
    void definitionExposesImageToolParameters() {
        ImageGenTool tool = new ImageGenTool(mock(ImageService.class));
        Map<?, ?> params = tool.definition().parameters();
        Map<String, Object> properties =
                (Map<String, Object>) params.get("properties");

        assertThat(properties).containsKeys("prompt", "sampler", "loras",
                "init_image_base64", "denoising_strength",
                "controlnet", "controlnet_strength",
                "control_image_base64", "mask_image_base64");
    }
}
