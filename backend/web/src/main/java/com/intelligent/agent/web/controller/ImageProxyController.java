package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.service.ImageService;
import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 图片生成端点（本地 {@link ImageService}，ComfyUI provider）。
 */
@Slf4j
@RestController
public class ImageProxyController {

    @Autowired(required = false)
    private ImageService imageService;

    @GetMapping("/api/images/{filename}")
    public ResponseEntity<byte[]> proxyImageBinary(@PathVariable String filename) {
        if (imageService == null) {
            return ResponseEntity.notFound().build();
        }
        Path file = imageService.resolveImage(filename);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(3600, java.util.concurrent.TimeUnit.SECONDS))
                    .body(Files.readAllBytes(file));
        } catch (Exception e) {
            log.warn("读取本地图片失败: {} - {}", filename, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/api/image/provider-status")
    public ResponseEntity<Map<String, Object>> getProviderStatus() {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.providerStatus());
        }
        return ResponseEntity.ok(Map.of("available", false, "message", "图片服务未初始化"));
    }

    @GetMapping("/api/image/progress")
    public ResponseEntity<Map<String, Object>> getProgress() {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.progress());
        }
        return ResponseEntity.ok(Map.of("progress", 0.0, "eta", 0.0));
    }

    @GetMapping("/api/image/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.listModels());
        }
        return ResponseEntity.ok(Map.of("models", java.util.Collections.emptyList()));
    }

    @PostMapping("/api/image/switch-model")
    public ResponseEntity<Map<String, Object>> switchModel(@RequestBody Map<String, Object> body) {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.switchModel(
                    String.valueOf(body.getOrDefault("model", ""))));
        }
        return ResponseEntity.ok(Map.of("success", false, "message", "图片服务未初始化"));
    }

    @PostMapping("/api/image/generate")
    public ResponseEntity<Map<String, Object>> generateImage(@RequestBody Map<String, Object> body) {
        if (imageService == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "图片服务未初始化"));
        }
        String prompt = String.valueOf(body.getOrDefault("prompt", "")).trim();
        if (prompt.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "prompt 不能为空"));
        }
        int width = number(body.get("width"), 512);
        int height = number(body.get("height"), 512);
        int steps = number(body.get("steps"), 20);
        double cfg = body.get("cfg") instanceof Number
                ? ((Number) body.get("cfg")).doubleValue() : 7.0;
        int seed = number(body.get("seed"), -1);
        if (seed < 0) {
            seed = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        }
        String model = String.valueOf(body.getOrDefault("model", "")).trim();
        String sampler = String.valueOf(body.getOrDefault("sampler_name", "")).trim();
        List<ComfyUiClient.Lora> loras = ComfyUiClient.parseLoras(body.get("loras"));
        return ResponseEntity.ok(imageService.generate(prompt,
                String.valueOf(body.getOrDefault("negative_prompt", "")),
                width, height, steps, cfg, seed, model, sampler, loras));
    }

    @GetMapping("/api/image/loras")
    public ResponseEntity<Map<String, Object>> listLoras() {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.listLoras());
        }
        return ResponseEntity.ok(Map.of("loras", java.util.Collections.emptyList()));
    }

    @GetMapping("/api/image/comfyui-workflow")
    public ResponseEntity<Map<String, Object>> getComfyuiWorkflow() {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.getCustomWorkflow());
        }
        return ResponseEntity.ok(Map.of("success", false, "message", "图片服务未初始化"));
    }

    @PutMapping("/api/image/comfyui-workflow")
    public ResponseEntity<Map<String, Object>> saveComfyuiWorkflow(
            @RequestBody Map<String, Object> body) {
        if (imageService == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "图片服务未初始化"));
        }
        Object workflow = body.get("workflow");
        if (!(workflow instanceof Map)) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "workflow 必须是节点图 JSON 对象"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) workflow;
        return ResponseEntity.ok(imageService.saveCustomWorkflow(graph));
    }

    @DeleteMapping("/api/image/comfyui-workflow")
    public ResponseEntity<Map<String, Object>> resetComfyuiWorkflow() {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.resetCustomWorkflow());
        }
        return ResponseEntity.ok(Map.of("success", false, "message", "图片服务未初始化"));
    }

    @GetMapping("/api/images")
    public ResponseEntity<Map<String, Object>> listImages() {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.listImages());
        }
        return ResponseEntity.ok(Map.of("images", java.util.Collections.emptyList()));
    }

    @DeleteMapping("/api/images/{filename}")
    public ResponseEntity<Map<String, Object>> deleteImage(@PathVariable String filename) {
        if (imageService != null) {
            return ResponseEntity.ok(imageService.deleteImage(filename));
        }
        return ResponseEntity.ok(Map.of("success", false));
    }

    private static int number(Object value, int defaultValue) {
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }
}
