package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 显式记忆工具（2026-08-15 补齐，对齐 Python FunctionTool 的 store_memory /
 * search_memories）：让 LLM 主动写入/检索长期记忆，userId 取自
 * {@link ToolExecutionContext}（自动记忆之外的能力补充）。
 */
public class MemoryTool implements AgentTool {

    public static final String STORE_MEMORY = "store_memory";
    public static final String SEARCH_MEMORIES = "search_memories";

    private final String name;
    private final VectorMemoryRepository repository;

    public MemoryTool(String name, VectorMemoryRepository repository) {
        this.name = name;
        this.repository = repository;
    }

    @Override
    public ToolDefinition definition() {
        if (STORE_MEMORY.equals(name)) {
            return new ToolDefinition(name,
                    "把一条值得长期记住的信息写入记忆系统（自动蒸馏之外的显式记忆）。"
                            + "参数: content(内容,必填), category(分类,可选,默认knowledge),"
                            + " importance(重要度0-1,可选,默认0.5)。",
                    false, null, Duration.ofSeconds(30),
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "content", Map.of("type", "string", "description", "要记住的内容"),
                                    "category", Map.of("type", "string", "description", "分类"),
                                    "importance", Map.of("type", "number", "description", "重要度 0-1")),
                            "required", List.of("content")));
        }
        return new ToolDefinition(name,
                "从长期记忆中检索与查询语义相关的内容。参数: query(查询,必填), limit(条数,默认5)。",
                true, null, Duration.ofSeconds(30),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "检索关键词/问题"),
                                "limit", Map.of("type", "integer", "description", "返回条数，默认 5")),
                        "required", List.of("query")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        return execute(arguments, null);
    }

    @Override
    public Object execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String userId = context == null || context.userId() == null || context.userId().isBlank()
                ? "default" : context.userId();
        if (STORE_MEMORY.equals(name)) {
            return store(arguments, userId);
        }
        return search(arguments, userId);
    }

    private Object store(Map<String, Object> arguments, String userId) {
        String content = str(arguments.get("content"));
        if (content.isBlank()) {
            return Map.of("success", false, "error", "content 不能为空");
        }
        String category = str(arguments.get("category"));
        double importance = dblOr(arguments.get("importance"), 0.5);
        MemoryRecord record = new MemoryRecord(
                "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                userId, content, null, null,
                category.isBlank() ? "knowledge" : category, Map.of(), importance);
        repository.upsert(record);
        return Map.of("success", true, "memory_id", record.id());
    }

    private Object search(Map<String, Object> arguments, String userId) {
        String query = str(arguments.get("query"));
        if (query.isBlank()) {
            return Map.of("success", false, "error", "query 不能为空");
        }
        int limit = Math.max(1, intOr(arguments.get("limit"), 5));
        List<MemoryRecord> results = repository.search(
                MemorySearchQuery.of(userId, query, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (MemoryRecord record : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", record.content());
            item.put("importance", Math.round(record.importance() * 1000) / 1000.0);
            item.put("memory_id", record.id());
            item.put("type", record.type());
            items.add(item);
        }
        return Map.of("success", true, "results", items, "count", items.size());
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static double dblOr(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
