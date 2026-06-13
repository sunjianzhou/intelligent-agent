package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 记忆管理代理端点（转发到 Python Agent /api/memory/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryProxyController extends AbstractProxyController {

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> memoryList(
            @RequestParam(defaultValue = "long_term") String memory_type,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/list")
                .queryParam("memory_type", memory_type)
                .queryParam("limit", limit)
                .build().toUriString();
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("memories", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGetAbsolute(url, req, fallback);
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Map<String, Object>> deleteMemory(
            @PathVariable String memoryId, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            proxy.delete("/api/memory/" + memoryId, userId);
            Map<String, Object> r = new HashMap<>();
            r.put("success", true);
            r.put("message", "已删除记忆 " + memoryId);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.error("删除记忆失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "删除失败");
        return ResponseEntity.ok(err);
    }

    @PatchMapping("/{memoryId}/importance")
    public ResponseEntity<Map<String, Object>> updateImportance(
            @PathVariable String memoryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return proxyPatch("/api/memory/" + memoryId + "/importance", body, req);
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchMemory(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest req) {
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/search")
                .queryParam("q", q)
                .queryParam("limit", limit)
                .build().toUriString();
        return proxyGetAbsolute(url, req,
                Collections.singletonMap("results", Collections.emptyList()));
    }

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> memoryStats(HttpServletRequest req) {
        return proxyGet("/api/memory", req,
                Collections.singletonMap("stats", new HashMap<String, Object>()));
    }

    @DeleteMapping("")
    public ResponseEntity<Map<String, Object>> clearAllMemory(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            proxy.delete("/api/memory", userId);
            return ResponseEntity.ok(Collections.singletonMap("message", "记忆已清空"));
        } catch (Exception e) {
            log.error("清空记忆失败", e);
        }
        return ResponseEntity.ok(Collections.singletonMap("message", "清空失败"));
    }

    @PostMapping("/distill")
    public ResponseEntity<Map<String, Object>> distill(HttpServletRequest req) {
        return proxyPost("/api/memory/distill", "{}", req);
    }

    @GetMapping("/summaries")
    public ResponseEntity<Map<String, Object>> memorySummaries(
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest req) {
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/summaries")
                .queryParam("limit", limit)
                .build().toUriString();
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("summaries", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGetAbsolute(url, req, fallback);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportMemory(
            @RequestParam(defaultValue = "json") String format,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/export")
                    .queryParam("format", format)
                    .build().toUriString();
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                String body = res.getBody() != null ? res.getBody() : "";
                HttpHeaders headers = new HttpHeaders();
                String disposition = res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
                if (disposition != null) headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
                headers.setContentType("markdown".equals(format)
                        ? MediaType.parseMediaType("text/markdown;charset=UTF-8")
                        : MediaType.APPLICATION_JSON);
                return ResponseEntity.ok().headers(headers)
                        .body(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("导出记忆失败", e);
        }
        return ResponseEntity.internalServerError().build();
    }

    @PostMapping("/batch-import")
    public ResponseEntity<Map<String, Object>> batchImport(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/memory/batch-import", body, req);
    }
}
