package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理端点（本地 {@link MemoryRepository} + {@link ConversationMemoryService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryProxyController {

    private final MemoryRepository memoryRepository;
    private final ConversationMemoryService conversationMemoryService;

    public MemoryProxyController(MemoryRepository memoryRepository,
                                 ConversationMemoryService conversationMemoryService) {
        this.memoryRepository = memoryRepository;
        this.conversationMemoryService = conversationMemoryService;
    }

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> memoryStats(HttpServletRequest req) {
        String userId = UserContext.userId(req);
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

    @DeleteMapping("")
    public ResponseEntity<Map<String, Object>> clearMemory(HttpServletRequest req) {
        String userId = UserContext.userId(req);
        conversationMemoryService.clearShortTerm(userId);
        memoryRepository.clear(userId);
        return ResponseEntity.ok(Map.of("message", "记忆已清空"));
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listMemories(
            @RequestParam(defaultValue = "long_term") String memory_type,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        String userId = UserContext.userId(req);
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

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchMemory(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest req) {
        String userId = UserContext.userId(req);
        List<Map<String, Object>> results = new ArrayList<>();
        for (MemoryRecord record : memoryRepository.search(userId, q, Math.max(1, limit))) {
            results.add(toMap(record));
        }
        return ResponseEntity.ok(Map.of("results", results));
    }

    /** 会话摘要列表（2026-08-15 补齐，对齐 Python /api/memory/summaries）。 */
    @GetMapping("/summaries")
    public ResponseEntity<Map<String, Object>> memorySummaries(
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest req) {
        String userId = UserContext.userId(req);
        List<Map<String, Object>> summaries = memoryRepository.list(
                        MemorySearchQuery.builder(userId, "", Math.max(1, limit))
                                .type("summary").build())
                .stream().map(MemoryProxyController::toMap).toList();
        return ResponseEntity.ok(Map.of(
                "summaries", summaries,
                "count", summaries.size()));
    }

    /** 记忆导出（json/markdown，2026-08-15 补齐，对齐 Python /api/memory/export）。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportMemory(
            @RequestParam(defaultValue = "json") String format,
            HttpServletRequest req) throws IOException {
        String userId = UserContext.userId(req);
        List<MemoryRecord> records = memoryRepository.list(
                MemorySearchQuery.builder(userId, "", 100_000).build());
        String contentType;
        String extension;
        String content;
        if ("markdown".equals(format)) {
            contentType = "text/markdown;charset=UTF-8";
            extension = "md";
            StringBuilder sb = new StringBuilder("# 记忆导出\n\n");
            for (MemoryRecord record : records) {
                sb.append("- [").append(record.type() == null ? "memory" : record.type())
                        .append("] ").append(record.content()).append('\n');
            }
            content = sb.toString();
        } else {
            contentType = "application/json;charset=UTF-8";
            extension = "json";
            List<Map<String, Object>> items = records.stream()
                    .map(MemoryProxyController::toMap).toList();
            content = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(items);
        }
        String filename = "memory-export-" + Instant.now().toEpochMilli() + "." + extension;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    /** 手动触发记忆蒸馏 + 摘要（2026-08-15 补齐，对齐 Python /api/memory/distill）。 */
    @PostMapping("/distill")
    public ResponseEntity<Map<String, Object>> distillMemory(HttpServletRequest req) {
        String userId = UserContext.userId(req);
        int records = conversationMemoryService.distillNow(userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "distilled", records));
    }

    /** 批量导入记忆（2026-08-15 补齐，对齐 Python /api/memory/batch-import）。 */
    @PostMapping("/batch-import")
    public ResponseEntity<Map<String, Object>> batchImport(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = UserContext.userId(req);
        List<?> items = body.get("items") instanceof List
                ? (List<?>) body.get("items") : List.of();
        List<String> importedIds = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object contentObj = entry.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj).trim();
            if (content.isBlank() || "null".equals(content)) {
                continue;
            }
            Object categoryObj = entry.get("category");
            String category = categoryObj == null ? "" : String.valueOf(categoryObj).trim();
            double importance = entry.get("importance") instanceof Number
                    ? ((Number) entry.get("importance")).doubleValue() : 0.5;
            MemoryRecord record = new MemoryRecord(
                    "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    userId, content, null, null,
                    category.isBlank() ? "knowledge" : category,
                    Map.of("category", category.isBlank() ? "knowledge" : category),
                    importance);
            memoryRepository.upsert(record);
            importedIds.add(record.id());
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "imported_count", importedIds.size(),
                "memory_ids", importedIds));
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Map<String, Object>> deleteMemory(
            @PathVariable String memoryId, HttpServletRequest req) {
        boolean ok = memoryRepository.delete(UserContext.userId(req), memoryId);
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "message", ok ? "已删除记忆 " + memoryId : "记忆 " + memoryId + " 不存在"));
    }

    @PatchMapping("/{memoryId}/importance")
    public ResponseEntity<Map<String, Object>> updateImportance(
            @PathVariable String memoryId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = UserContext.userId(req);
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
}
