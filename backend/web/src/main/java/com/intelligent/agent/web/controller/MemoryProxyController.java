package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 记忆管理代理端点（转发到 Python Agent /api/memory/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryProxyController {


    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> memoryList(
            @RequestParam(defaultValue = "long_term") String memory_type,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/list")
                    .queryParam("memory_type", memory_type)
                    .queryParam("limit", limit)
                    .build().toUriString();
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取记忆列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("memories", new ArrayList<>());
        fallback.put("count", 0);
        return ResponseEntity.ok(fallback);
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Map<String, Object>> deleteMemory(@PathVariable String memoryId,
            HttpServletRequest req) {
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
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch("/api/memory/" + memoryId + "/importance", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("更新记忆重要性失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchMemory(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/search")
                    .queryParam("q", q)
                    .queryParam("limit", limit)
                    .build().toUriString();
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("搜索记忆失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("results", new ArrayList<>());
        return ResponseEntity.ok(fallback);
    }

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> memoryStats(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/memory", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取记忆统计失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("stats", new HashMap<>());
        return ResponseEntity.ok(fallback);
    }

    @DeleteMapping("")
    public ResponseEntity<Map<String, Object>> clearAllMemory(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            proxy.delete("/api/memory", userId);
            Map<String, Object> r = new HashMap<>();
            r.put("message", "记忆已清空");
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.error("清空记忆失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("message", "清空失败");
        return ResponseEntity.ok(err);
    }

    @PostMapping("/distill")
    public ResponseEntity<Map<String, Object>> distill(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/memory/distill", "{}", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("触发记忆蒸馏失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "记忆蒸馏失败");
        return ResponseEntity.ok(err);
    }

    @GetMapping("/summaries")
    public ResponseEntity<Map<String, Object>> memorySummaries(
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/summaries")
                    .queryParam("limit", limit)
                    .build().toUriString();
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取记忆摘要失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("summaries", new ArrayList<>());
        fallback.put("count", 0);
        return ResponseEntity.ok(fallback);
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
    public ResponseEntity<Map<String, Object>> batchImport(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.post("/api/memory/batch-import", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("批量导入记忆失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }
}
