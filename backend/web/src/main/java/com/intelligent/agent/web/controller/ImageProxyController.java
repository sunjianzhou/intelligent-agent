package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.service.ImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 图片生成端点（TODO-110 Task 2 本地化）。
 * java / shadow：走本地 {@link ImageService}（ComfyUI provider，本地图片目录）；
 * python：回退代理旧 Python 服务。
 */
@Slf4j
@RestController
public class ImageProxyController extends AbstractProxyController {

    @Value("${intelligent-agent.python-service.base-url:http://localhost:8000}")
    private String pythonBaseUrl;

    @Autowired(required = false)
    private ImageService imageService;

    @Value("${ai.runtime.mode:python}")
    private String runtimeMode;

    private final RestTemplate binaryRestTemplate = new RestTemplate();

    @GetMapping("/api/images/{filename}")
    public ResponseEntity<byte[]> proxyImageBinary(@PathVariable String filename) {
        if (localRuntime() && imageService != null) {
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
        String url = pythonBaseUrl + "/api/images/" + filename;
        try {
            ResponseEntity<byte[]> resp = binaryRestTemplate.exchange(
                    url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
            MediaType contentType = resp.getHeaders().getContentType();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(contentType != null ? contentType : MediaType.IMAGE_PNG);
            headers.setCacheControl(CacheControl.maxAge(3600, java.util.concurrent.TimeUnit.SECONDS));
            return ResponseEntity.ok().headers(headers).body(resp.getBody());
        } catch (Exception e) {
            log.warn("图片代理失败: {} - {}", filename, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/api/image/provider-status")
    public ResponseEntity<Map<String, Object>> getProviderStatus(HttpServletRequest req) {
        if (localRuntime() && imageService != null) {
            return ResponseEntity.ok(imageService.providerStatus());
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("available", false);
        fallback.put("message", "无法连接到 Python 服务");
        return proxyGet("/api/image/provider-status", req, fallback);
    }

    @GetMapping("/api/image/progress")
    public ResponseEntity<Map<String, Object>> getProgress(HttpServletRequest req) {
        if (localRuntime() && imageService != null) {
            return ResponseEntity.ok(imageService.progress());
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("progress", 0.0);
        fallback.put("eta", 0.0);
        return proxyGet("/api/image/progress", req, fallback);
    }

    @GetMapping("/api/image/models")
    public ResponseEntity<Map<String, Object>> listModels(HttpServletRequest req) {
        if (localRuntime() && imageService != null) {
            return ResponseEntity.ok(imageService.listModels());
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("models", Collections.emptyList());
        return proxyGet("/api/image/models", req, fallback);
    }

    @PostMapping("/api/image/switch-model")
    public ResponseEntity<Map<String, Object>> switchModel(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime() && imageService != null) {
            return ResponseEntity.ok(imageService.switchModel(
                    String.valueOf(body.getOrDefault("model", ""))));
        }
        return proxyPost("/api/image/switch-model", body, req);
    }

    @PostMapping("/api/image/generate")
    public ResponseEntity<Map<String, Object>> generateImage(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime() && imageService != null) {
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
            return ResponseEntity.ok(imageService.generate(prompt,
                    String.valueOf(body.getOrDefault("negative_prompt", "")),
                    width, height, steps, cfg, seed));
        }
        return proxyPost("/api/image/generate", body, req);
    }

    @GetMapping("/api/images")
    public ResponseEntity<Map<String, Object>> listImages(HttpServletRequest req) {
        if (localRuntime() && imageService != null) {
            return ResponseEntity.ok(imageService.listImages());
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("images", Collections.emptyList());
        return proxyGet("/api/images", req, fallback);
    }

    @DeleteMapping("/api/images/{filename}")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @PathVariable String filename, HttpServletRequest req) {
        if (localRuntime() && imageService != null) {
            return ResponseEntity.ok(imageService.deleteImage(filename));
        }
        return proxyDelete("/api/images/" + filename, req);
    }

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private static int number(Object value, int defaultValue) {
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }
}
