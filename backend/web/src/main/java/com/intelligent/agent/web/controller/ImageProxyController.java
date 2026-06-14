package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 图片生成代理端点：
 *   - /api/images/{filename}      — 二进制图片流（无 JWT，浏览器直接访问）
 *   - /api/image/provider-status  — Provider 状态查询
 *   - /api/image/models           — 模型列表
 *   - /api/image/switch-model     — 切换模型
 *   - /api/image/generate         — REST 生成图片
 *   - /api/images                 — 历史图片列表
 *   - DELETE /api/images/{filename} — 删除图片
 */
@Slf4j
@RestController
public class ImageProxyController extends AbstractProxyController {

    @Value("${intelligent-agent.python-service.base-url:http://localhost:8000}")
    private String pythonBaseUrl;

    // ── 二进制图片流代理（不需要 JWT，保持 RestTemplate 直传）────────────────

    private final RestTemplate binaryRestTemplate = new RestTemplate();

    @GetMapping("/api/images/{filename}")
    public ResponseEntity<byte[]> proxyImageBinary(@PathVariable String filename) {
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

    // ── JSON API 代理 ──────────────────────────────────────────────────────────

    @GetMapping("/api/image/provider-status")
    public ResponseEntity<Map<String, Object>> getProviderStatus(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("available", false);
        fallback.put("message", "无法连接到 Python 服务");
        return proxyGet("/api/image/provider-status", req, fallback);
    }

    @GetMapping("/api/image/progress")
    public ResponseEntity<Map<String, Object>> getProgress(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("progress", 0.0);
        fallback.put("eta", 0.0);
        return proxyGet("/api/image/progress", req, fallback);
    }

    @GetMapping("/api/image/models")
    public ResponseEntity<Map<String, Object>> listModels(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("models", Collections.emptyList());
        return proxyGet("/api/image/models", req, fallback);
    }

    @PostMapping("/api/image/switch-model")
    public ResponseEntity<Map<String, Object>> switchModel(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/image/switch-model", body, req);
    }

    @PostMapping("/api/image/generate")
    public ResponseEntity<Map<String, Object>> generateImage(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/image/generate", body, req);
    }

    @GetMapping("/api/images")
    public ResponseEntity<Map<String, Object>> listImages(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("images", Collections.emptyList());
        return proxyGet("/api/images", req, fallback);
    }

    @DeleteMapping("/api/images/{filename}")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @PathVariable String filename, HttpServletRequest req) {
        return proxyDelete("/api/images/" + filename, req);
    }
}
