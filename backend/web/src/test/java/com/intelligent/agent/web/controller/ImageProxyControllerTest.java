package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.service.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ImageProxyController 契约测试（TD-07）。
 * <p>覆盖此前零测试引用的图片端点：参数校验、服务缺失降级、二进制代理与错误路径。
 * imageService 为 {@code @Autowired(required=false)} 字段，未注入时必须降级而不是 NPE。
 */
class ImageProxyControllerTest {

    @TempDir
    Path tempDir;

    private ImageProxyController controller;
    private ImageService imageService;

    @BeforeEach
    void setUp() {
        controller = new ImageProxyController();
        imageService = mock(ImageService.class);
    }

    /** 注入 mock 服务（模拟 Spring 已完成装配）。 */
    private void withService() {
        ReflectionTestUtils.setField(controller, "imageService", imageService);
    }

    @Test
    void generateImage_blankPrompt_returns400WithoutCallingService() {
        withService();

        ResponseEntity<Map<String, Object>> resp = controller.generateImage(Map.of("prompt", "   "));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).isEqualTo("prompt 不能为空");
        verifyNoInteractions(imageService);
    }

    @Test
    void generateImage_withoutService_degradesGracefully() {
        ResponseEntity<Map<String, Object>> resp = controller.generateImage(Map.of("prompt", "cat"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
        assertThat(resp.getBody().get("message")).isEqualTo("图片服务未初始化");
    }

    @Test
    void generateImage_appliesDefaultsAndForwardsOptionalParams() {
        withService();
        when(imageService.generate(eq("cat"), eq(""), eq(512), eq(512), eq(20), eq(7.0), anyInt(),
                eq(""), eq(""), anyList(), eq(""), eq(0.75), eq(""), eq(1.0), eq(""), eq("")))
                .thenReturn(Map.of("success", true, "mode", "txt2img"));

        ResponseEntity<Map<String, Object>> resp = controller.generateImage(Map.of("prompt", "cat"));

        assertThat(resp.getBody().get("mode")).isEqualTo("txt2img");
        verify(imageService).generate(eq("cat"), eq(""), eq(512), eq(512), eq(20), eq(7.0), anyInt(),
                eq(""), eq(""), anyList(), eq(""), eq(0.75), eq(""), eq(1.0), eq(""), eq(""));
    }

    @Test
    void proxyImageBinary_unknownFile_returns404() {
        withService();
        when(imageService.resolveImage("nope.png")).thenReturn(null);

        assertThat(controller.proxyImageBinary("nope.png").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyImageBinary_knownFile_returnsPngWithLongCache() throws Exception {
        Path png = tempDir.resolve("a.png");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        withService();
        when(imageService.resolveImage("a.png")).thenReturn(png);

        ResponseEntity<byte[]> resp = controller.proxyImageBinary("a.png");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(resp.getHeaders().getCacheControl()).contains("max-age=3600");
        assertThat(resp.getBody()).hasSize(4);
    }

    @Test
    void saveComfyuiWorkflow_nonGraphPayload_returns400() {
        withService();

        ResponseEntity<Map<String, Object>> resp =
                controller.saveComfyuiWorkflow(Map.of("workflow", "not-a-graph"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).isEqualTo("workflow 必须是节点图 JSON 对象");
        verifyNoInteractions(imageService);
    }

    @Test
    void progress_withoutService_returnsZeroSnapshot() {
        ResponseEntity<Map<String, Object>> resp = controller.getProgress();

        assertThat(resp.getBody().get("progress")).isEqualTo(0.0);
        assertThat(resp.getBody().get("eta")).isEqualTo(0.0);
    }
}
