package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * 将 /api/images/{filename} 请求代理到 Python Agent，以二进制流返回生成图片。
 * 图片端点不需要 JWT（Python 侧已白名单放行），直接转发即可。
 */
@Slf4j
@RestController
public class ImageProxyController {

    @Value("${intelligent-agent.python-service.base-url:http://localhost:8000}")
    private String pythonBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/api/images/{filename}")
    public ResponseEntity<byte[]> proxyImage(@PathVariable String filename) {
        String url = pythonBaseUrl + "/api/images/" + filename;
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);

            MediaType contentType = resp.getHeaders().getContentType();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(contentType != null ? contentType : MediaType.IMAGE_PNG);
            // 允许浏览器缓存图片 1 小时，避免重复下载
            headers.setCacheControl(CacheControl.maxAge(3600, java.util.concurrent.TimeUnit.SECONDS));

            return ResponseEntity.ok().headers(headers).body(resp.getBody());
        } catch (Exception e) {
            log.warn("图片代理失败: {} - {}", filename, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
