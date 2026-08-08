package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆管理端点（TODO-110 Task 2 本地化）。
 * <ul>
 *   <li>java / shadow：走本地 {@link MemoryRepository} + {@link ConversationMemoryService}；</li>
 *   <li>python：转发旧 Python Agent（回滚窗口用）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryProxyController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final MemoryRepository memoryRepository;
    private final ConversationMemoryService conversationMemoryService;
    private final String runtimeMode;

    public MemoryProxyController(PythonProxyService proxy,
                                 ObjectMapper objectMapper,
                                 MemoryRepository memoryRepository,
                                 ConversationMemoryService conversationMemoryService,
                                 @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.memoryRepository = memoryRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.runtimeMode = runtimeMode;
    }

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> memoryStats(HttpServletRequest req) {
        if (localRuntime()) {
            String userId = userId(req);
            Map<String, Object> longTerm = new LinkedHashMap<>();
            longTerm.put("count", memoryRepository.count(
                    MemorySearchQuery.builder(userId, "", 100000).build()));
            Map<String, Object> shortTerm = new LinkedHashMap<>();
            shortTerm.put("count", conversationMemoryService.shortTermCount(userId));
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("long_term", longTerm);
            stats.put("short_term", shortTerm);
            return ResponseEntity.ok(Map.of("stats", stats));
        }
        return proxyGet("", req);
    }

    @DeleteMapping("")
    public ResponseEntity<Map<String, Object>> clearMemory(HttpServletRequest req) {
        if (localRuntime()) {
            String userId = userId(req);
            conversationMemoryService.clearShortTerm(userId);
            memoryRepository.clear(userId);
            return ResponseEntity.ok(Map.of("message", "记忆已清空"));
        }
        return proxyDelete("", req);
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listMemories(
            @RequestParam(defaultValue = "long_term") String memory_type,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        if (localRuntime()) {
            String userId = userId(req);
            List<Map<String, Object>> memories = new ArrayList<>();
            if ("short_term".equals(memory_type)) {
                for (com.intelligent.agent.web.ai.llm.ChatMessage message
                        : conversationMemoryService.shortTermMessages(userId)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", Integer.toHexString(message.content().hashCode()));
                    item.put("content", message.content());
                    item.put("importance", 0.3);
                    item.put("category", "conversation");
                    item.put("role", message.role());
                    item.put("type", "conversation");
                    item.put("created_at", "");
                    item.put("access_count", 0);
                    memories.add(item);
                }
            } else {
                for (MemoryRecord record : memoryRepository.list(
                        MemorySearchQuery.builder(userId, "", Math.max(1, limit)).build())) {
                    memories.add(toMap(record));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("memories", memories);
            result.put("count", memories.size());
            result.put("type", memory_type);
            return ResponseEntity.ok(result);
        }
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/list")
                .queryParam("memory_type", memory_type)
                .queryParam("limit", limit)
                .build().toUriString();
        return proxyGetAbsolute(url, req);
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchMemory(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest req) {
        if (localRuntime()) {
            String userId = userId(req);
            List<Map<String, Object>> results = new ArrayList<>();
            for (MemoryRecord record : memoryRepository.search(userId, q, Math.max(1, limit))) {
                results.add(toMap(record));
            }
            return ResponseEntity.ok(Map.of("results", results));
        }
        String url = UriComponentsBuilder
                .fromHttpUrl(proxy.getBaseUrl() + "/api/memory/search")
                .queryParam("q", q)
                .queryParam("limit", limit)
                .build().toUriString();
        return proxyGetAbsolute(url, req);
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Map<String, Object>> deleteMemory(
            @PathVariable String memoryId, HttpServletRequest req) {
        if (localRuntime()) {
            boolean ok = memoryRepository.delete(userId(req), memoryId);
            return ResponseEntity.ok(Map.of(
                    "success", ok,
                    "message", ok ? "已删除记忆 " + memoryId : "记忆 " + memoryId + " 不存在"));
        }
        return proxyDelete("/" + memoryId, req);
    }

    @PatchMapping("/{memoryId}/importance")
    public ResponseEntity<Map<String, Object>> updateImportance(
            @PathVariable String memoryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (localRuntime()) {
            String userId = userId(req);
            double importance = Math.max(0.0, Math.min(1.0,
                    body.get("importance") instanceof Number
                            ? ((Number) body.get("importance")).doubleValue() : 0.5));
            MemoryRecord existing = memoryRepository.list(
                    MemorySearchQuery.builder(userId, "", 1000).build()).stream()
                    .filter(r -> memoryId.equals(r.id())).findFirst().orElse(null);
            if (existing == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "记忆不存在"));
            }
            memoryRepository.upsert(existing.withImportance(importance));
            return ResponseEntity.ok(Map.of(
                    "success", true, "memory_id", memoryId, "importance", importance));
        }
        return proxyPatch("/" + memoryId + "/importance", body, req);
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private String userId(HttpServletRequest req) {
        if (proxy != null) {
            String userId = proxy.extractUserIdFromRequest(req);
            if (userId != null) {
                return userId;
            }
        }
        return "default";
    }

    private static Map<String, Object> toMap(MemoryRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", record.id());
        item.put("content", record.content());
        item.put("importance", Math.round(record.importance() * 100) / 100.0);
        Object category = record.metadata().get("category");
        Object type = record.metadata().get("type");
        item.put("category", category != null ? category
                : (type != null ? type : (record.type() != null ? record.type() : "unknown")));
        item.put("role", record.metadata().getOrDefault("role", ""));
        item.put("type", type != null ? type : (record.type() != null ? record.type() : ""));
        item.put("created_at", record.createdAt() != null ? record.createdAt().toString() : "");
        item.put("access_count", record.accessCount());
        return item;
    }

    // ── Python 回退 ───────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> proxyGet(String path, HttpServletRequest req) {
        return proxyGetAbsolute(proxy.getBaseUrl() + "/api/memory" + path, req);
    }

    private ResponseEntity<Map<String, Object>> proxyGetAbsolute(String url, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("GET {} 失败", url, e);
        }
        return ResponseEntity.ok(Map.of("memories", Collections.emptyList(), "count", 0));
    }

    private ResponseEntity<Map<String, Object>> proxyDelete(String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete("/api/memory" + path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("DELETE {} 失败", path, e);
        }
        return ResponseEntity.ok(errorResponse());
    }

    private ResponseEntity<Map<String, Object>> proxyPatch(String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch("/api/memory" + path, json, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("PATCH {} 失败", path, e);
        }
        return ResponseEntity.ok(errorResponse());
    }

    private static Map<String, Object> errorResponse() {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return err;
    }
}
